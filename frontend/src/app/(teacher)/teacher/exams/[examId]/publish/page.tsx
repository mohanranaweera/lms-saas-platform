"use client";

import { useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import {
  AlertDialog,
  AlertDialogClose,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import { LiveRegion } from "@/components/ui/live-region";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { ExamStatusChip } from "@/components/exams/exam-status-chip";
import { ExamPicker } from "@/components/exams/exam-picker";
import { useAuth } from "@/lib/auth/auth-context";
import { canManageExamsStaff, canScheduleOrPublishExam, isTeacherRole } from "@/lib/auth/permissions";
import { usePublishResults, useExam } from "@/lib/api/exams";
import { isApiClientError } from "@/lib/api/error";
import { formatDateTime, formatRoleLabel } from "@/lib/format";

/**
 * Teacher/staff Results Publishing (MVP-017 plan §11 screen #7). Publish is
 * only meaningful once `status === "CLOSED"` — the actual, sole backend
 * precondition (`ResultsPublishingService`, 409 otherwise). The backend
 * exposes no marking-completion percentage, so this deliberately does not
 * fabricate a "marking still in progress" completeness check beyond that —
 * three real, distinct next-action states are shown: not yet closed,
 * closed-and-ready, and already published (with `resultsPublishedAt`).
 *
 * An `ExamPicker` at the top lets the caller switch to a different exam
 * (navigating to that exam's own `/publish` URL) — added post-review so this
 * screen is revisitable without already knowing an exam id (previously only
 * reachable via a just-completed action's own navigation).
 *
 * **Fix 4 (role-context banner):** see the Scheduler page's identical doc
 * comment — this screen is reachable by Tenant Admin/Exam Manager (never
 * Teacher Assistant, per plan §11's own access column) despite living under
 * the "Teacher Portal" route group/chrome, so a banner discloses that
 * mismatch whenever the caller isn't a Teacher/Teacher Assistant.
 */
export default function TeacherExamPublishPage() {
  const { examId } = useParams<{ examId: string }>();
  const router = useRouter();
  const { session } = useAuth();
  const role = session?.role ?? null;
  const isViewedAsTeacher = isTeacherRole(role);
  const examQuery = useExam(examId);
  const publishMutation = usePublishResults(examId);
  const [publishError, setPublishError] = useState<string | null>(null);
  const [publishDialogOpen, setPublishDialogOpen] = useState(false);

  const canReachPublishAction = canScheduleOrPublishExam(role) || canManageExamsStaff(role);

  // Fix 2 (blocking, UX review): publishing is irreversible — this exam's
  // own copy says so — so the action must be behind an `AlertDialog`
  // confirmation, same pattern as `question-list-item.tsx`'s delete. On
  // failure the dialog stays open (no `setPublishDialogOpen(false)`) so the
  // teacher sees the error and can retry without re-opening the dialog.
  const handlePublish = async () => {
    setPublishError(null);
    try {
      await publishMutation.mutateAsync();
      setPublishDialogOpen(false);
    } catch (error) {
      setPublishError(isApiClientError(error) ? error.message : "Could not publish results. Please try again.");
    }
  };

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">Results Publishing</h1>
        <p className="text-sm text-muted-foreground">
          Publishing makes every student&apos;s score and per-question review visible. This cannot be undone.
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

      <ExamPicker
        idPrefix="publish-results"
        initialExamId={examId}
        onSelect={(nextExamId) => router.push(`/teacher/exams/${nextExamId}/publish`)}
      />

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

            {exam.resultsPublishedAt ? (
              <Alert role="status">
                <AlertDescription>
                  Results were already published on {formatDateTime(exam.resultsPublishedAt)}. Publishing is a
                  one-way action — there is no unpublish path.
                </AlertDescription>
              </Alert>
            ) : exam.status !== "CLOSED" ? (
              <Alert role="status">
                <AlertDescription>
                  Publish becomes available once the exam window closes and its status advances to Closed. This
                  exam is currently {exam.status}.
                </AlertDescription>
              </Alert>
            ) : (
              <div className="flex flex-col gap-2 rounded-lg border border-border p-4">
                <p className="text-sm text-muted-foreground">
                  This exam is Closed and ready to publish. Every enrolled student will immediately be able to
                  see their score and per-question review.
                </p>
                <LiveRegion message={publishMutation.isPending ? "Publishing results…" : ""} />
                {publishError && !publishDialogOpen ? (
                  <p role="alert" className="text-sm text-destructive">
                    {publishError}
                  </p>
                ) : null}
                {canReachPublishAction ? (
                  <AlertDialog open={publishDialogOpen} onOpenChange={setPublishDialogOpen}>
                    <AlertDialogTrigger render={<Button type="button" className="w-full sm:w-fit" />}>
                      Publish results
                    </AlertDialogTrigger>
                    <AlertDialogContent>
                      <AlertDialogHeader>
                        <AlertDialogTitle>Publish results for this exam?</AlertDialogTitle>
                        <AlertDialogDescription>
                          Publishing makes every enrolled student&apos;s score and per-question review visible
                          immediately. This cannot be undone — there is no unpublish path.
                        </AlertDialogDescription>
                      </AlertDialogHeader>
                      {publishError ? (
                        <Alert variant="destructive">
                          <AlertDescription>{publishError}</AlertDescription>
                        </Alert>
                      ) : null}
                      <AlertDialogFooter>
                        <AlertDialogClose render={<Button type="button" variant="outline" />}>
                          Cancel
                        </AlertDialogClose>
                        <Button
                          type="button"
                          onClick={handlePublish}
                          disabled={publishMutation.isPending}
                          aria-busy={publishMutation.isPending}
                        >
                          {publishMutation.isPending ? "Publishing…" : "Publish results"}
                        </Button>
                      </AlertDialogFooter>
                    </AlertDialogContent>
                  </AlertDialog>
                ) : null}
              </div>
            )}
          </div>
        )}
      </QueryStateBoundary>
    </div>
  );
}
