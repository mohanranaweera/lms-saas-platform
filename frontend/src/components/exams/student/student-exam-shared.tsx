"use client";

import { CalendarDays, CheckCircle2, CircleDashed, FileClock, Hourglass, ListChecks, TimerOff, Timer } from "lucide-react";
import { EXAM_TYPE_LABELS, ExamLiveStatusChip, ExamTypeIcon, formatExamMarks } from "@/components/exams/config/exam-shared";
import { formatDuration, formatExamDateTime } from "@/lib/exams/exam-time";
import type { StudentExam } from "@/lib/api/student-exams";
import { cn } from "@/lib/utils";

/** Student exam routes. The take/result pages find the attempt through `GET /student/exams/{id}`. */
export const studentExamPaths = {
  list: "/student/exams",
  details: (examId: string) => `/student/exams/${examId}`,
  take: (examId: string) => `/student/exams/${examId}/take`,
  result: (examId: string, finished?: "submitted" | "timeout" | "closed") =>
    `/student/exams/${examId}/results${finished ? `?finished=${finished}` : ""}`,
};

export function questionCountText(count: number | null | undefined): string | null {
  if (count === null || count === undefined) return null;
  return `${count} ${count === 1 ? "question" : "questions"}`;
}

/** "In progress", "Submitted", … — from the server's attempt status, never inferred from the clock. */
export function attemptStatusLabel(exam: StudentExam): string {
  const attempt = exam.attempt;
  if (!attempt) return exam.status === "FINISHED" ? "Not attempted" : "Not started";
  if (attempt.status === "IN_PROGRESS") return exam.status === "FINISHED" ? "Being finalized" : "In progress";
  if (attempt.status === "EXPIRED") return "Auto-submitted at the deadline";
  return "Submitted";
}

export function AttemptStatusChip({ exam, className }: { exam: StudentExam; className?: string }) {
  const attempt = exam.attempt;
  const Icon = !attempt ? CircleDashed : attempt.status === "IN_PROGRESS" ? Hourglass : attempt.status === "EXPIRED" ? TimerOff : CheckCircle2;
  return (
    <span
      data-testid="attempt-status"
      className={cn(
        "inline-flex h-6 shrink-0 items-center gap-1 rounded-full px-2 text-xs font-medium whitespace-nowrap",
        !attempt
          ? "bg-muted text-muted-foreground"
          : attempt.status === "IN_PROGRESS"
            ? "bg-amber-500/10 text-amber-800 dark:text-amber-300"
            : "bg-green-600/10 text-green-700 dark:text-green-400",
        className
      )}
    >
      <Icon className="size-3" aria-hidden="true" />
      {attemptStatusLabel(exam)}
    </span>
  );
}

export type StudentExamCta =
  | { kind: "link"; label: string; href: string; testId: string }
  | { kind: "text"; text: string; testId: string };

/**
 * The card's next step, from server facts only (exam status, attempt status,
 * `resultAvailable`). Starting/resuming is still re-checked by the server.
 */
export function studentExamCta(exam: StudentExam): StudentExamCta {
  if (exam.type !== "MCQ" && exam.resultAvailable) {
    return { kind: "link", label: "View result", href: studentExamPaths.result(exam.id), testId: "exam-cta-result" };
  }
  if (exam.type === "PHYSICAL") return { kind: "text", text: "Sit this exam in class", testId: "exam-cta-info" };
  if (exam.type === "WRITTEN") {
    const open = exam.status === "IN_PROGRESS" && (!exam.attempt || exam.attempt.status === "IN_PROGRESS");
    return {
      kind: "link",
      label: open ? (exam.attempt ? "Continue" : "Open") : "View details",
      href: studentExamPaths.details(exam.id),
      testId: "exam-cta-details",
    };
  }
  const attempt = exam.attempt;
  if (attempt) {
    if (attempt.status === "IN_PROGRESS" && exam.status === "IN_PROGRESS") {
      return { kind: "link", label: "Resume", href: studentExamPaths.take(exam.id), testId: "exam-cta-resume" };
    }
    if (exam.resultAvailable) {
      return { kind: "link", label: "View result", href: studentExamPaths.result(exam.id), testId: "exam-cta-result" };
    }
    return {
      kind: "text",
      text: `Results available after ${formatExamDateTime(exam.endAt)}`,
      testId: "exam-cta-pending",
    };
  }
  if (exam.status === "IN_PROGRESS") {
    return { kind: "link", label: "Start", href: studentExamPaths.details(exam.id), testId: "exam-cta-start" };
  }
  if (exam.status === "UPCOMING" || exam.status === "DRAFT") {
    return { kind: "link", label: "View details", href: studentExamPaths.details(exam.id), testId: "exam-cta-details" };
  }
  return { kind: "text", text: "You didn't attempt this exam", testId: "exam-cta-missed" };
}

/** Type, status, name, course, window and size — shared by the start and result pages. */
export function StudentExamSummary({ exam, headingLevel = 1 }: { exam: StudentExam; headingLevel?: 1 | 2 }) {
  const Heading = headingLevel === 1 ? "h1" : "h2";
  const duration = formatDuration(exam.durationMinutes);
  const extent = [questionCountText(exam.questionCount), formatExamMarks(exam.totalMarks)].filter(Boolean).join(" · ");
  return (
    <section
      aria-labelledby="student-exam-title"
      className="flex min-w-0 flex-col gap-3 rounded-xl bg-card p-4 ring-1 ring-foreground/10 sm:p-5"
      data-testid="student-exam-summary"
    >
      <div className="flex flex-wrap items-center gap-2">
        <ExamTypeIcon type={exam.type} />
        <span className="text-sm font-semibold tracking-wide text-muted-foreground uppercase">{EXAM_TYPE_LABELS[exam.type]}</span>
        <ExamLiveStatusChip status={exam.status} />
        <AttemptStatusChip exam={exam} />
      </div>
      <div className="flex min-w-0 flex-col gap-0.5">
        <Heading id="student-exam-title" className="text-xl font-semibold break-words text-foreground">
          {exam.name}
        </Heading>
        <p className="text-sm text-muted-foreground">{exam.courseName}</p>
      </div>
      <dl className="grid gap-2 text-sm sm:grid-cols-2">
        <div className="flex items-start gap-1.5">
          <CalendarDays className="mt-0.5 size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
          <dt className="text-muted-foreground">Opens</dt>
          <dd>
            <time dateTime={exam.startAt}>{formatExamDateTime(exam.startAt)}</time>
          </dd>
        </div>
        <div className="flex items-start gap-1.5">
          <CalendarDays className="mt-0.5 size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
          <dt className="text-muted-foreground">Closes</dt>
          <dd>
            <time dateTime={exam.endAt}>{formatExamDateTime(exam.endAt)}</time>
          </dd>
        </div>
        <div className="flex items-start gap-1.5">
          <Timer className="mt-0.5 size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
          <dt className="sr-only">Duration</dt>
          <dd>{duration ?? "No fixed duration"}</dd>
        </div>
        {extent ? (
          <div className="flex items-start gap-1.5">
            {exam.type === "MCQ" ? (
              <ListChecks className="mt-0.5 size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
            ) : (
              <FileClock className="mt-0.5 size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
            )}
            <dt className="sr-only">Size</dt>
            <dd>{extent}</dd>
          </div>
        ) : null}
      </dl>
    </section>
  );
}
