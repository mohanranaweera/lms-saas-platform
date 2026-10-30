"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { ArrowLeft, CheckCircle2, CircleSlash, Clock, Play, Timer, XCircle } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Breadcrumbs } from "@/components/ui/breadcrumbs";
import { buttonVariants } from "@/components/ui/button";
import { EmptyState } from "@/components/states/empty-state";
import { LoadingState } from "@/components/states/loading-state";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { RichContent } from "@/components/exams/rich-content";
import { formatExamMarks } from "@/components/exams/config/exam-shared";
import { optionLetter } from "@/components/exams/student/attempt-question";
import { StudentExamSummary, studentExamPaths } from "@/components/exams/student/student-exam-shared";
import { formatExamDateTime } from "@/lib/exams/exam-time";
import { marksNumber } from "@/lib/api/question-bank";
import { isApiClientError } from "@/lib/api/error";
import {
  isResultNotReleasedError,
  useAttemptResult,
  useStudentExam,
  type ExamResult,
  type ResultItem,
  type StudentExam,
} from "@/lib/api/student-exams";
import { cn } from "@/lib/utils";

export type FinishedReason = "submitted" | "timeout" | "closed";

function numberText(value: number | string | null | undefined): string {
  if (value === null || value === undefined || value === "") return "—";
  const n = marksNumber(value);
  return Number.isInteger(n) ? String(n) : String(Number(n.toFixed(2)));
}

const FINISHED_COPY: Record<FinishedReason, string> = {
  submitted: "Your answers were submitted.",
  timeout: "Time is up — your answers were submitted.",
  closed: "This attempt has already been submitted. Your saved answers count.",
};

function ResultSummary({ result, exam }: { result: ExamResult; exam: StudentExam }) {
  const hasCutoff = result.cutoffMarks !== null && result.cutoffMarks !== undefined && result.passed !== null;
  const questionTotal = result.items?.length ?? exam.questionCount ?? null;
  return (
    <section
      aria-labelledby="result-summary-title"
      className="flex flex-col gap-4 rounded-xl bg-card p-4 ring-1 ring-foreground/10 sm:p-5"
      data-testid="result-summary"
    >
      <h2 id="result-summary-title" className="text-base font-semibold text-foreground">
        Your result
      </h2>
      <div className="flex flex-wrap items-end gap-x-6 gap-y-3">
        <p className="flex items-baseline gap-1 text-foreground">
          <span className="text-4xl font-bold tabular-nums" data-testid="result-score">
            {numberText(result.score)}
          </span>
          <span className="text-lg text-muted-foreground tabular-nums">
            / <span data-testid="result-total">{numberText(result.totalMarks)}</span>
          </span>
          <span className="sr-only">marks</span>
        </p>
        {hasCutoff ? (
          <span
            data-testid="result-outcome"
            className={cn(
              "inline-flex h-8 items-center gap-1.5 rounded-full px-3 text-sm font-semibold",
              result.passed
                ? "bg-green-600/10 text-green-700 dark:text-green-400"
                : "bg-destructive/10 text-destructive"
            )}
          >
            {result.passed ? <CheckCircle2 className="size-4" aria-hidden="true" /> : <XCircle className="size-4" aria-hidden="true" />}
            {result.passed ? "Passed" : "Not passed"}
          </span>
        ) : null}
      </div>
      <dl className="grid gap-2 text-sm sm:grid-cols-2">
        {hasCutoff ? (
          <div className="flex gap-1.5">
            <dt className="text-muted-foreground">Cut-off</dt>
            <dd data-testid="result-cutoff">{formatExamMarks(result.cutoffMarks)}</dd>
          </div>
        ) : null}
        <div className="flex gap-1.5">
          <dt className="text-muted-foreground">Answered</dt>
          <dd data-testid="result-answered">
            {result.answeredCount}
            {questionTotal !== null ? ` of ${questionTotal}` : ""} questions
          </dd>
        </div>
      </dl>
    </section>
  );
}

