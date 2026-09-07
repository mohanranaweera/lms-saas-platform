"use client";

import { useState } from "react";
import { useSearchParams } from "next/navigation";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { ExamStatusChip } from "@/components/exams/exam-status-chip";
import { ExamPicker } from "@/components/exams/exam-picker";
import { MarkingQueueEntryRow } from "@/components/exams/marking-queue-entry-row";
import { useAuth } from "@/lib/auth/auth-context";
import { isTeacherRole } from "@/lib/auth/permissions";
import { useExam, useMarkingQueueEntries } from "@/lib/api/exams";
import { formatRoleLabel } from "@/lib/format";

const PAGE_SIZE = 20;

/**
 * Teacher/staff Marking Queue (MVP-017 plan §11 screen #6). Course → exam
 * dropdown picker, backed by the real `GET /courses/{courseId}/exams` list
 * (rebuilt post-review — previously a manual exam-id-paste form, disclosed as
 * a stopgap pending that endpoint; see `lib/api/exams.ts`'s doc comment for
 * the closed-gap record). A `?examId=...` deep link (e.g. from the Scheduler
 * screen) still pre-selects both dropdowns by resolving that exam's
 * `courseId` first.
 *
 * **Fix 4 (role-context banner):** see the Scheduler/Publish pages' identical
 * doc comment — Exam Manager/Tenant Admin legitimately reach this screen
 * tenant-wide despite the "Teacher Portal" chrome, so a banner discloses that
 * mismatch whenever the caller isn't a Teacher/Teacher Assistant.
 */
export default function TeacherMarkingQueuePage() {
  const searchParams = useSearchParams();
  const deepLinkExamId = searchParams.get("examId") ?? "";
  const { session } = useAuth();
  const role = session?.role ?? null;
  const isViewedAsTeacher = isTeacherRole(role);

  const [examId, setExamId] = useState(deepLinkExamId);
  const [page, setPage] = useState(0);

  const examQuery = useExam(examId);
  const queueQuery = useMarkingQueueEntries(examId, { page, size: PAGE_SIZE });

  function handleExamChange(value: string) {
    setExamId(value);
    setPage(0);
  }

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">Marking Queue</h1>
        <p className="text-sm text-muted-foreground">
          Manually mark structured-answer submissions for one exam.
        </p>
      </div>

      {role && !isViewedAsTeacher ? (
        <Alert role="status">
          <AlertTitle>Viewing as {formatRoleLabel(role)}</AlertTitle>
          <AlertDescription>
            You&apos;re viewing this as {formatRoleLabel(role)}, not as the course&apos;s Teacher. This page
            is nested under the Teacher Portal, but oversight roles reach it too, per this exam module&apos;s
            access rules.
          </AlertDescription>
        </Alert>
      ) : null}

      <ExamPicker idPrefix="marking-queue" initialExamId={deepLinkExamId} onSelect={handleExamChange} />

      {examId ? (
        <QueryStateBoundary
          query={examQuery}
          loadingLabel="Loading exam…"
          loginPath="/login"
          permissionDenied={{ dashboardHref: "/teacher/dashboard" }}
        >
          {(exam) => (
            <div className="flex flex-col gap-4">
              <div className="flex flex-col gap-2 rounded-lg border border-border p-4 sm:flex-row sm:items-center sm:justify-between">
                <p className="text-sm font-medium text-foreground">{exam.title}</p>
                <ExamStatusChip status={exam.status} />
              </div>

              <QueryStateBoundary
                query={queueQuery}
                loadingLabel="Loading the marking queue…"
                loginPath="/login"
                permissionDenied={{ dashboardHref: "/teacher/dashboard" }}
                isEmpty={(data) => data.content.length === 0}
                emptyState={{
                  title: "Nothing to mark right now.",
                  description:
                    "Every structured answer for this exam has already been marked, or none have been submitted yet.",
                }}
              >
                {(data) => (
                  <div className="flex flex-col gap-4">
                    <ul className="flex flex-col gap-3">
                      {data.content.map((entry) => (
                        <MarkingQueueEntryRow key={entry.answerId} examId={examId} entry={entry} />
                      ))}
                    </ul>
                    <div className="flex items-center justify-between">
                      <Button
                        type="button"
                        variant="outline"
                        size="sm"
                        onClick={() => setPage((current) => Math.max(0, current - 1))}
                        disabled={page === 0}
                      >
                        Previous
                      </Button>
                      <span className="text-xs text-muted-foreground">
                        Page {data.page + 1} of {Math.max(data.totalPages, 1)}
                      </span>
                      <Button
                        type="button"
                        variant="outline"
                        size="sm"
                        onClick={() => setPage((current) => current + 1)}
                        disabled={data.page + 1 >= data.totalPages}
                      >
                        Next
                      </Button>
                    </div>
                  </div>
                )}
              </QueryStateBoundary>
            </div>
          )}
        </QueryStateBoundary>
      ) : null}
    </div>
  );
}
