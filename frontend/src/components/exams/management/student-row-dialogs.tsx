"use client";

import { useMemo, useState } from "react";
import { useForm, useWatch } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { Check, Trash2, Upload, UserCheck, UserX } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { AdminFormDialog } from "@/components/courses/admin/admin-dialog";
import { FormField, describedBy, fieldIds } from "@/components/courses/admin/form-field";
import { mutationErrorMessage } from "@/components/courses/admin/confirm-delete-dialog";
import {
  ExamFilePicker,
  FileLinkButton,
  ResultLabelBadge,
  UploadProgress,
  marksText,
  sequentialLabel,
} from "@/components/exams/management/management-shared";
import { candidateName } from "@/lib/api/exam-enrollment";
import { marksNumber } from "@/lib/api/question-bank";
import { isApiClientError } from "@/lib/api/error";
import {
  useDeleteSubmissionDocument,
  useSaveExamResult,
  useUploadMarkedPaper,
  uploadSequentially,
  useUploadSubmissionDocument,
  type SequentialUploadProgress,
  type Attendance,
  type ManagedStudentRow,
} from "@/lib/api/exam-management";
import type { ExamDetail } from "@/lib/api/exam-config";
import { cn } from "@/lib/utils";

export type Toast = (message: { kind: "success" | "error"; text: string }) => void;

export function studentLabel(row: ManagedStudentRow): string {
  const name = candidateName(row);
  return row.indexNumber ? `${name} (${row.indexNumber})` : name;
}

function totalNumber(exam: ExamDetail): number | null {
  if (exam.totalMarks === null || exam.totalMarks === undefined || exam.totalMarks === "") return null;
  return marksNumber(exam.totalMarks);
}

/** Splits a server 400 into field messages (`marks`, `reason`) and a general message. */
function splitServerError(error: unknown): { marks?: string; reason?: string; general?: string } {
  if (isApiClientError(error) && error.fieldErrors.length > 0) {
    const out: { marks?: string; reason?: string; general?: string } = {};
    const rest: string[] = [];
    for (const field of error.fieldErrors) {
      if (field.field === "marks") out.marks = field.message;
      else if (field.field === "reason") out.reason = field.message;
      else rest.push(field.message);
    }
    if (rest.length > 0) out.general = rest.join(" ");
    if (!out.marks && !out.reason && !out.general) out.general = error.message;
    return out;
  }
  return { general: mutationErrorMessage(error) };
}

type MarksValues = { marks: string; reason: string };

/** Zod schema for the marks form (UX pre-check only; the server enforces the same rules). */
export function marksSchema(total: number | null, existing: number | null) {
  return z
    .object({ marks: z.string(), reason: z.string().max(500, "Keep the reason under 500 characters.") })
    .superRefine((values, ctx) => {
      const raw = values.marks.trim();
      const n = Number(raw);
      if (!raw || !Number.isFinite(n)) {
        ctx.addIssue({ code: "custom", path: ["marks"], message: "Enter the marks." });
        return;
      }
      if (n < 0) ctx.addIssue({ code: "custom", path: ["marks"], message: "Marks can't be negative." });
      else if (total !== null && n > total)
        ctx.addIssue({ code: "custom", path: ["marks"], message: `Marks can't be more than the total of ${marksText(total)}.` });
      else if (Math.round(n * 100) !== n * 100)
        ctx.addIssue({ code: "custom", path: ["marks"], message: "Use at most 2 decimal places." });
      if (existing !== null && n !== existing && values.reason.trim().length === 0) {
        ctx.addIssue({ code: "custom", path: ["reason"], message: "Give a reason for changing the marks." });
      }
    });
}

/**
 * "Enter marks" (WRITTEN / PHYSICAL). Pre-checks `0 ≤ marks ≤ totalMarks` and
 * the reason-on-change rule for quick feedback; the server enforces both and
 * audit-logs the before/after values. Mounted per opening (fresh state).
 */
