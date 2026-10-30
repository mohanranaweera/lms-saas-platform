"use client";

import Link from "next/link";
import { useMemo, useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useQueryClient } from "@tanstack/react-query";
import {
  AlertTriangle,
  CheckCircle2,
  Clock,
  FileQuestion,
  FileText,
  Info,
  KeyRound,
  Send,
  Sigma,
  Timer,
  Upload,
  UserCheck,
  UserX,
  XCircle,
} from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import {
  AlertDialog,
  AlertDialogClose,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Button, buttonVariants } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { LiveRegion } from "@/components/ui/live-region";
import { Skeleton } from "@/components/ui/skeleton";
import { EmptyState } from "@/components/states/empty-state";
import { ErrorState } from "@/components/states/error-state";
import { LoadingState } from "@/components/states/loading-state";
import { FormField, describedBy, fieldIds } from "@/components/courses/admin/form-field";
import { mutationErrorMessage } from "@/components/courses/admin/confirm-delete-dialog";
import { ExamFilePicker, UploadProgress, marksText, sequentialLabel } from "@/components/exams/management/management-shared";
import { studentExamPaths } from "@/components/exams/student/student-exam-shared";
import { formatDuration, formatExamDateTime } from "@/lib/exams/exam-time";
import { formatCountdown, useServerCountdown } from "@/lib/exams/server-clock";
import { isApiClientError } from "@/lib/api/error";
import {
  startAttemptErrorMessage,
  studentExamKeys,
  useAttempt,
  useStartAttempt,
  useSubmitAttempt,
  type StudentExam,
} from "@/lib/api/student-exams";
import {
  isResultNotPublishedError,
  useMyAnswerDocuments,
  useOpenStudentPaper,
  useStudentMarksResult,
  uploadSequentially,
  useUploadAnswerDocument,
  type SequentialUploadProgress,
} from "@/lib/api/exam-management";
import { cn } from "@/lib/utils";

/** Upload/paper failures that mean the student's time is over (server-decided). */
/** 409 `NOT_STARTED`: no open written attempt (open the paper with the password first). */
function isNotStartedError(error: unknown): boolean {
  return isApiClientError(error) && error.status === 409 && error.code === "NOT_STARTED";
}

function isDeadlineError(error: unknown): boolean {
  return (
    isApiClientError(error) &&
    error.status === 409 &&
    ["DEADLINE_PASSED", "FINALIZED", "CLOSED"].includes(error.code)
  );
}

function WrittenRules({ exam }: { exam: StudentExam }) {
  const duration = formatDuration(exam.durationMinutes);
  return (
    <section aria-labelledby="written-rules-title" className="flex flex-col gap-3 rounded-xl bg-card p-4 ring-1 ring-foreground/10 sm:p-5">
      <h2 id="written-rules-title" className="flex items-center gap-2 text-base font-semibold text-foreground">
        <Info className="size-4 text-primary" aria-hidden="true" />
        How this written exam works
      </h2>
      <ul className="flex list-disc flex-col gap-1.5 pl-5 text-sm text-foreground" data-testid="written-rules">
        <li>
          Your time starts when you open the question paper.{" "}
          {duration ? (
            <>
              You then have <strong>{duration}</strong> to upload your answer sheet, but never past the exam&apos;s closing
              time ({formatExamDateTime(exam.endAt)}).
            </>
          ) : (
            <>You can upload until the exam closes at {formatExamDateTime(exam.endAt)}.</>
          )}
        </li>
        <li>Write your answers on paper, then photograph or scan every page.</li>
        <li>Upload PDF, PNG or JPEG files, up to 20 MB each. You can upload several files.</li>
        <li>Uploads are not accepted after your deadline.</li>
        <li>Your marks appear here after your teacher publishes them.</li>
      </ul>
    </section>
  );
}

type PasswordValues = { password: string };

