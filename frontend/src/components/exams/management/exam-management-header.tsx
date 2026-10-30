"use client";

import Link from "next/link";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import {
  CalendarDays,
  ChartColumn,
  Eye,
  EyeOff,
  FileQuestion,
  FileText,
  ListChecks,
  Lock,
  PenTool,
  Pencil,
  School,
  Sigma,
  Timer,
} from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  AlertDialog,
  AlertDialogClose,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { AdminFormDialog } from "@/components/courses/admin/admin-dialog";
import { FormField, describedBy, fieldIds } from "@/components/courses/admin/form-field";
import { mutationErrorMessage } from "@/components/courses/admin/confirm-delete-dialog";
import { ExamLiveStatusChip, examPaths, formatExamMarks, type ExamPortal } from "@/components/exams/config/exam-shared";
import { FileLinkButton } from "@/components/exams/management/management-shared";
import { formatExamDateTime } from "@/lib/exams/exam-time";
import { marksNumber } from "@/lib/api/question-bank";
import { isApiClientError } from "@/lib/api/error";
import { useExamDocumentLink, useRevealExamPassword, type ExamDetail, type ExamDocumentPurpose } from "@/lib/api/exam-config";
import { useSetExamTotalMarks } from "@/lib/api/exam-management";

const MASK = "••••••••";

/**
 * D6: the password is never shown by default. "Reveal" asks for confirmation,
 * then `POST /exams/{id}/password/reveal` (audit-logged server-side) and shows
 * the value in place. The value lives only in this component's state — never
 * in storage, the query cache or the URL — and is dropped on Hide/unmount.
 */