export function EnterMarksDialog({
  exam,
  row,
  open,
  onOpenChange,
  onToast,
}: {
  exam: ExamDetail;
  row: ManagedStudentRow | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onToast: Toast;
}) {
  const save = useSaveExamResult(exam.id);
  const total = totalNumber(exam);
  const existing = row?.marks === null || row?.marks === undefined || row?.marks === "" ? null : marksNumber(row.marks);
  const schema = useMemo(() => marksSchema(total, existing), [total, existing]);
  const form = useForm<MarksValues>({
    resolver: zodResolver(schema),
    defaultValues: { marks: existing === null ? "" : String(existing), reason: "" },
  });
  const [general, setGeneral] = useState<string | null>(null);
  const marksIds = fieldIds("exam-marks", "value");
  const reasonIds = fieldIds("exam-marks", "reason");
  const marksError = form.formState.errors.marks?.message ?? null;
  const reasonError = form.formState.errors.reason?.message ?? null;
  const watched = useWatch({ control: form.control, name: "marks" }) ?? "";
  const changingExisting = existing !== null && watched.trim() !== "" && Number(watched) !== existing;

  const onSubmit = form.handleSubmit(async (values) => {
    if (!row) return;
    setGeneral(null);
    const marks = Number(values.marks.trim());
    if (existing !== null && marks === existing) {
      onOpenChange(false);
      return;
    }
    try {
      await save.mutateAsync({
        studentId: row.studentId,
        body: {
          marks,
          // Never sent with marks: the server sets PRESENT for physical marks; written exams have no attendance.
          attendance: null,
          reason: values.reason.trim() ? values.reason.trim() : null,
        },
      });
      onToast({ kind: "success", text: `Marks saved for ${candidateName(row)}.` });
      onOpenChange(false);
    } catch (error) {
      const split = splitServerError(error);
      if (split.marks) form.setError("marks", { type: "server", message: split.marks }, { shouldFocus: true });
      if (split.reason) form.setError("reason", { type: "server", message: split.reason }, { shouldFocus: !split.marks });
      setGeneral(split.general ?? null);
    }
  });

  return (
    <AdminFormDialog
      open={open}
      onOpenChange={onOpenChange}
      title={existing === null ? "Enter marks" : "Change marks"}
      description={row ? `${studentLabel(row)}${total !== null ? ` · out of ${marksText(total)}` : ""}` : undefined}
      testId="exam-marks-dialog"
      className="sm:max-w-md"
      footer={
        <>
          <Button type="button" variant="outline" onClick={() => onOpenChange(false)} disabled={save.isPending}>
            Cancel
          </Button>
          <Button type="submit" form="exam-marks-form" disabled={save.isPending || !row} aria-busy={save.isPending} data-testid="exam-marks-save">
            <Check aria-hidden="true" />
            {save.isPending ? "Saving…" : "Save marks"}
          </Button>
        </>
      }
    >
      <form id="exam-marks-form" noValidate onSubmit={onSubmit} className="flex flex-col gap-4">
        <FormField
          ids={marksIds}
          label="Marks"
          required
          helper={total !== null ? `Between 0 and ${marksText(total)}.` : "Zero or more."}
          error={marksError}
        >
          <Input
            id={marksIds.inputId}
            type="number"
            inputMode="decimal"
            min={0}
            max={total ?? undefined}
            step="0.01"
            aria-invalid={marksError ? true : undefined}
            aria-describedby={describedBy(marksIds, true, Boolean(marksError))}
            data-testid="exam-marks-input"
            {...form.register("marks")}
          />
        </FormField>
        {existing !== null ? (
          <FormField
            ids={reasonIds}
            label="Reason for change"
            required={changingExisting}
            helper={`Current marks: ${marksText(existing)}. A reason is required when you change existing marks; it is kept in the audit log.`}
            error={reasonError}
          >
            <Textarea
              id={reasonIds.inputId}
              maxLength={500}
              rows={3}
              aria-invalid={reasonError ? true : undefined}
              aria-describedby={describedBy(reasonIds, true, Boolean(reasonError))}
              data-testid="exam-marks-reason"
              {...form.register("reason")}
            />
          </FormField>
        ) : null}
        {general ? (
          <Alert variant="destructive" data-testid="exam-marks-error">
            <AlertDescription>{general}</AlertDescription>
          </Alert>
        ) : null}
      </form>
    </AdminFormDialog>
  );
}

/**
 * "Enter answer sheet" (WRITTEN): staff upload on the student's behalf, and
 * the existing documents with open/delete. The row is re-read from the
 * management query after every change, so the list stays current.
 */