/** Password + "Open question paper": verifies the password via the attempt start, then fetches the paper (starts the clock). */
function OpenPaperForm({
  exam,
  onBusy,
}: {
  exam: StudentExam;
  /** Keeps this form mounted while start → paper runs (the start response already adds the attempt to the cache). */
  onBusy: (busy: boolean) => void;
}) {
  const start = useStartAttempt(exam.id);
  const openPaper = useOpenStudentPaper(exam.id);
  const schema = useMemo(
    () =>
      z.object({
        password: exam.requiresPassword
          ? z.string().trim().min(1, "Enter the exam password your teacher gave you.").max(64, "The password is at most 64 characters.")
          : z.string(),
      }),
    [exam.requiresPassword]
  );
  const form = useForm<PasswordValues>({ resolver: zodResolver(schema), defaultValues: { password: "" } });
  const [stateError, setStateError] = useState<string | null>(null);
  const [announcement, setAnnouncement] = useState("");
  const ids = fieldIds("written-open", "password");
  const passwordError = form.formState.errors.password?.message ?? null;
  const pending = start.isPending || openPaper.isPending;

  const onSubmit = form.handleSubmit(async (values) => {
    setStateError(null);
    setAnnouncement("Opening the question paper…");
    onBusy(true);
    try {
      await start.mutateAsync(exam.requiresPassword ? values.password.trim() : null);
    } catch (error) {
      setAnnouncement("");
      const mapped = startAttemptErrorMessage(error);
      if (mapped.kind === "password") {
        form.setError("password", { type: "server", message: mapped.message }, { shouldFocus: true });
      } else {
        setStateError(mapped.message);
      }
      onBusy(false);
      return;
    }
    try {
      const paper = await openPaper.mutateAsync();
      setAnnouncement("Question paper opened. Your time has started.");
      window.open(paper.url, "_blank", "noopener,noreferrer");
    } catch (error) {
      setAnnouncement("");
      setStateError(mutationErrorMessage(error));
    } finally {
      onBusy(false);
    }
  });

  return (
    <form
      onSubmit={onSubmit}
      noValidate
      aria-labelledby="written-open-title"
      className="flex flex-col gap-4 rounded-xl bg-card p-4 ring-1 ring-foreground/10 sm:p-5"
      data-testid="written-open-form"
    >
      <LiveRegion message={announcement} />
      <h2 id="written-open-title" className="flex items-center gap-2 text-base font-semibold text-foreground">
        <FileQuestion className="size-4 text-primary" aria-hidden="true" />
        Open the question paper
      </h2>
      {exam.requiresPassword ? (
        <FormField
          ids={ids}
          label="Exam password"
          required
          helper="Your teacher gives you this password when the exam begins."
          error={passwordError}
          className="max-w-sm"
        >
          <div className="relative">
            <KeyRound className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
            <Input
              id={ids.inputId}
              type="password"
              autoComplete="off"
              className="pl-8"
              aria-invalid={passwordError ? true : undefined}
              aria-describedby={describedBy(ids, true, Boolean(passwordError))}
              {...form.register("password")}
            />
          </div>
        </FormField>
      ) : null}
      {stateError ? (
        <Alert variant="destructive" data-testid="written-open-error">
          <AlertDescription>{stateError}</AlertDescription>
        </Alert>
      ) : null}
      <div>
        <Button type="submit" size="lg" disabled={pending} aria-busy={pending} data-testid="written-open-paper">
          <FileQuestion aria-hidden="true" />
          {pending ? "Opening…" : "Open question paper"}
        </Button>
      </div>
    </form>
  );
}