function ReviewItem({ item }: { item: ResultItem }) {
  const correct = item.selectedOptionId !== null && item.selectedOptionId === item.correctOptionId;
  const unanswered = item.selectedOptionId === null;
  const headingId = `result-q-${item.sequence}`;
  return (
    <li>
      <section
        aria-labelledby={headingId}
        className="flex min-w-0 flex-col gap-3 rounded-xl bg-card p-4 ring-1 ring-foreground/10 sm:p-5"
        data-testid="result-item"
      >
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h3 id={headingId} className="text-sm font-semibold text-muted-foreground">
            Question {item.sequence}
          </h3>
          <span
            className={cn(
              "inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium",
              correct
                ? "bg-green-600/10 text-green-700 dark:text-green-400"
                : unanswered
                  ? "bg-muted text-muted-foreground"
                  : "bg-destructive/10 text-destructive"
            )}
            data-testid="result-item-outcome"
          >
            {correct ? (
              <CheckCircle2 className="size-3" aria-hidden="true" />
            ) : unanswered ? (
              <CircleSlash className="size-3" aria-hidden="true" />
            ) : (
              <XCircle className="size-3" aria-hidden="true" />
            )}
            {correct ? "Correct" : unanswered ? "Not answered" : "Incorrect"} · {numberText(item.awardedMarks)} /{" "}
            {numberText(item.marks)}
          </span>
        </div>
        <RichContent html={item.bodyHtml} className="text-base text-foreground" />
        <ul className="flex flex-col gap-2">
          {item.options.map((option, index) => {
            const isSelected = option.id === item.selectedOptionId;
            const isCorrect = option.id === item.correctOptionId;
            return (
              <li
                key={option.id}
                data-testid="result-option"
                data-selected={isSelected}
                data-correct={isCorrect}
                className={cn(
                  "flex min-w-0 items-start gap-3 rounded-lg border p-3",
                  isCorrect
                    ? "border-green-600/50 bg-green-600/5"
                    : isSelected
                      ? "border-destructive/50 bg-destructive/5"
                      : "border-border"
                )}
              >
                <span
                  aria-hidden="true"
                  className="flex size-6 shrink-0 items-center justify-center rounded-full border border-border text-xs font-semibold text-muted-foreground"
                >
                  {optionLetter(index)}
                </span>
                <div className="flex min-w-0 flex-1 flex-col gap-1">
                  <RichContent html={option.html} className="text-sm text-foreground" />
                  {isSelected || isCorrect ? (
                    <div className="flex flex-wrap gap-1.5">
                      {isSelected ? (
                        <span className="inline-flex items-center rounded-full bg-muted px-2 py-0.5 text-xs font-medium text-foreground">
                          Your answer
                        </span>
                      ) : null}
                      {isCorrect ? (
                        <span className="inline-flex items-center gap-1 rounded-full bg-green-600/10 px-2 py-0.5 text-xs font-medium text-green-700 dark:text-green-400">
                          <CheckCircle2 className="size-3" aria-hidden="true" />
                          Correct answer
                        </span>
                      ) : null}
                    </div>
                  ) : null}
                </div>
              </li>
            );
          })}
        </ul>
        {item.explanationHtml ? (
          <div className="flex flex-col gap-1 rounded-lg bg-muted/50 p-3" data-testid="result-explanation">
            <p className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">Explanation</p>
            <RichContent html={item.explanationHtml} className="text-sm text-foreground" />
          </div>
        ) : null}
      </section>
    </li>
  );
}

