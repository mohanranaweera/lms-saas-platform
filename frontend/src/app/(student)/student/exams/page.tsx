"use client";

import { useMemo } from "react";
import Link from "next/link";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { ExamStatusChip } from "@/components/exams/exam-status-chip";
import { useMyEnrolledCourseSummaries, indexCourseSummariesById } from "@/lib/api/enrollments";
import { useMyUpcomingExams } from "@/lib/api/exams";
import { formatDateTime, shortId } from "@/lib/format";

/**
 * Student Exam List (MVP-017 plan §11 screen #1). `GET /api/v1/exams/my/upcoming`
 * is owner-only (`hasRole('STUDENT')`, no id param) and, per
 * `ExamSchedulingService.listMyUpcomingExams`'s actual implementation, only
 * ever returns exams with a resolved status of `SCHEDULED` or `PUBLISHED` —
 * it structurally cannot return a `DRAFT` or `CLOSED` exam.
 *
 * **Known limitation, not a resolved design decision:** this means this
 * screen can only ever render one real empty case from this endpoint's
 * output ("nothing SCHEDULED/PUBLISHED exists for my enrolled courses yet"),
 * not the two-variant pair the plan's issue-derived AC describes ("no exams
 * scheduled" vs. "no published exams — drafts/scheduled exams exist, nothing
 * is visible yet"). The second variant is not distinguishable from this
 * endpoint's response at all, since a still-`DRAFT` exam is invisible to it
 * by construction, not merely filtered out while still being detectable.
 * Closing this gap for real would require a backend change (e.g. a way to
 * check whether any `DRAFT` exam exists for the student's enrolled courses,
 * without leaking its contents) — that is out of scope here and should be
 * raised as a follow-up, not worked around client-side. The genuinely
 * distinct "no published exams" state lives on the Results & Review screen
 * (#3) instead, where it has real meaning (a submitted, unpublished attempt).
 */
export default function StudentExamListPage() {
  const courseSummariesQuery = useMyEnrolledCourseSummaries();
  const examsQuery = useMyUpcomingExams({ size: 50 });

  const courseSummaries = useMemo(
    () => indexCourseSummariesById(courseSummariesQuery.data),
    [courseSummariesQuery.data]
  );

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">Exams</h1>
        <p className="text-sm text-muted-foreground">
          Exams scheduled for your enrolled courses. An exam becomes available to take once its window opens.
        </p>
      </div>

      <QueryStateBoundary
        query={examsQuery}
        loadingLabel="Loading your exams…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/student/dashboard" }}
        isEmpty={(data) => data.content.length === 0}
        emptyState={{
          title: "No exams scheduled yet",
          description: "When a teacher schedules an exam for one of your courses, it will appear here.",
        }}
      >
        {(data) => (
          <ul className="flex flex-col gap-3">
            {data.content.map((exam) => {
              const courseName = courseSummaries.get(exam.courseId)?.name ?? shortId(exam.courseId, "Course");
              const isTakeable = exam.status === "PUBLISHED";
              return (
                <li
                  key={exam.id}
                  className="flex flex-col gap-2 rounded-lg border border-border p-4 sm:flex-row sm:items-center sm:justify-between"
                >
                  <div className="flex flex-col gap-1">
                    <span className="text-sm font-medium text-foreground">{exam.title}</span>
                    <span className="text-xs text-muted-foreground">{courseName}</span>
                    <span className="text-xs text-muted-foreground">
                      {isTakeable
                        ? `Window closes ${formatDateTime(exam.scheduledEnd)}`
                        : `Opens ${formatDateTime(exam.scheduledStart)}`}
                    </span>
                  </div>
                  <div className="flex items-center gap-3">
                    <ExamStatusChip status={exam.status} />
                    {isTakeable ? (
                      <Link
                        href={`/student/exams/${exam.id}/take`}
                        className="text-sm font-medium text-foreground hover:underline"
                      >
                        Take exam →
                      </Link>
                    ) : (
                      <span className="text-xs text-muted-foreground">Not yet open</span>
                    )}
                  </div>
                </li>
              );
            })}
          </ul>
        )}
      </QueryStateBoundary>
    </div>
  );
}