function MyUploads({ examId }: { examId: string }) {
  const query = useMyAnswerDocuments(examId);
  return (
    <section aria-labelledby="my-uploads-title" className="flex min-w-0 flex-col gap-2" data-testid="written-my-uploads">
      <h3 id="my-uploads-title" className="text-sm font-semibold text-foreground">
        Your uploaded answer sheets
      </h3>
      {query.status === "pending" ? (
        <div role="status" aria-busy="true" className="flex flex-col gap-1">
          <span className="sr-only">Loading your uploads…</span>
          <Skeleton className="h-8 w-full" />
          <Skeleton className="h-8 w-full" />
        </div>
      ) : query.status === "error" ? (
        <ErrorState message="We couldn't load your uploads." onRetry={() => void query.refetch()} />
      ) : query.data.length === 0 ? (
        <p className="rounded-lg border border-dashed border-border px-3 py-4 text-center text-sm text-muted-foreground" data-testid="written-my-uploads-empty">
          You haven&apos;t uploaded an answer sheet yet.
        </p>
      ) : (
        <ul className="flex flex-col gap-1" data-testid="written-my-uploads-list">
          {query.data.map((document) => (
            <li key={document.id} className="flex min-w-0 items-center gap-2 rounded-md border border-border px-3 py-2 text-sm" data-testid="written-my-upload">
              <FileText className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
              <span className="min-w-0 flex-1 truncate">{document.fileName}</span>
              {document.uploadedAt ? (
                <time dateTime={document.uploadedAt} className="shrink-0 text-xs text-muted-foreground">
                  {formatExamDateTime(document.uploadedAt)}
                </time>
              ) : null}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function UploadAnswers({ examId, onClosed }: { examId: string; onClosed: (code: "deadline" | "not-started") => void }) {
  const upload = useUploadAnswerDocument(examId);
  const [files, setFiles] = useState<File[]>([]);
  const [progress, setProgress] = useState<SequentialUploadProgress | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState("");
  const uploading = progress !== null;

  async function submit() {
    if (files.length === 0) {
      setError("Attach at least one file of your answer sheet.");
      return;
    }
    setError(null);
    setSuccess("");
    // One request per file (server request ceiling); stop as soon as the server says time is up.
    const result = await uploadSequentially(
      files,
      (file, onProgress) => upload.mutateAsync({ file, onProgress }),
      setProgress,
      (failure) => isDeadlineError(failure) || isNotStartedError(failure)
    );
    setProgress(null);
    if (result.stoppedBy) {
      onClosed(isNotStartedError(result.stoppedBy) ? "not-started" : "deadline");
      return;
    }
    setFiles(result.failed.map((entry) => entry.file));
    if (result.uploaded.length > 0) {
      setSuccess(`${result.uploaded.length} ${result.uploaded.length === 1 ? "file" : "files"} uploaded.`);
    }
    if (result.failed.length > 0) {
      setError(result.failed.map((entry) => `${entry.file.name}: ${mutationErrorMessage(entry.error)}`).join(" "));
    }
  }

  return (
    <div className="flex min-w-0 flex-col gap-3" data-testid="written-upload">
      <ExamFilePicker
        label="Answer sheet files"
        files={files}
        onChange={(next) => {
          setFiles(next);
          setError(null);
        }}
        disabled={uploading}
        testId="written-files"
      />
      {progress !== null ? <UploadProgress value={progress.fraction} label={sequentialLabel(progress)} /> : null}
      {error ? (
        <Alert variant="destructive" data-testid="written-upload-error">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      ) : null}
      <p role="status" className="text-sm text-green-700 dark:text-green-400" data-testid="written-upload-success">
        {success}
      </p>
      <div>
        <Button type="button" onClick={() => void submit()} disabled={uploading} aria-busy={uploading} data-testid="written-upload-submit">
          <Upload aria-hidden="true" />
          {uploading ? "Uploading…" : "Upload answer sheet"}
        </Button>
      </div>
    </div>
  );
}

type ClosedReason = "deadline" | "submitted";

function AttemptClosed({ exam, reason }: { exam: StudentExam; reason: ClosedReason }) {
  return (
    <div className="flex flex-col gap-4 rounded-xl bg-card p-4 ring-1 ring-foreground/10 sm:p-5" data-testid="written-deadline-passed" data-reason={reason}>
      <Alert role="status">
        {reason === "submitted" ? <CheckCircle2 aria-hidden="true" /> : <Clock aria-hidden="true" />}
        <AlertDescription className="text-foreground">
          {reason === "submitted"
            ? "You submitted this exam. Uploads are closed; your teacher marks the answer sheets below."
            : "The deadline for this exam has passed. Uploads are closed; the answer sheets you uploaded before the deadline are what your teacher marks."}
        </AlertDescription>
      </Alert>
      <MyUploads examId={exam.id} />
      <div>
        <Link href={studentExamPaths.result(exam.id)} className={buttonVariants({ variant: "outline" })} data-testid="written-result-link">
          {exam.resultAvailable ? "View result" : "Result status"}
        </Link>
      </div>
    </div>
  );
}

/** "Finish and submit": ends the written attempt early (`POST /student/attempts/{id}/submit`, idempotent). */
function FinishButton({ attemptId, examId, onSubmitted }: { attemptId: string; examId: string; onSubmitted: () => void }) {
  const submit = useSubmitAttempt(attemptId, examId);
  const [open, setOpen] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function run() {
    setError(null);
    try {
      await submit.mutateAsync();
      setOpen(false);
      onSubmitted();
    } catch (failure) {
      if (isDeadlineError(failure)) {
        setOpen(false);
        onSubmitted();
        return;
      }
      setError(mutationErrorMessage(failure));
    }
  }

  return (
    <>
      <Button type="button" variant="outline" onClick={() => setOpen(true)} data-testid="written-finish-open">
        <Send aria-hidden="true" />
        Finish and submit
      </Button>
      <AlertDialog
        open={open}
        onOpenChange={(next) => {
          setOpen(next);
          if (!next) setError(null);
        }}
      >
        <AlertDialogContent data-testid="written-finish-dialog">
          <AlertDialogHeader>
            <AlertDialogTitle>Finish this exam?</AlertDialogTitle>
            <AlertDialogDescription>
              After you submit, you can&apos;t upload more answer sheets. Make sure every page is uploaded.
            </AlertDialogDescription>
          </AlertDialogHeader>
          {error ? (
            <Alert variant="destructive">
              <AlertDescription>{error}</AlertDescription>
            </Alert>
          ) : null}
          <AlertDialogFooter>
            <AlertDialogClose render={<Button type="button" variant="outline" />}>Keep working</AlertDialogClose>
            <Button type="button" onClick={() => void run()} disabled={submit.isPending} aria-busy={submit.isPending} data-testid="written-finish-confirm">
              {submit.isPending ? "Submitting…" : "Submit"}
            </Button>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  );
}

type CloseCode = "deadline" | "not-started" | "submitted";

/** The open attempt: countdown on the server clock, reopen paper, upload, own uploads, finish. */
function WrittenInProgress({
  exam,
  attemptId,
  deadlineAt,
  anchor,
  onClosed,
}: {
  exam: StudentExam;
  attemptId: string;
  deadlineAt: string;
  anchor: { serverNow: string | null; receivedAt: number };
  onClosed: (code: CloseCode) => void;
}) {
  const { remainingMs } = useServerCountdown(deadlineAt, anchor.serverNow, anchor.receivedAt);
  const openPaper = useOpenStudentPaper(exam.id);
  const [paperError, setPaperError] = useState<string | null>(null);
  const expired = remainingMs !== null && remainingMs <= 0;
  const low = remainingMs !== null && remainingMs <= 5 * 60_000;

  if (expired) return <AttemptClosed exam={exam} reason="deadline" />;

  async function reopen() {
    setPaperError(null);
    try {
      const paper = await openPaper.mutateAsync();
      window.open(paper.url, "_blank", "noopener,noreferrer");
    } catch (error) {
      if (isDeadlineError(error)) onClosed("deadline");
      else if (isNotStartedError(error)) onClosed("not-started");
      else setPaperError(mutationErrorMessage(error));
    }
  }

  return (
    <div className="flex min-w-0 flex-col gap-4 rounded-xl bg-card p-4 ring-1 ring-foreground/10 sm:p-5" data-testid="written-in-progress">
      <div
        className={cn(
          "flex flex-wrap items-center justify-between gap-3 rounded-lg px-3 py-2",
          low ? "bg-destructive/10 text-destructive" : "bg-primary/5 text-foreground"
        )}
      >
        <p className="flex items-center gap-2 text-sm">
          <Timer className="size-4" aria-hidden="true" />
          Time left to upload
        </p>
        <p className="text-2xl font-semibold tabular-nums" data-testid="written-countdown" role="timer" aria-live="off">
          {remainingMs === null ? "—" : formatCountdown(remainingMs)}
        </p>
        <p className="w-full text-xs text-muted-foreground">
          Deadline <time dateTime={deadlineAt}>{formatExamDateTime(deadlineAt)}</time>
        </p>
      </div>
      <div>
        <Button type="button" variant="outline" onClick={() => void reopen()} disabled={openPaper.isPending} aria-busy={openPaper.isPending} data-testid="written-reopen-paper">
          <FileQuestion aria-hidden="true" />
          {openPaper.isPending ? "Opening…" : "Open question paper"}
        </Button>
      </div>
      {paperError ? (
        <Alert variant="destructive">
          <AlertDescription>{paperError}</AlertDescription>
        </Alert>
      ) : null}
      <UploadAnswers examId={exam.id} onClosed={onClosed} />
      <MyUploads examId={exam.id} />
      <div className="border-t border-border pt-4">
        <FinishButton attemptId={attemptId} examId={exam.id} onSubmitted={() => onClosed("submitted")} />
      </div>
    </div>
  );
}

/**
 * The open attempt (after the password start, or on reload):
 * `GET /student/attempts/{id}` supplies the deadline, status and `serverNow`
 * that anchor the countdown to the server clock.
 */
function ResumedAttempt({
  exam,
  attemptId,
  fallbackDeadline,
  onClosed,
}: {
  exam: StudentExam;
  attemptId: string;
  fallbackDeadline: string | null;
  onClosed: (code: CloseCode) => void;
}) {
  const query = useAttempt(attemptId);
  if (query.status === "pending") return <LoadingState label="Loading your exam…" />;
  if (query.status === "success") {
    if (query.data.status !== "IN_PROGRESS") {
      return <AttemptClosed exam={exam} reason={query.data.status === "SUBMITTED" ? "submitted" : "deadline"} />;
    }
    return (
      <WrittenInProgress
        exam={exam}
        attemptId={attemptId}
        deadlineAt={query.data.deadlineAt}
        anchor={{ serverNow: query.data.serverNow, receivedAt: query.dataUpdatedAt }}
        onClosed={onClosed}
      />
    );
  }
  // Attempt read failed: fall back to the exam's deadline on the device clock (display only).
  return fallbackDeadline ? (
    <WrittenInProgress exam={exam} attemptId={attemptId} deadlineAt={fallbackDeadline} anchor={{ serverNow: null, receivedAt: 0 }} onClosed={onClosed} />
  ) : (
    <ErrorState message="We couldn't load your exam." onRetry={() => void query.refetch()} />
  );
}

/**
 * Student WRITTEN exam (Slice D): details → password (`POST /attempts`, opens
 * the written attempt and its deadline) → "Open question paper" → upload
 * answer sheets one file at a time until the deadline or "Finish and submit".
 * Every rule (window, password, deadline, file checks) is enforced by the
 * server; the countdown is display-only on the server clock.
 */
export function StudentWrittenExam({ exam }: { exam: StudentExam }) {
  const queryClient = useQueryClient();
  const [closed, setClosed] = useState<ClosedReason | null>(null);
  const [opening, setOpening] = useState(false);
  const attempt = exam.attempt;

  function onClosed(code: CloseCode) {
    if (code !== "not-started") setClosed(code);
    void queryClient.invalidateQueries({ queryKey: studentExamKeys.detail(exam.id) });
    void queryClient.invalidateQueries({ queryKey: studentExamKeys.list() });
  }

  if (exam.status === "UPCOMING" || exam.status === "DRAFT") {
    return (
      <EmptyState
        title="This exam hasn't opened yet"
        description={`You can open the question paper from ${formatExamDateTime(exam.startAt)}. Come back then with the exam password.`}
      />
    );
  }
  if (closed) return <AttemptClosed exam={exam} reason={closed} />;
  if (attempt && attempt.status !== "IN_PROGRESS") {
    return <AttemptClosed exam={exam} reason={attempt.status === "SUBMITTED" ? "submitted" : "deadline"} />;
  }
  if (exam.status === "FINISHED") {
    return attempt ? (
      <AttemptClosed exam={exam} reason="deadline" />
    ) : (
      <EmptyState
        title="This exam has closed"
        description="The exam window ended before you opened the question paper, so answers can no longer be uploaded."
      />
    );
  }
  // Keep the form mounted while start → paper runs (the start response already adds the attempt to the cache).
  if (opening || !attempt) return <OpeningForm exam={exam} onBusy={setOpening} />;
  return <ResumedAttempt exam={exam} attemptId={attempt.id} fallbackDeadline={attempt.deadlineAt} onClosed={onClosed} />;
}

function OpeningForm(props: { exam: StudentExam; onBusy: (busy: boolean) => void }) {
  return (
    <div className="flex flex-col gap-6">
      <WrittenRules exam={props.exam} />
      <OpenPaperForm {...props} />
    </div>
  );
}

/** Physical exam (student): schedule is in the summary; results once published. */
export function StudentPhysicalExam({ exam }: { exam: StudentExam }) {
  return (
    <div className="flex flex-col gap-3" data-testid="physical-exam-info">
      <EmptyState
        title="This exam is held in class"
        description={`Attend the exam at the scheduled time (${formatExamDateTime(exam.startAt)}). Your teacher records your attendance and marks.`}
      />
      {exam.resultAvailable ? (
        <div>
          <Link href={studentExamPaths.result(exam.id)} className={buttonVariants({ variant: "outline" })} data-testid="physical-result-link">
            View result
          </Link>
        </div>
      ) : null}
    </div>
  );
}

const NOT_PUBLISHED = {
  title: "Marks will be available after they are published",
  description: "Your teacher publishes the marks for this exam when marking is complete. You'll be notified when they're available.",
};

/** Published written/physical result: marks, pass/fail (when a cut-off is set), attendance, marked paper. */
export function StudentMarksResult({ exam }: { exam: StudentExam }) {
  const query = useStudentMarksResult(exam.id, { enabled: exam.resultAvailable });
  const [openingPaper, setOpeningPaper] = useState(false);
  const [paperError, setPaperError] = useState<string | null>(null);

  if (!exam.resultAvailable) {
    return (
      <div data-testid="result-not-published">
        <EmptyState title={NOT_PUBLISHED.title} description={NOT_PUBLISHED.description} icon={<Clock className="size-8" aria-hidden="true" />} />
      </div>
    );
  }
  if (query.status === "pending") return <LoadingState label="Loading your result…" />;
  if (query.status === "error") {
    if (isResultNotPublishedError(query.error)) {
      return (
        <div data-testid="result-not-published">
          <EmptyState title={NOT_PUBLISHED.title} description={NOT_PUBLISHED.description} icon={<Clock className="size-8" aria-hidden="true" />} />
        </div>
      );
    }
    return <ErrorState message="We couldn't load your result." onRetry={() => void query.refetch()} />;
  }

  const result = query.data;
  const absent = exam.type === "PHYSICAL" && result.attendance === "ABSENT";
  const hasOutcome = result.passed !== null && result.passed !== undefined && result.cutoffMarks !== null;

  /** The signed link expires after 10 minutes: re-read the result for a fresh one, then open it. */
  async function openMarkedPaper() {
    setPaperError(null);
    setOpeningPaper(true);
    try {
      const fresh = await query.refetch();
      const link = fresh.data?.markedPaper;
      if (link?.url) window.open(link.url, "_blank", "noopener,noreferrer");
      else setPaperError(fresh.error ? mutationErrorMessage(fresh.error) : "The marked paper is no longer available.");
    } finally {
      setOpeningPaper(false);
    }
  }

  return (
    <section aria-labelledby="marks-result-title" className="flex flex-col gap-4 rounded-xl bg-card p-4 ring-1 ring-foreground/10 sm:p-5" data-testid="marks-result">
      <h2 id="marks-result-title" className="text-base font-semibold text-foreground">
        Your result
      </h2>
      <div className="flex flex-wrap items-end gap-x-6 gap-y-3">
        <p className="flex items-baseline gap-1 text-foreground">
          <span className="text-4xl font-bold tabular-nums" data-testid="result-score">
            {marksText(result.score)}
          </span>
          <span className="text-lg text-muted-foreground tabular-nums">
            / <span data-testid="result-total">{marksText(result.totalMarks)}</span>
          </span>
          <span className="sr-only">marks</span>
        </p>
        {hasOutcome ? (
          <span
            data-testid="result-outcome"
            className={cn(
              "inline-flex h-8 items-center gap-1.5 rounded-full px-3 text-sm font-semibold",
              result.passed ? "bg-green-600/10 text-green-700 dark:text-green-400" : "bg-destructive/10 text-destructive"
            )}
          >
            {result.passed ? <CheckCircle2 className="size-4" aria-hidden="true" /> : <XCircle className="size-4" aria-hidden="true" />}
            {result.passed ? "Passed" : "Not passed"}
          </span>
        ) : null}
      </div>
      <dl className="grid gap-2 text-sm sm:grid-cols-2">
        {hasOutcome ? (
          <div className="flex items-center gap-1.5">
            <Sigma className="size-4 text-muted-foreground" aria-hidden="true" />
            <dt className="text-muted-foreground">Cut-off</dt>
            <dd>{marksText(result.cutoffMarks)} marks</dd>
          </div>
        ) : null}
        {exam.type === "PHYSICAL" && result.attendance ? (
          <div className="flex items-center gap-1.5">
            {absent ? <UserX className="size-4 text-muted-foreground" aria-hidden="true" /> : <UserCheck className="size-4 text-muted-foreground" aria-hidden="true" />}
            <dt className="text-muted-foreground">Attendance</dt>
            <dd data-testid="result-attendance">{absent ? "Absent" : "Present"}</dd>
          </div>
        ) : null}
      </dl>
      {exam.type === "WRITTEN" ? (
        result.markedPaper ? (
          <div>
            <Button type="button" variant="outline" onClick={() => void openMarkedPaper()} disabled={openingPaper} aria-busy={openingPaper} data-testid="result-marked-paper">
              <FileText aria-hidden="true" />
              {openingPaper ? "Opening…" : `View marked paper (${result.markedPaper.fileName})`}
            </Button>
          </div>
        ) : (
          <p className="text-sm text-muted-foreground" data-testid="result-no-marked-paper">
            Your teacher hasn&apos;t shared a marked paper for this exam.
          </p>
        )
      ) : null}
      {paperError ? (
        <Alert variant="destructive">
          <AlertTriangle aria-hidden="true" />
          <AlertDescription>{paperError}</AlertDescription>
        </Alert>
      ) : null}
    </section>
  );
}