function ResultBody({ exam }: { exam: StudentExam }) {
  const attempt = exam.attempt;
  const router = useRouter();
  const query = useAttemptResult(attempt?.id ?? "", { enabled: Boolean(attempt) && exam.type === "MCQ" });

  if (exam.type !== "MCQ") {
    return (
      <EmptyState
        title="Results for this exam aren't shown here yet"
        description="Your teacher publishes marks for written and in-class exams. You'll be notified when they're available."
      />
    );
  }
  if (!attempt) {
    return exam.status === "FINISHED" ? (
      <EmptyState title="You didn't attempt this exam" description="The exam closed without an attempt from you, so there's no result to show." />
    ) : (
      <EmptyState
        title="You haven't started this exam"
        description="There's no result yet because you haven't attempted this exam."
        action={{ label: "Go to exam details", onClick: () => router.push(studentExamPaths.details(exam.id)) }}
      />
    );
  }

  const stillOpen = attempt.status === "IN_PROGRESS" && exam.status === "IN_PROGRESS";

  if (query.status === "pending") return <LoadingState label="Loading your result…" />;
  if (query.status === "error") {
    if (isResultNotReleasedError(query.error)) {
      return (
        <div className="flex flex-col gap-3" data-testid="result-not-released">
          <EmptyState
            title={`Results will be available after ${formatExamDateTime(exam.endAt)}`}
            description="Scores are released when the exam window closes for everyone. Check back then."
            icon={<Clock className="size-8" aria-hidden="true" />}
          />
          {stillOpen ? (
            <div>
              <Link href={studentExamPaths.take(exam.id)} className={buttonVariants()} data-testid="result-resume-link">
                <Play aria-hidden="true" />
                Resume exam
              </Link>
            </div>
          ) : null}
        </div>
      );
    }
    if (isApiClientError(query.error) && query.error.status === 404) {
      return <EmptyState title="Result not found" description="This attempt doesn't exist, or it isn't yours." />;
    }
    return (
      <QueryStateBoundary query={query} loginPath="/login" permissionDenied={{ dashboardHref: studentExamPaths.list }}>
        {() => null}
      </QueryStateBoundary>
    );
  }

  const result = query.data;
  return (
    <div className="flex flex-col gap-6">
      <ResultSummary result={result} exam={exam} />
      {result.items && result.items.length > 0 ? (
        <section aria-labelledby="result-review-title" className="flex flex-col gap-3">
          <h2 id="result-review-title" className="text-base font-semibold text-foreground">
            Answer review
          </h2>
          <ol className="flex flex-col gap-4" data-testid="result-items">
            {[...result.items]
              .sort((a, b) => a.sequence - b.sequence)
              .map((item) => (
                <ReviewItem key={item.sequence} item={item} />
              ))}
          </ol>
        </section>
      ) : (
        <p className="text-sm text-muted-foreground" data-testid="result-answers-hidden">
          Correct answers aren&apos;t shown for this exam.
        </p>
      )}
    </div>
  );
}

/** Result page (Slice C): score after the window closes; answers + explanations only when released. */
export function StudentExamResultPage({ examId, finished }: { examId: string; finished: FinishedReason | null }) {
  const query = useStudentExam(examId);
  const router = useRouter();
  const notFound = query.status === "error" && isApiClientError(query.error) && query.error.status === 404;
  return (
    <div className="flex min-w-0 flex-col gap-6">
      <Breadcrumbs
        items={[
          { label: "My exams", href: studentExamPaths.list },
          ...(query.data ? [{ label: query.data.name, href: studentExamPaths.details(examId) }] : []),
          { label: "Result" },
        ]}
      />
      {finished ? (
        <Alert role="status" data-testid="attempt-finished-banner">
          {finished === "timeout" ? <Timer aria-hidden="true" /> : <CheckCircle2 aria-hidden="true" />}
          <AlertDescription className="font-medium text-foreground">{FINISHED_COPY[finished]}</AlertDescription>
        </Alert>
      ) : null}
      {notFound ? (
        <EmptyState
          title="Exam not found"
          description="This exam doesn't exist, or you aren't enrolled in it."
          action={{ label: "Back to my exams", onClick: () => router.push(studentExamPaths.list) }}
        />
      ) : (
        <QueryStateBoundary
          query={query}
          loadingLabel="Loading your result…"
          loginPath="/login"
          permissionDenied={{ dashboardHref: "/student/dashboard" }}
          genericErrorMessage="We couldn't load this exam."
        >
          {(exam) => (
            <div className="flex flex-col gap-6">
              <StudentExamSummary exam={exam} />
              <ResultBody exam={exam} />
            </div>
          )}
        </QueryStateBoundary>
      )}
      <div>
        <Link href={studentExamPaths.list} className={buttonVariants({ variant: "ghost" })}>
          <ArrowLeft aria-hidden="true" />
          Back to my exams
        </Link>
      </div>
    </div>
  );
}
