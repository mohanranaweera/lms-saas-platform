"use client";

import { useState } from "react";
import { AlertCircle, Ban, RotateCcw } from "lucide-react";
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
import { useSuspendTeacher, useReactivateTeacher, type Teacher } from "@/lib/api/teachers";

interface TeacherSuspendDialogProps {
  teacher: Teacher;
  action: "suspend" | "reactivate";
  /** Same two variants as `teacher-decision-dialog.tsx` — see that component's doc comment for the rationale. */
  triggerVariant: "icon" | "full";
  className?: string;
  /**
   * Invoked right after a successful mutation, before the dialog closes.
   * Callers use this to surface a brief, distinguishable confirmation (this
   * app has no toast library — see `components/ui/live-region.tsx`'s doc
   * comment) alongside the status badge that updates in place, mirroring
   * `students/page.tsx#createdNotice`'s "brief page-level notice" pattern.
   */
  onSuccess?: () => void;
}

/**
 * Suspend/Reactivate confirmation (Wave 3, PAR-04-04) — same gating pattern
 * and dialog shape as `teacher-decision-dialog.tsx`'s Approve/Reject (same
 * `TEACHERS`/`CREATE_EDIT` + `requireTenantAdmin()` server-side gate, per the
 * wave-03 plan §4). `suspend` only legal from `APPROVED`; `reactivate` only
 * legal from `SUSPENDED` (409 otherwise, enforced server-side — this
 * component's callers are responsible for only rendering the applicable
 * action for the teacher's current status).
 */
export function TeacherSuspendDialog({
  teacher,
  action,
  triggerVariant,
  className,
  onSuccess,
}: TeacherSuspendDialogProps) {
  const suspendMutation = useSuspendTeacher();
  const reactivateMutation = useReactivateTeacher();
  const mutation = action === "suspend" ? suspendMutation : reactivateMutation;

  const verb = action === "suspend" ? "Suspend" : "Reactivate";
  const pendingLabel = action === "suspend" ? "Suspending…" : "Reactivating…";
  const Icon = action === "suspend" ? Ban : RotateCcw;

  const errorMessage = mutation.isError
    ? isApiClientError(mutation.error)
      ? mutation.error.message
      : "Something went wrong. Please try again."
    : null;

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
              variant={action === "suspend" ? "destructive" : "default"}
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
            {action === "suspend"
              ? `${teacher.name} (${teacher.email}) will be blocked from signing in until reactivated.`
              : `${teacher.name} (${teacher.email}) will be able to sign in again.`}
          </AlertDialogDescription>
        </AlertDialogHeader>
        {errorMessage ? (
          <Alert variant="destructive">
            <AlertCircle aria-hidden="true" />
            <AlertDescription>{errorMessage}</AlertDescription>
          </Alert>
        ) : null}
        <AlertDialogFooter>
          <AlertDialogClose render={<Button type="button" variant="outline" />}>Cancel</AlertDialogClose>
          <Button
            type="button"
            variant={action === "suspend" ? "destructive" : "default"}
            onClick={() =>
              mutation.mutate(teacher.id, {
                onSuccess: () => {
                  onSuccess?.();
                  setOpen(false);
                },
              })
            }
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
