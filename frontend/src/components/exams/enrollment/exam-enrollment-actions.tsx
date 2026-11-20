"use client";

import { useState } from "react";
import { BadgeDollarSign, RefreshCw, UserMinus, UserPlus, Users } from "lucide-react";
import { Button } from "@/components/ui/button";
import { AdminToast, useAdminToast } from "@/components/courses/admin/admin-toast";
import { mutationErrorMessage } from "@/components/courses/admin/confirm-delete-dialog";
import { EnrollStudentsDialog } from "@/components/exams/enrollment/enroll-students-dialog";
import {
  HAS_ATTEMPT_MESSAGE,
  isHasAttemptError,
  useRemoveExamEnrollment,
  useSyncExamPaymentEnrollments,
} from "@/lib/api/exam-enrollment";
import type { ExamDetail } from "@/lib/api/exam-config";

function plural(count: number, word: string): string {
  return `${count} ${word}${count === 1 ? "" : "s"}`;
}

/**
 * Staff enrollment controls for one exam (Slice C, on the manage page until
 * Slice D's student table): "Enroll students", and "Sync payments" when the
 * exam auto-enrolls on payment. Shown only to authoring roles; every call is
 * re-authorized server-side.
 */
export function ExamEnrollmentActions({
  exam,
  variant = "card",
}: {
  exam: Pick<ExamDetail, "id" | "name" | "enrollAllStudents" | "autoEnrollOnPayment">;
  /** `toolbar`: badge + buttons only (Slice D manage page); `card`: the Slice C stand-alone section. */
  variant?: "card" | "toolbar";
}) {
  const [dialogOpen, setDialogOpen] = useState(false);
  const toast = useAdminToast();
  const sync = useSyncExamPaymentEnrollments(exam.id);

  async function runSync() {
    try {
      const result = await sync.mutateAsync();
      toast.show({
        kind: "success",
        text:
          result.added > 0
            ? `${plural(result.added, "student")} enrolled from confirmed payments.`
            : "No new students matched the payment criteria.",
      });
    } catch (error) {
      toast.show({ kind: "error", text: mutationErrorMessage(error) });
    }
  }

  const badge = exam.autoEnrollOnPayment ? (
    <span className="inline-flex w-fit items-center gap-1 rounded-full bg-primary/10 px-2 py-0.5 text-xs font-medium text-primary" data-testid="auto-enroll-badge">
      <BadgeDollarSign className="size-3" aria-hidden="true" />
      Auto-enroll on payment
    </span>
  ) : null;
  const buttons = (
    <div className="flex flex-col gap-2 sm:flex-row">
      {exam.autoEnrollOnPayment ? (
        <Button
          type="button"
          variant="outline"
          onClick={() => void runSync()}
          disabled={sync.isPending}
          aria-busy={sync.isPending}
          data-testid="exam-sync-payments"
        >
          <RefreshCw className={sync.isPending ? "animate-spin motion-reduce:animate-none" : undefined} aria-hidden="true" />
          {sync.isPending ? "Syncing…" : "Sync payments"}
        </Button>
      ) : null}
      {!exam.enrollAllStudents ? (
        <Button type="button" onClick={() => setDialogOpen(true)} data-testid="exam-enroll-open">
          <UserPlus aria-hidden="true" />
          Enroll students
        </Button>
      ) : null}
    </div>
  );
  const dialog = (
    <>
      <EnrollStudentsDialog
        examId={exam.id}
        examName={exam.name}
        open={dialogOpen}
        onOpenChange={setDialogOpen}
        onEnrolled={(result) =>
          toast.show({
            kind: "success",
            text:
              result.alreadyEnrolled > 0
                ? `${plural(result.added, "student")} enrolled. ${plural(result.alreadyEnrolled, "student")} ${result.alreadyEnrolled === 1 ? "was" : "were"} already enrolled.`
                : `${plural(result.added, "student")} enrolled.`,
          })
        }
      />
      <AdminToast message={toast.message} onDismiss={toast.dismiss} />
    </>
  );

  if (variant === "toolbar") {
    return (
      <div className="flex min-w-0 flex-col gap-2 sm:flex-row sm:items-center sm:justify-between" data-testid="exam-enrollment-actions">
        <p className="flex min-w-0 flex-wrap items-center gap-2 text-sm text-muted-foreground">
          {badge}
          <span>
            {exam.enrollAllStudents
              ? "Every student of the course is enrolled automatically until the exam ends."
              : exam.autoEnrollOnPayment
                ? "Students with a matching confirmed payment are enrolled automatically."
                : "Only students you enroll can take this exam."}
          </span>
        </p>
        {buttons}
        {dialog}
      </div>
    );
  }

  return (
    <section
      aria-labelledby="exam-enrollment-title"
      className="flex flex-col gap-3 rounded-xl bg-card p-4 ring-1 ring-foreground/10"
      data-testid="exam-enrollment-actions"
    >
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex min-w-0 flex-col gap-1">
          <h2 id="exam-enrollment-title" className="flex items-center gap-2 text-base font-semibold text-foreground">
            <Users className="size-4 text-primary" aria-hidden="true" />
            Enrollment
          </h2>
          <p className="text-sm text-muted-foreground">
            {exam.enrollAllStudents
              ? "Every student of the course is enrolled automatically until the exam ends."
              : exam.autoEnrollOnPayment
                ? "Students are enrolled automatically when a matching payment is confirmed. You can also add students yourself."
                : "Only students you enroll can take this exam."}
          </p>
          {badge}
        </div>
        {buttons}
      </div>
      {dialog}
    </section>
  );
}

/**
 * Remove one student from the exam — for Slice D's enrolled-students table.
 * Disabled when the row already shows an attempt; a 409 `HAS_ATTEMPT` from the
 * server (the authority) disables it too and explains why.
 */
export function RemoveEnrollmentButton({
  examId,
  studentId,
  studentName,
  hasAttempt,
  onRemoved,
  onError,
}: {
  examId: string;
  studentId: string;
  studentName: string;
  hasAttempt: boolean;
  onRemoved: () => void;
  onError: (message: string) => void;
}) {
  const remove = useRemoveExamEnrollment(examId);
  const [blocked, setBlocked] = useState(false);
  const disabled = hasAttempt || blocked || remove.isPending;

  async function run() {
    try {
      await remove.mutateAsync(studentId);
      onRemoved();
    } catch (error) {
      if (isHasAttemptError(error)) {
        setBlocked(true);
        onError(HAS_ATTEMPT_MESSAGE);
        return;
      }
      onError(mutationErrorMessage(error));
    }
  }

  return (
    <Button
      type="button"
      variant="ghost"
      size="icon-sm"
      onClick={() => void run()}
      disabled={disabled}
      aria-label={hasAttempt || blocked ? `Can't remove ${studentName}: ${HAS_ATTEMPT_MESSAGE}` : `Remove ${studentName} from this exam`}
      title={hasAttempt || blocked ? HAS_ATTEMPT_MESSAGE : undefined}
      data-testid="exam-remove-enrollment"
    >
      <UserMinus aria-hidden="true" />
    </Button>
  );
}
