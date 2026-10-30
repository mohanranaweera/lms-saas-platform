"use client";

import Link from "next/link";
import { ArrowLeft, CalendarDays, ChartColumn, FileClock, Timer, Trophy } from "lucide-react";
import { buttonVariants } from "@/components/ui/button";
import { Breadcrumbs } from "@/components/ui/breadcrumbs";
import { Skeleton } from "@/components/ui/skeleton";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { EmptyState } from "@/components/states/empty-state";
import {
  EXAM_TYPE_LABELS,
  ExamLiveStatusChip,
  ExamPageTitle,
  ExamTypeIcon,
  examPaths,
  formatExamMarks,
  type ExamPortal,
} from "@/components/exams/config/exam-shared";
import { formatDuration, formatExamDateTime } from "@/lib/exams/exam-time";
import { useExamDetail, type ExamDetail } from "@/lib/api/exam-config";
import { ExamEnrollmentActions } from "@/components/exams/enrollment/exam-enrollment-actions";
import { useAuth } from "@/lib/auth/auth-context";
import { canAuthorExams } from "@/lib/auth/permissions";
import { cn } from "@/lib/utils";

/** Exam header from `GET /exams/{id}` (title, type, status, course, window, size). Never shows the password (D6). */
function ExamHeader({ exam }: { exam: ExamDetail }) {
  const duration = formatDuration(exam.durationMinutes);
  const size =
    exam.type === "MCQ"
      ? exam.paperQuestionCount ?? exam.questionCount
        ? `${exam.paperQuestionCount ?? exam.questionCount} Questions`
        : null
      : formatExamMarks(exam.totalMarks);
  return (
    <section
      aria-labelledby="exam-header-title"
      className="flex flex-col gap-3 rounded-xl bg-card p-4 ring-1 ring-foreground/10"
      data-testid="exam-header"
    >
      <div className="flex flex-wrap items-center gap-2">
        <ExamTypeIcon type={exam.type} />
        <span className="text-sm font-semibold tracking-wide text-muted-foreground uppercase">{EXAM_TYPE_LABELS[exam.type]}</span>
        <ExamLiveStatusChip status={exam.status} />
      </div>
      <div className="flex flex-col gap-0.5">
        <h2 id="exam-header-title" className="text-lg font-semibold break-words text-foreground" data-testid="exam-header-name">
          {exam.name}
        </h2>
        <p className="text-sm text-muted-foreground">{exam.courseName}</p>
      </div>
      <dl className="grid gap-2 text-sm sm:grid-cols-2">
        <div className="flex items-center gap-1.5">
          <CalendarDays className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
          <dt className="sr-only">From</dt>
          <dd>
            <time dateTime={exam.startAt}>{formatExamDateTime(exam.startAt)}</time>
          </dd>
        </div>
        <div className="flex items-center gap-1.5">
          <CalendarDays className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
          <dt className="text-muted-foreground">To</dt>
          <dd>
            <time dateTime={exam.endAt}>{formatExamDateTime(exam.endAt)}</time>
          </dd>
        </div>
        <div className="flex items-center gap-1.5">
          <Timer className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
          <dt className="sr-only">Duration</dt>
          <dd>{duration ?? "No fixed duration"}</dd>
        </div>
        {size ? (
          <div className="flex items-center gap-1.5">
            <FileClock className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
            <dt className="sr-only">Size</dt>
            <dd>{size}</dd>
          </div>
        ) : null}
      </dl>
    </section>
  );
}

function HeaderSkeleton() {
  return (
    <div role="status" aria-busy="true" className="flex flex-col gap-3 rounded-xl p-4 ring-1 ring-foreground/10">
      <span className="sr-only">Loading exam…</span>
      <Skeleton className="h-6 w-40" />
      <Skeleton className="h-6 w-2/3" />
      <Skeleton className="h-4 w-1/2" />
    </div>
  );
}

/**
 * Minimal Manage / Stats pages (Slice B): the exam header only. Slices D/E
 * replace the body with the management table and statistics.
 */
export function ExamHeaderPage({ portal, examId, view }: { portal: ExamPortal; examId: string; view: "manage" | "stats" }) {
  const paths = examPaths(portal);
  const query = useExamDetail(examId);
  const isStats = view === "stats";
  const { session } = useAuth();
  // UX only: the enrollment endpoints re-check authoring access server-side.
  const canEnroll = !isStats && canAuthorExams(session?.role ?? null);
  return (
    <div className="flex min-w-0 flex-col gap-6">
      <Breadcrumbs
        items={[
          { label: "Exams", href: paths.directory },
          ...(isStats
            ? [{ label: query.data?.name ?? "Exam", href: paths.manage(examId) }, { label: "Stats" }]
            : [{ label: query.data?.name ?? "Exam" }]),
        ]}
      />
      <ExamPageTitle
        title={isStats ? "Exam statistics" : "Manage exam"}
        icon={isStats ? <ChartColumn className="size-5" aria-hidden="true" /> : <FileClock className="size-5" aria-hidden="true" />}
        actions={
          <div className="flex flex-wrap gap-2">
            <Link href={paths.directory} className={buttonVariants({ variant: "outline" })}>
              <ArrowLeft aria-hidden="true" />
              All exams
            </Link>
            <Link
              href={isStats ? paths.manage(examId) : paths.stats(examId)}
              className={buttonVariants({ variant: "outline" })}
              data-testid={isStats ? "exam-manage-link" : "exam-stats-link"}
            >
              {isStats ? null : <ChartColumn aria-hidden="true" />}
              {isStats ? "Manage" : "Stats"}
            </Link>
          </div>
        }
      />
      {query.status === "pending" ? (
        <HeaderSkeleton />
      ) : (
        <QueryStateBoundary query={query} loginPath="/login" permissionDenied={{ dashboardHref: paths.directory }}>
          {(exam) => (
            <div className="flex flex-col gap-6">
              <ExamHeader exam={exam} />
              {canEnroll ? <ExamEnrollmentActions exam={exam} /> : null}
              <EmptyState
                title={isStats ? "Statistics are on the way" : "Student management is on the way"}
                description={
                  isStats
                    ? "Average, highest and lowest marks and the question breakdown will appear here once results are recorded."
                    : "Enrolled students, attendance and marks for this exam will be listed here."
                }
              />
            </div>
          )}
        </QueryStateBoundary>
      )}
    </div>
  );
}

/** Placeholder route for the leaderboards (Slice E). */
export function ExamLeaderboardsPlaceholder({ portal }: { portal: ExamPortal }) {
  const paths = examPaths(portal);
  return (
    <div className="flex min-w-0 flex-col gap-6">
      <Breadcrumbs items={[{ label: "Exams", href: paths.directory }, { label: "Leaderboards" }]} />
      <ExamPageTitle
        title="Leaderboards"
        icon={<Trophy className="size-5" aria-hidden="true" />}
        actions={
          <Link href={paths.directory} className={cn(buttonVariants({ variant: "outline" }))}>
            <ArrowLeft aria-hidden="true" />
            All exams
          </Link>
        }
      />
      <EmptyState
        title="Leaderboards are coming soon"
        description="Group exams into a leaderboard to rank students by their combined marks. Ranks appear once marks are published."
      />
    </div>
  );
}