export function AnswerSheetDialog({
  examId,
  row,
  open,
  onOpenChange,
  onOpenDocument,
  onToast,
}: {
  examId: string;
  row: ManagedStudentRow | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onOpenDocument: (row: ManagedStudentRow, documentId: string) => void;
  onToast: Toast;
}) {
  const upload = useUploadSubmissionDocument(examId);
  const remove = useDeleteSubmissionDocument(examId);
  const [files, setFiles] = useState<File[]>([]);
  const [progress, setProgress] = useState<SequentialUploadProgress | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [confirmId, setConfirmId] = useState<string | null>(null);
  const documents = row?.answerDocuments ?? [];

  async function submit() {
    if (!row) return;
    if (files.length === 0) {
      setError("Attach at least one file.");
      return;
    }
    setError(null);
    const studentId = row.studentId;
    // One request per file (server request ceiling); failed files stay selected for a retry.
    const result = await uploadSequentially(
      files,
      (file, onProgress) => upload.mutateAsync({ studentId, file, onProgress }),
      setProgress
    );
    setProgress(null);
    setFiles(result.failed.map((entry) => entry.file));
    if (result.uploaded.length > 0) {
      onToast({ kind: "success", text: `${result.uploaded.length} ${result.uploaded.length === 1 ? "file" : "files"} uploaded for ${candidateName(row)}.` });
    }
    if (result.failed.length > 0) {
      setError(result.failed.map((entry) => `${entry.file.name}: ${mutationErrorMessage(entry.error)}`).join(" "));
    }
  }

  async function deleteDocument(documentId: string) {
    if (!row) return;
    setError(null);
    try {
      await remove.mutateAsync({ studentId: row.studentId, documentId });
      setConfirmId(null);
      onToast({ kind: "success", text: "Answer document deleted." });
    } catch (failure) {
      setError(mutationErrorMessage(failure));
    }
  }

  const uploading = progress !== null;
  const busy = uploading || remove.isPending;

  return (
    <AdminFormDialog
      open={open}
      onOpenChange={onOpenChange}
      title="Enter answer sheet"
      description={row ? `Upload the answer sheet for ${studentLabel(row)}.` : undefined}
      testId="exam-answer-sheet-dialog"
      footer={
        <>
          <Button type="button" variant="outline" onClick={() => onOpenChange(false)} disabled={busy}>
            Close
          </Button>
          <Button type="button" onClick={() => void submit()} disabled={busy || !row} aria-busy={uploading} data-testid="exam-answer-sheet-upload">
            <Upload aria-hidden="true" />
            {uploading ? "Uploading…" : files.length > 0 ? `Upload ${files.length} ${files.length === 1 ? "file" : "files"}` : "Upload"}
          </Button>
        </>
      }
    >
      <div className="flex min-w-0 flex-col gap-5">
        <section aria-labelledby="answer-docs-title" className="flex min-w-0 flex-col gap-2">
          <h3 id="answer-docs-title" className="text-sm font-semibold text-foreground">
            Uploaded documents
          </h3>
          {documents.length === 0 ? (
            <p className="rounded-lg border border-dashed border-border px-3 py-4 text-center text-sm text-muted-foreground" data-testid="exam-answer-docs-empty">
              No answer sheet has been uploaded yet.
            </p>
          ) : (
            <ul className="flex flex-col gap-1" data-testid="exam-answer-docs">
              {documents.map((document) => (
                <li
                  key={document.id}
                  className="flex min-w-0 flex-wrap items-center gap-2 rounded-md border border-border px-2 py-1.5"
                  data-testid="exam-answer-doc"
                >
                  <div className="min-w-0 flex-1">
                    <FileLinkButton fileName={document.fileName} onOpen={() => row && onOpenDocument(row, document.id)} />
                  </div>
                  {confirmId === document.id ? (
                    <span className="flex items-center gap-1">
                      <span className="text-xs text-muted-foreground">Delete this file?</span>
                      <Button
                        type="button"
                        size="xs"
                        variant="destructive"
                        onClick={() => void deleteDocument(document.id)}
                        disabled={remove.isPending}
                        aria-busy={remove.isPending}
                        data-testid="exam-answer-doc-delete-confirm"
                      >
                        {remove.isPending ? "Deleting…" : "Delete"}
                      </Button>
                      <Button type="button" size="xs" variant="ghost" onClick={() => setConfirmId(null)} disabled={remove.isPending}>
                        Keep
                      </Button>
                    </span>
                  ) : (
                    <Button
                      type="button"
                      variant="ghost"
                      size="icon-xs"
                      onClick={() => setConfirmId(document.id)}
                      disabled={busy}
                      aria-label={`Delete ${document.fileName}`}
                      data-testid="exam-answer-doc-delete"
                    >
                      <Trash2 className="text-destructive" aria-hidden="true" />
                    </Button>
                  )}
                </li>
              ))}
            </ul>
          )}
        </section>
        <ExamFilePicker
          label="Attach answer sheet files"
          files={files}
          onChange={(next) => {
            setFiles(next);
            setError(null);
          }}
          disabled={uploading}
          testId="exam-answer-sheet-files"
        />
        {progress !== null ? <UploadProgress value={progress.fraction} label={sequentialLabel(progress)} /> : null}
        {error ? (
          <Alert variant="destructive" data-testid="exam-answer-sheet-error">
            <AlertDescription>{error}</AlertDescription>
          </Alert>
        ) : null}
      </div>
    </AdminFormDialog>
  );
}