function PasswordRow({ exam, canReveal }: { exam: ExamDetail; canReveal: boolean }) {
  const reveal = useRevealExamPassword(exam.id);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [value, setValue] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function run() {
    setError(null);
    try {
      const result = await reveal.mutateAsync();
      setValue(result.password);
      setConfirmOpen(false);
    } catch (failure) {
      setError(mutationErrorMessage(failure));
    } finally {
      reveal.reset();
    }
  }

  return (
    <div className="flex min-w-0 flex-wrap items-center gap-2">
      <Lock className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
      <dt className="sr-only">Exam password</dt>
      <dd className="font-semibold tracking-wider" data-testid="exam-password-value" aria-label={value ? undefined : "Password hidden"}>
        {value ?? MASK}
      </dd>
      {canReveal ? (
        value ? (
          <Button type="button" variant="ghost" size="xs" onClick={() => setValue(null)} data-testid="exam-password-hide">
            <EyeOff aria-hidden="true" />
            Hide
          </Button>
        ) : (
          <Button type="button" variant="ghost" size="xs" onClick={() => setConfirmOpen(true)} data-testid="exam-password-reveal">
            <Eye aria-hidden="true" />
            Reveal
          </Button>
        )
      ) : null}
      <AlertDialog open={confirmOpen} onOpenChange={(next) => { setConfirmOpen(next); if (!next) setError(null); }}>
        <AlertDialogContent data-testid="exam-password-reveal-dialog">
          <AlertDialogHeader>
            <AlertDialogTitle>Reveal the exam password?</AlertDialogTitle>
            <AlertDialogDescription>
              Revealing the password is recorded in the audit log with your name and the time. Only share it with
              students when the exam starts.
            </AlertDialogDescription>
          </AlertDialogHeader>
          {error ? (
            <Alert variant="destructive" data-testid="exam-password-reveal-error">
              <AlertDescription>{error}</AlertDescription>
            </Alert>
          ) : null}
          <AlertDialogFooter>
            <AlertDialogClose render={<Button type="button" variant="outline" />}>Cancel</AlertDialogClose>
            <Button type="button" onClick={() => void run()} disabled={reveal.isPending} aria-busy={reveal.isPending} data-testid="exam-password-reveal-confirm">
              <Eye aria-hidden="true" />
              {reveal.isPending ? "Revealing…" : "Reveal password"}
            </Button>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}

function DocumentLinks({ exam, onError }: { exam: ExamDetail; onError: (message: string) => void }) {
  const link = useExamDocumentLink();
  const [pending, setPending] = useState<ExamDocumentPurpose | null>(null);

  async function open(purpose: ExamDocumentPurpose) {
    setPending(purpose);
    try {
      const result = await link.mutateAsync({ examId: exam.id, purpose });
      window.open(result.url, "_blank", "noopener,noreferrer");
    } catch (error) {
      onError(mutationErrorMessage(error));
    } finally {
      setPending(null);
    }
  }

  if (!exam.questionPaper && !exam.markingScheme) return null;
  return (
    <>
      {exam.questionPaper ? (
        <div className="flex min-w-0 items-center gap-2">
          <FileQuestion className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
          <dt className="shrink-0 text-muted-foreground">Question paper</dt>
          <dd className="min-w-0">
            <FileLinkButton
              fileName={exam.questionPaper.fileName}
              label={`Download question paper ${exam.questionPaper.fileName}`}
              pending={pending === "QUESTION_PAPER"}
              onOpen={() => void open("QUESTION_PAPER")}
              testId="exam-question-paper-link"
            />
          </dd>
        </div>
      ) : null}
      {exam.markingScheme ? (
        <div className="flex min-w-0 items-center gap-2">
          <FileText className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
          <dt className="shrink-0 text-muted-foreground">Marking scheme</dt>
          <dd className="min-w-0">
            <FileLinkButton
              fileName={exam.markingScheme.fileName}
              label={`Download marking scheme ${exam.markingScheme.fileName}`}
              pending={pending === "MARKING_SCHEME"}
              onOpen={() => void open("MARKING_SCHEME")}
              testId="exam-marking-scheme-link"
            />
          </dd>
        </div>
      ) : null}
    </>
  );
}

const totalMarksSchema = z.object({
  totalMarks: z.string().superRefine((raw, ctx) => {
    const trimmed = raw.trim();
    const n = Number(trimmed);
    if (!trimmed || !Number.isFinite(n)) ctx.addIssue({ code: "custom", message: "Enter the total marks for this exam." });
    else if (n < 1 || n > 10000) ctx.addIssue({ code: "custom", message: "Total marks must be between 1 and 10000." });
    else if (Math.round(n * 100) !== n * 100) ctx.addIssue({ code: "custom", message: "Use at most 2 decimal places." });
  }),
});

/** D8: physical exams get their total marks here, before marks are entered. Mounted per opening. */
export function TotalMarksDialog({
  exam,
  open,
  onOpenChange,
  onSaved,
}: {
  exam: ExamDetail;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onSaved: (total: number) => void;
}) {
  const save = useSetExamTotalMarks(exam.id);
  const current = exam.totalMarks === null || exam.totalMarks === undefined || exam.totalMarks === "" ? "" : String(marksNumber(exam.totalMarks));
  const form = useForm<{ totalMarks: string }>({ resolver: zodResolver(totalMarksSchema), defaultValues: { totalMarks: current } });
  const [formError, setFormError] = useState<string | null>(null);
  const ids = fieldIds("exam-total-marks", "value");
  const error = form.formState.errors.totalMarks?.message ?? null;

  const onSubmit = form.handleSubmit(async (values) => {
    setFormError(null);
    const n = Number(values.totalMarks.trim());
    try {
      await save.mutateAsync(n);
      onSaved(n);
      onOpenChange(false);
    } catch (failure) {
      const field = isApiClientError(failure) ? failure.fieldErrors.find((entry) => entry.field === "totalMarks") : undefined;
      if (field) form.setError("totalMarks", { type: "server", message: field.message }, { shouldFocus: true });
      else setFormError(mutationErrorMessage(failure));
    }
  });

  return (
    <AdminFormDialog
      open={open}
      onOpenChange={onOpenChange}
      title={current ? "Change total marks" : "Set total marks"}
      description="The maximum marks a student can get in this in-class exam. Enter it before recording marks."
      testId="exam-total-marks-dialog"
      className="sm:max-w-md"
      footer={
        <>
          <Button type="button" variant="outline" onClick={() => onOpenChange(false)} disabled={save.isPending}>
            Cancel
          </Button>
          <Button type="submit" form="exam-total-marks-form" disabled={save.isPending} aria-busy={save.isPending} data-testid="exam-total-marks-save">
            {save.isPending ? "Saving…" : "Save total marks"}
          </Button>
        </>
      }
    >
      <form id="exam-total-marks-form" noValidate onSubmit={onSubmit} className="flex flex-col gap-3">
        <FormField ids={ids} label="Total marks" required helper="Between 1 and 10000." error={error}>
          <Input
            id={ids.inputId}
            type="number"
            inputMode="decimal"
            min={1}
            max={10000}
            step="0.01"
            aria-invalid={error ? true : undefined}
            aria-describedby={describedBy(ids, true, Boolean(error))}
            data-testid="exam-total-marks-input"
            {...form.register("totalMarks")}
          />
        </FormField>
        {formError ? (
          <Alert variant="destructive" data-testid="exam-total-marks-error">
            <AlertDescription>{formError}</AlertDescription>
          </Alert>
        ) : null}
      </form>
    </AdminFormDialog>
  );
}

/** Icon composition standing in for Klass's illustration (no copied artwork). */
function HeaderIllustration({ exam }: { exam: ExamDetail }) {
  const Icon = exam.type === "MCQ" ? ListChecks : exam.type === "WRITTEN" ? PenTool : School;
  return (
    <div aria-hidden="true" className="relative hidden h-40 w-56 shrink-0 lg:block">
      <div className="absolute inset-x-4 top-4 bottom-6 rounded-xl bg-primary/90 shadow-md" />
      <div className="absolute inset-x-0 bottom-3 h-3 rounded-full bg-foreground/80" />
      <div className="absolute top-8 left-9 flex size-14 items-center justify-center rounded-lg bg-background/95 text-primary shadow">
        <Icon className="size-7" />
      </div>
      <div className="absolute top-8 right-9 flex size-10 items-center justify-center rounded-lg bg-background/95 text-primary shadow">
        <FileQuestion className="size-5" />
      </div>
      <div className="absolute right-12 bottom-10 flex size-10 items-center justify-center rounded-lg bg-background/95 text-primary shadow">
        <ChartColumn className="size-5" />
      </div>
    </div>
  );
}

/**
 * Manage page header card (Klass layout): name, status chip, stats link,
 * course, window "(from)"/"(to)", duration, size, masked password, and the
 * written exam's documents.
 */
export function ExamManagementHeader({
  exam,
  portal,
  canAuthor,
  onToast,
}: {
  exam: ExamDetail;
  portal: ExamPortal;
  canAuthor: boolean;
  onToast: (message: { kind: "success" | "error"; text: string }) => void;
}) {
  const paths = examPaths(portal);
  const [totalOpen, setTotalOpen] = useState(false);
  const questionCount = exam.paperQuestionCount ?? exam.questionCount;
  const marks = formatExamMarks(exam.totalMarks);
  const physicalMissingTotal = exam.type === "PHYSICAL" && !marks;

  return (
    <section
      aria-labelledby="exam-header-title"
      className="flex min-w-0 gap-6 rounded-xl bg-card p-4 shadow-sm ring-1 ring-foreground/10 sm:p-5"
      data-testid="exam-header"
    >
      <div className="flex min-w-0 flex-1 flex-col gap-4">
        <div className="flex min-w-0 flex-wrap items-center gap-2">
          <h2 id="exam-header-title" className="min-w-0 text-lg font-semibold break-words text-foreground" data-testid="exam-header-name">
            {exam.name}
          </h2>
          <ExamLiveStatusChip status={exam.status} className="h-7 px-3 uppercase" />
          <Link
            href={paths.stats(exam.id)}
            aria-label={`Statistics for ${exam.name}`}
            title="Statistics"
            className="inline-flex size-8 items-center justify-center rounded-md text-primary hover:bg-primary/10 focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none"
            data-testid="exam-stats-link"
          >
            <ChartColumn className="size-5" aria-hidden="true" />
          </Link>
        </div>
        <p className="-mt-2 text-sm text-muted-foreground" data-testid="exam-header-course">
          {exam.courseName}
        </p>
        <dl className="flex min-w-0 flex-col gap-2 text-sm">
          <div className="flex items-center gap-2">
            <CalendarDays className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
            <dt className="sr-only">From</dt>
            <dd>
              <time dateTime={exam.startAt} className="font-semibold">
                {formatExamDateTime(exam.startAt)}
              </time>{" "}
              <span className="text-xs text-muted-foreground">(from)</span>
            </dd>
          </div>
          <div className="flex items-center gap-2">
            <CalendarDays className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
            <dt className="sr-only">To</dt>
            <dd>
              <time dateTime={exam.endAt} className="font-semibold">
                {formatExamDateTime(exam.endAt)}
              </time>{" "}
              <span className="text-xs text-muted-foreground">(to)</span>
            </dd>
          </div>
          <div className="flex items-center gap-2">
            <Timer className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
            <dt className="sr-only">Duration</dt>
            <dd className="font-semibold">
              {exam.durationMinutes ? `${exam.durationMinutes} ${exam.durationMinutes === 1 ? "Minute" : "Minutes"}` : "No fixed duration"}
            </dd>
          </div>
          <div className="flex min-w-0 flex-wrap items-center gap-2" data-testid="exam-header-size">
            {exam.type === "MCQ" ? (
              <ListChecks className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
            ) : (
              <Sigma className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
            )}
            <dt className="sr-only">{exam.type === "MCQ" ? "Questions" : "Total marks"}</dt>
            <dd className="font-semibold">
              {exam.type === "MCQ"
                ? questionCount
                  ? `${questionCount} ${questionCount === 1 ? "Question" : "Questions"}`
                  : "No questions drawn"
                : (marks ?? <span className="font-normal text-muted-foreground">Total marks not set</span>)}
            </dd>
            {exam.type === "PHYSICAL" && canAuthor ? (
              physicalMissingTotal ? (
                <Button type="button" size="xs" onClick={() => setTotalOpen(true)} data-testid="exam-set-total-marks">
                  <Sigma aria-hidden="true" />
                  Set total marks
                </Button>
              ) : (
                <Button
                  type="button"
                  variant="ghost"
                  size="icon-xs"
                  onClick={() => setTotalOpen(true)}
                  aria-label="Change total marks"
                  data-testid="exam-edit-total-marks"
                >
                  <Pencil aria-hidden="true" />
                </Button>
              )
            ) : null}
          </div>
          {exam.hasPassword ? <PasswordRow exam={exam} canReveal={canAuthor} /> : null}
          {exam.type === "WRITTEN" ? <DocumentLinks exam={exam} onError={(text) => onToast({ kind: "error", text })} /> : null}
        </dl>
        {physicalMissingTotal ? (
          <p className="text-sm text-muted-foreground" data-testid="exam-total-marks-hint">
            Set the total marks before entering students&apos; marks.
          </p>
        ) : null}
      </div>
      <HeaderIllustration exam={exam} />
      {exam.type === "PHYSICAL" && totalOpen ? (
        <TotalMarksDialog
          exam={exam}
          open
          onOpenChange={setTotalOpen}
          onSaved={(total) => onToast({ kind: "success", text: `Total marks set to ${total}.` })}
        />
      ) : null}
    </section>
  );
}
