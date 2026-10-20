"use client";

import { useState } from "react";
import Link from "next/link";
import { ClipboardList } from "lucide-react";
import { LoadingState } from "@/components/states/loading-state";
import { ErrorState } from "@/components/states/error-state";
import { ExamStatusChip } from "@/components/exams/exam-status-chip";
import { cn } from "@/lib/utils";
import { isApiClientError } from "@/lib/api/error";
import { useMyUpcomingExams, type ExamSummaryResponse } from "@/lib/api/exams";
import { formatLessonDateTime } from "./student-format";

type ExamBucket = "IN_PROGRESS" | "UPCOMING" | "PREVIOUS";

const BUCKET_LABELS: Record<ExamBucket, string> = {
  IN_PROGRESS: "In progress",
  UPCOMING: "Upcoming",
  PREVIOUS: "Previous",
};

const EMPTY_COPY: Record<ExamBucket, { title: string; description: string }> = {
  IN_PROGRESS: {
    title: "No exams running right now",
    description: "When your teacher starts an exam it will appear here.",
  },
  UPCOMING: {
    title: "No upcoming exams",
    description: "Scheduled exams for this course will appear here.",
  },
  PREVIOUS: {
    title: "No previous exams",
    description: "Exams whose window has closed will appear here while they're still listed.",
  },
};

function bucketOf(exam: ExamSummaryResponse, now: number): ExamBucket {
  const start = Date.parse(exam.scheduledStart);
  const end = Date.parse(exam.scheduledEnd);
  if (end < now) return "PREVIOUS";
  if (exam.status === "PUBLISHED" && start <= now) return "IN_PROGRESS";
  return "UPCOMING";
}

/**
 * Exams tab: the existing student exam list (`GET /api/v1/exams/my/upcoming`,
 * owner-only, server-filtered to the student's courses) narrowed to this
 * course. That endpoint never returns CLOSED exams, so "Previous" only holds
 * exams whose window ended but which the server still lists. Exam membership
 * follows the existing rule (D17/O-4), unchanged here.
 */
export function CourseExamsTab({ courseId, timeZone }: { courseId: string; timeZone: string | null }) {
  const query = useMyUpcomingExams({ size: 100 });
  const [bucket, setBucket] = useState<ExamBucket>("IN_PROGRESS");
  const [now] = useState(() => Date.now());

  if (query.status === "pending") return <LoadingState label="Loading exams…" />;
  if (query.status === "error") {
    return (
      <ErrorState
        message="We couldn't load your exams."
        code={isApiClientError(query.error) ? query.error.code : undefined}
        onRetry={() => query.refetch()}
      />
    );
  }

  const exams = query.data.content.filter((exam) => exam.courseId === courseId);
  const counts: Record<ExamBucket, number> = { IN_PROGRESS: 0, UPCOMING: 0, PREVIOUS: 0 };
  for (const exam of exams) counts[bucketOf(exam, now)] += 1;
  const visible = exams.filter((exam) => bucketOf(exam, now) === bucket);

  return (
    <section
      aria-label="Exams"
      className="flex flex-col gap-4 rounded-xl border border-border bg-card p-4 shadow-sm sm:p-6"
      data-testid="course-exams-panel"
    >
      <div role="group" aria-label="Exam status" className="flex flex-wrap gap-2">
        {(Object.keys(BUCKET_LABELS) as ExamBucket[]).map((value) => (
          <button
            key={value}
            type="button"
            aria-pressed={bucket === value}
            onClick={() => setBucket(value)}
            data-testid={`exam-bucket-${value}`}
            className={cn(
              "rounded-full border px-3 py-1 text-sm font-medium outline-none focus-visible:ring-3 focus-visible:ring-ring/50",
              bucket === value
                ? "border-blue-600 bg-blue-50 text-blue-700 dark:bg-blue-950 dark:text-blue-200"
                : "border-border text-foreground hover:bg-muted"
            )}
          >
            {BUCKET_LABELS[value]} ({counts[value]})
          </button>
        ))}
      </div>

      {visible.length === 0 ? (
        <div
          role="status"
          className="flex flex-col items-center gap-2 px-6 py-12 text-center"
          data-testid="course-exams-empty"
        >
          <ClipboardList className="size-8 text-muted-foreground" aria-hidden="true" />
          <p className="text-sm font-medium text-foreground">{EMPTY_COPY[bucket].title}</p>
          <p className="text-sm text-muted-foreground">{EMPTY_COPY[bucket].description}</p>
        </div>
      ) : (
        <ul className="flex flex-col gap-2">
          {visible.map((exam) => {
            const takeable = bucket === "IN_PROGRESS";
            return (
              <li
                key={exam.id}
                className="flex flex-col gap-2 rounded-lg border border-border p-3 sm:flex-row sm:items-center sm:justify-between"
                data-testid="course-exam-row"
              >
                <div className="flex flex-col gap-1">
                  <span className="text-sm font-medium text-foreground">{exam.title}</span>
                  <span className="text-xs text-muted-foreground">
                    {takeable
                      ? `Closes ${formatLessonDateTime(exam.scheduledEnd, timeZone)}`
                      : bucket === "UPCOMING"
                        ? `Opens ${formatLessonDateTime(exam.scheduledStart, timeZone)}`
                        : `Closed ${formatLessonDateTime(exam.scheduledEnd, timeZone)}`}
                  </span>
                </div>
                <div className="flex items-center gap-3">
                  <ExamStatusChip status={exam.status} />
                  {takeable ? (
                    <Link href={`/student/exams/${exam.id}/take`} className="text-sm font-medium text-blue-700 hover:underline dark:text-blue-300">
                      Take exam →
                    </Link>
                  ) : null}
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}