/** "Upload marked paper" (WRITTEN): one file; replaces any earlier marked paper server-side. */
export function MarkedPaperDialog({
  examId,
  row,
  open,
  onOpenChange,
  onToast,
}: {
  examId: string;
  row: ManagedStudentRow | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onToast: Toast;
}) {
  const upload = useUploadMarkedPaper(examId);
  const [files, setFiles] = useState<File[]>([]);
  const [progress, setProgress] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function submit() {
    if (!row) return;
    if (files.length === 0) {
      setError("Attach the marked paper.");
      return;
    }
    setError(null);
    setProgress(0);
    try {
      await upload.mutateAsync({ studentId: row.studentId, file: files[0], onProgress: setProgress });
      onToast({ kind: "success", text: `Marked paper uploaded for ${candidateName(row)}.` });
      onOpenChange(false);
    } catch (failure) {
      setError(mutationErrorMessage(failure));
    } finally {
      setProgress(null);
    }
  }

  return (
    <AdminFormDialog
      open={open}
      onOpenChange={onOpenChange}
      title="Upload marked paper"
      description={
        row
          ? `${studentLabel(row)}${row.markedPaper ? ` · replaces ${row.markedPaper.fileName}` : ""}. Students see it once results are published.`
          : undefined
      }
      testId="exam-marked-paper-dialog"
      className="sm:max-w-lg"
      footer={
        <>
          <Button type="button" variant="outline" onClick={() => onOpenChange(false)} disabled={upload.isPending}>
            Cancel
          </Button>
          <Button type="button" onClick={() => void submit()} disabled={upload.isPending || !row} aria-busy={upload.isPending} data-testid="exam-marked-paper-upload">
            <Upload aria-hidden="true" />
            {upload.isPending ? "Uploading…" : "Upload"}
          </Button>
        </>
      }
    >
      <div className="flex min-w-0 flex-col gap-4">
        <ExamFilePicker
          label="Marked paper file"
          files={files}
          multiple={false}
          max={1}
          onChange={(next) => {
            setFiles(next);
            setError(null);
          }}
          helper="One PDF, PNG or JPEG file, up to 20 MB."
          disabled={upload.isPending}
          testId="exam-marked-paper-files"
        />
        {progress !== null ? <UploadProgress value={progress} /> : null}
        {error ? (
          <Alert variant="destructive" data-testid="exam-marked-paper-error">
            <AlertDescription>{error}</AlertDescription>
          </Alert>
        ) : null}
      </div>
    </AdminFormDialog>
  );
}

const ATTEMPT_STATUS: Record<string, string> = {
  IN_PROGRESS: "In progress",
  SUBMITTED: "Submitted",
  EXPIRED: "Auto-submitted at the deadline",
};

