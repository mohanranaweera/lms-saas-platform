"use client";

import { useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
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
import { ExamDraftForm } from "@/components/exams/exam-draft-form";
import { useAuth } from "@/lib/auth/auth-context";
import { canManageExamsStaff, canScheduleOrPublishExam, isTeacherRole } from "@/lib/auth/permissions";
import { useExam, useScheduleExam } from "@/lib/api/exams";
import { isApiClientError } from "@/lib/api/error";
import { formatRoleLabel } from "@/lib/format";

/**
 * Teacher/staff Exam Scheduler (MVP-017 plan §11 screen #5). Edits the exam
 * while `DRAFT` (`ExamDraftForm`), then the explicit `DRAFT -> SCHEDULED`
 * transition. The Schedule button is only *rendered* for a role that can
 * plausibly reach it (`canScheduleOrPublishExam || canManageExamsStaff`) —
 * pure UX convenience — but the mutation itself still round-trips to the
 * backend and surfaces a real 403 via `scheduleError` if a stale client ever
 * sends it anyway (`.claude/rules/ui-ux.md` §1's "hiding is not enforcement").
 *
 * No list-exams-by-course endpoint exists (see `lib/api/exams.ts`'s doc
 * comment) — this exam's own id is shown prominently so it can be copied
 * into the Marking Queue / Publish screens, which otherwise have no way to
 * discover it.
 *
 * **Fix 4 (role-context banner):** this screen lives under `app/(teacher)/`
 * and its shared layout always renders "Teacher Portal" chrome, but per the
 * plan's own §11 access column, Tenant Admin/Exam Manager legitimately reach
 * this same route tenant-wide (not just the course's Teacher). Changing the
 * route-group structure is out of scope for this fix — instead, a banner
 * below discloses the mismatch whenever the caller is not a Teacher/Teacher
 * Assistant, so the "Teacher Portal" label is never silently misleading.
 */
export default function TeacherExamSchedulePage() {
  const { examId } = useParams<{ examId: string }>();
  const { session } = useAuth();
  const role = session?.role ?? null;
  const isViewedAsTeacher = isTeacherRole(role);
  const examQuery = useExam(examId);
  const scheduleMutation = useScheduleExam(examId);
  const [scheduleError, setScheduleError] = useState<string | null>(null);
  const [scheduleDialogOpen, setScheduleDialogOpen] = useState(false);

  const canReachScheduleAction = canScheduleOrPublishExam(role) || canManageExamsStaff(role);

  // Fix 3 (UX review): scheduling is the only way to leave Draft status —
  // this exam's own copy says so — so it's behind an `AlertDialog`
  // confirmation, same pattern as the Publish page's Fix 2. On failure the
  // dialog stays open so the error is visible without re-opening it.
  const handleSchedule = async () => {
    setScheduleError(null);
    try {
      await scheduleMutation.mutateAsync();
      setScheduleDialogOpen(false);
    } catch (error) {
      setScheduleError(isApiClientError(error) ? error.message : "Could not schedule this exam. Please try again.");
    }
  };

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">Exam Scheduler</h1>
        <p className="text-sm text-muted-foreground">
          Set the exam window, time limit, and linked questions, then schedule it to make it visible to
          students.
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

      <QueryStateBoundary
        query={examQuery}
        loadingLabel="Loading exam…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/teacher/dashboard" }}
      >
        {(exam) => (
          <div className="flex flex-col gap-6">
            <div className="flex flex-col gap-2 rounded-lg border border-border p-4 sm:flex-row sm:items-center sm:justify-between">
              <div>
                <p className="text-sm font-medium text-foreground">{exam.title}</p>
                <p className="text-xs text-muted-foreground">
                  Exam ID: <code className="rounded bg-muted px-1 py-0.5">{exam.id}</code> — copy this to open
                  the Marking Queue or Publish Results screens later.
                </p>
              </div>
              <ExamStatusChip status={exam.status} />
            </div>

            <ExamDraftForm exam={exam} />

            {exam.status === "DRAFT" && canReachScheduleAction ? (
              <div className="flex flex-col gap-2 rounded-lg border border-border p-4">
                <p className="text-sm text-muted-foreground">
                  Scheduling locks the window and questions in and makes this exam visible to enrolled
                  students once its window opens. This is the only way to leave Draft status.
                </p>
                <LiveRegion message={scheduleMutation.isPending ? "Scheduling exam…" : ""} />
                {scheduleError && !scheduleDialogOpen ? (
                  <p role="alert" className="text-sm text-destructive">
                    {scheduleError}
                  </p>
                ) : null}
                <AlertDialog open={scheduleDialogOpen} onOpenChange={setScheduleDialogOpen}>
                  <AlertDialogTrigger render={<Button type="button" className="w-full sm:w-fit" />}>
                    Schedule exam
                  </AlertDialogTrigger>
                  <AlertDialogContent>
                    <AlertDialogHeader>
                      <AlertDialogTitle>Schedule this exam?</AlertDialogTitle>
                      <AlertDialogDescription>
                        This locks the window and linked questions and makes the exam visible to enrolled
                        students once its window opens. This is the only way to leave Draft status.
                      </AlertDialogDescription>
                    </AlertDialogHeader>
                    {scheduleError ? (
                      <Alert variant="destructive">
                        <AlertDescription>{scheduleError}</AlertDescription>
                      </Alert>
                    ) : null}
                    <AlertDialogFooter>
                      <AlertDialogClose render={<Button type="button" variant="outline" />}>Cancel</AlertDialogClose>
                      <Button
                        type="button"
                        onClick={handleSchedule}
                        disabled={scheduleMutation.isPending}
                        aria-busy={scheduleMutation.isPending}
                      >
                        {scheduleMutation.isPending ? "Scheduling…" : "Schedule exam"}
                      </Button>
                    </AlertDialogFooter>
                  </AlertDialogContent>
                </AlertDialog>
              </div>
            ) : null}

            {exam.status !== "DRAFT" ? (
              <div className="flex flex-col gap-2 text-sm sm:flex-row sm:gap-4">
                <Link href={`/teacher/exams/${exam.id}/publish`} className="font-medium text-foreground hover:underline">
                  Go to Results Publishing →
                </Link>
                <Link
                  href={`/teacher/exams/marking?examId=${exam.id}`}
                  className="font-medium text-foreground hover:underline"
                >
                  Go to Marking Queue →
                </Link>
              </div>
            ) : null}
          </div>
        )}
      </QueryStateBoundary>
    </div>
  );
}
