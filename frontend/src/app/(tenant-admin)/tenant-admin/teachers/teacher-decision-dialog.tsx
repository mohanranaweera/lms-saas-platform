"use client";

import { useState } from "react";
import { AlertCircle, Check, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Alert, AlertDescription } from "@/components/ui/alert";
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
import { isApiClientError } from "@/lib/api/error";
import { useApproveTeacher, useRejectTeacher, type Teacher } from "@/lib/api/teachers";

interface TeacherDecisionDialogProps {
  teacher: Teacher;
  action: "approve" | "reject";
  /**
   * "icon" renders an icon-only trigger (desktop/tablet table row actions,
   * pointer-driven — needs an instance-specific `aria-label` naming the
   * teacher, per `.claude/rules/ui-ux.md` §4). "full" renders an icon +
   * visible label at `size="lg"` (36px, the largest token this design system
   * has) so it's both a bigger, more differentiable tap target than the
   * icon-only pair and clearly reads "Approve"/"Reject" — used on the mobile
   * card list (where two adjacent small icon buttons risk a mis-tap on a
   * consequential action) and the teacher detail page.
   */
  triggerVariant: "icon" | "full";
  /** Passed through to the trigger `Button` — e.g. `flex-1` so two "full" triggers split the available width evenly on mobile. */
  className?: string;
}

/**
 * Approve/Reject confirmation, shared by the teacher list (row actions) and
 * teacher detail page. Follows `components/auth/logout-control.tsx`'s
 * AlertDialog + `useMutation` pattern exactly: trigger button, dialog naming
 * the specific teacher, Cancel + confirm actions, `aria-busy`/pending-label
 * swap, and the mutation error surfaced inline (never silently dropped).
 *
 * `POST /v1/teachers/{id}/approve|reject` only accepts a PENDING teacher and
 * only from a literal Tenant Admin session (403 otherwise) — callers of this
 * component are responsible for the PENDING + role gating (UX convenience
 * only; the backend independently re-enforces both, see contract in
 * `lib/api/teachers.ts`).
 */
export function TeacherDecisionDialog({
  teacher,
  action,
  triggerVariant,
  className,
}: TeacherDecisionDialogProps) {
  const approveMutation = useApproveTeacher();
  const rejectMutation = useRejectTeacher();
  const mutation = action === "approve" ? approveMutation : rejectMutation;

  const verb = action === "approve" ? "Approve" : "Reject";
  const pendingLabel = action === "approve" ? "Approving…" : "Rejecting…";
  const Icon = action === "approve" ? Check : X;

  const errorMessage = mutation.isError
    ? isApiClientError(mutation.error)
      ? mutation.error.message
      : "Something went wrong. Please try again."
    : null;

  // Controlled `open` state: the confirm button isn't an `AlertDialogClose`
  // (it must stay open and show the inline error on failure, per the 403
  // test above), so nothing closes the dialog on its own — without this, a
  // *successful* approve/reject leaves the dialog sitting open indefinitely
  // with no feedback beyond the confirm button reverting from "Approving…"
  // back to "Approve". Closed from the mutation's own `onSuccess` callback
  // (not a `mutation.isSuccess` effect — that resets one render late).
  const [open, setOpen] = useState(false);

  return (
    <AlertDialog
      open={open}
      onOpenChange={(nextOpen) => {
        setOpen(nextOpen);
        if (!nextOpen) {
          mutation.reset();
        }
      }}
    >
      <AlertDialogTrigger
        render={
          triggerVariant === "icon" ? (
            <Button
              type="button"
              variant="outline"
              size="icon-sm"
              aria-label={`${verb} teacher ${teacher.name}`}
              className={className}
            />
          ) : (
            <Button
              type="button"
              variant={action === "approve" ? "default" : "destructive"}
              size="lg"
              className={className}
            />
          )
        }
      >
        <Icon aria-hidden="true" />
        {triggerVariant === "full" ? verb : null}
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>
            {verb} {teacher.name}?
          </AlertDialogTitle>
          <AlertDialogDescription>
            {action === "approve"
              ? `${teacher.name} (${teacher.email}) will be approved and able to sign in as a teacher.`
              : `${teacher.name} (${teacher.email}) will be rejected. This teacher account will not be able to sign in.`}
          </AlertDialogDescription>
        </AlertDialogHeader>
        {errorMessage ? (
          <Alert variant="destructive">
            <AlertCircle aria-hidden="true" />
            <AlertDescription>{errorMessage}</AlertDescription>
          </Alert>
        ) : null}
        <AlertDialogFooter>
          <AlertDialogClose render={<Button type="button" variant="outline" />}>
            Cancel
          </AlertDialogClose>
          <Button
            type="button"
            variant={action === "approve" ? "default" : "destructive"}
            onClick={() => mutation.mutate(teacher.id, { onSuccess: () => setOpen(false) })}
            disabled={mutation.isPending}
            aria-busy={mutation.isPending}
          >
            {mutation.isPending ? pendingLabel : verb}
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