/** MCQ: read-only attempt summary from the management row (score, answered count, status). */
export function AttemptSummaryDialog({
  exam,
  row,
  open,
  onOpenChange,
}: {
  exam: ExamDetail;
  row: ManagedStudentRow | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const questionCount = exam.paperQuestionCount ?? exam.questionCount;
  return (
    <AdminFormDialog
      open={open}
      onOpenChange={onOpenChange}
      title="Attempt summary"
      description={row ? studentLabel(row) : undefined}
      testId="exam-attempt-summary-dialog"
      className="sm:max-w-md"
      footer={
        <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
          Close
        </Button>
      }
    >
      {row ? (
        <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-3 text-sm" data-testid="exam-attempt-summary">
          <dt className="text-muted-foreground">Result</dt>
          <dd>
            <ResultLabelBadge result={row.result} />
          </dd>
          <dt className="text-muted-foreground">Score</dt>
          <dd className="font-semibold" data-testid="exam-attempt-score">
            {marksText(row.marks)}
            {row.marks !== null && exam.totalMarks !== null ? ` / ${marksText(exam.totalMarks)}` : ""}
          </dd>
          <dt className="text-muted-foreground">Answered</dt>
          <dd data-testid="exam-attempt-answered">
            {row.answeredCount ?? 0}
            {questionCount ? ` of ${questionCount}` : ""} questions
          </dd>
          <dt className="text-muted-foreground">Status</dt>
          <dd data-testid="exam-attempt-status">{row.attemptStatus ? ATTEMPT_STATUS[row.attemptStatus] ?? row.attemptStatus : "Not started"}</dd>
        </dl>
      ) : null}
    </AdminFormDialog>
  );
}

/**
 * PHYSICAL: Present / Absent segmented toggle (`aria-pressed`). Saves through
 * `PUT /results/{studentId}` with the current marks unchanged.
 */
const ABSENT_BLOCKED = "A student with marks can't be marked absent.";

export function AttendanceToggle({
  examId,
  row,
  disabled,
  onToast,
}: {
  examId: string;
  row: ManagedStudentRow;
  disabled?: boolean;
  onToast: Toast;
}) {
  const save = useSaveExamResult(examId);
  const [pendingValue, setPendingValue] = useState<Attendance | null>(null);
  const current = pendingValue ?? row.attendance;
  const name = candidateName(row);
  const hasMarks = !(row.marks === null || row.marks === undefined || row.marks === "");
  const absentBlocked = hasMarks && row.attendance !== "ABSENT";

  async function set(value: Attendance) {
    if (value === row.attendance || save.isPending) return;
    setPendingValue(value);
    try {
      await save.mutateAsync({
        studentId: row.studentId,
        body: {
          // Marks unchanged; the server rejects ABSENT together with marks (400).
          marks: hasMarks ? marksNumber(row.marks!) : null,
          attendance: value,
          reason: null,
        },
      });
      onToast({ kind: "success", text: `${name} marked ${value === "PRESENT" ? "present" : "absent"}.` });
    } catch (error) {
      onToast({ kind: "error", text: mutationErrorMessage(error) });
    } finally {
      setPendingValue(null);
    }
  }

  const option = (value: Attendance) => {
    const active = current === value;
    const Icon = value === "PRESENT" ? UserCheck : UserX;
    return (
      <button
        type="button"
        aria-pressed={active}
        disabled={disabled || save.isPending || (value === "ABSENT" && absentBlocked)}
        title={value === "ABSENT" && absentBlocked ? ABSENT_BLOCKED : undefined}
        aria-label={value === "ABSENT" && absentBlocked ? `Absent: ${ABSENT_BLOCKED}` : undefined}
        onClick={() => void set(value)}
        data-testid={`attendance-${value.toLowerCase()}`}
        className={cn(
          "inline-flex h-7 items-center gap-1 px-2.5 text-xs font-medium transition-colors focus-visible:z-10 focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none disabled:opacity-60",
          active
            ? value === "PRESENT"
              ? "bg-green-600 text-white dark:bg-green-500 dark:text-black"
              : "bg-foreground text-background"
            : "bg-background text-foreground hover:bg-muted"
        )}
      >
        <Icon className="size-3.5" aria-hidden="true" />
        {value === "PRESENT" ? "Present" : "Absent"}
      </button>
    );
  };

  return (
    <div
      role="group"
      aria-label={`Attendance for ${name}`}
      aria-busy={save.isPending}
      className="inline-flex overflow-hidden rounded-lg border border-border"
      data-testid="attendance-toggle"
    >
      {option("PRESENT")}
      {option("ABSENT")}
    </div>
  );
}
