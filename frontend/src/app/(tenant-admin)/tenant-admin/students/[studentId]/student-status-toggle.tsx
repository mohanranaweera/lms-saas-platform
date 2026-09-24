"use client";

import { useState } from "react";
import { AlertCircle, Ban, CheckCircle2 } from "lucide-react";
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
import {
  useActivateStudent,
  useDeactivateStudent,
  type StudentResponse,
} from "@/lib/api/students";

/**
 * Activate/Deactivate toggle (Wave 3, PAR-03-05) — `POST
 * /v1/students/{id}/activate|deactivate`, staff `STUDENTS`/`CREATE_EDIT`
 * (server-enforced; this button's visibility is UX convenience only, per
 * `.claude/rules/frontend.md`). Follows `teacher-decision-dialog.tsx`'s exact
 * `AlertDialog` + `useMutation` + controlled-`open` + inline-error shape.
 */
interface StudentStatusToggleProps {
  student: StudentResponse;
  /**
   * Invoked right after a successful mutation, before the dialog closes —
   * lets the caller surface a brief, distinguishable confirmation (this app
   * has no toast library — see `components/ui/live-region.tsx`'s doc
   * comment), mirroring `students/page.tsx#createdNotice`'s established
   * "brief page-level notice" pattern rather than inventing a new one.
   */
  onSuccess?: (message: string) => void;
}

export function StudentStatusToggle({ student, onSuccess }: StudentStatusToggleProps) {
  const activateMutation = useActivateStudent(student.id);
  const deactivateMutation = useDeactivateStudent(student.id);
  const isActive = student.status === "ACTIVE";
  const mutation = isActive ? deactivateMutation : activateMutation;

  const [open, setOpen] = useState(false);

  const verb = isActive ? "Deactivate" : "Activate";
  const pendingLabel = isActive ? "Deactivating…" : "Activating…";
  const Icon = isActive ? Ban : CheckCircle2;

  const errorMessage = mutation.isError
    ? isApiClientError(mutation.error)
      ? mutation.error.message
      : "Something went wrong. Please try again."
    : null;

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
      <AlertDialogTrigger render={<Button type="button" variant={isActive ? "destructive" : "default"} />}>
        <Icon aria-hidden="true" />
        {verb}
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>
            {verb} {student.name}?
          </AlertDialogTitle>
          <AlertDialogDescription>
            {isActive
              ? `${student.name} (${student.email}) will no longer be able to sign in until reactivated.`
              : `${student.name} (${student.email}) will be able to sign in again.`}
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
            variant={isActive ? "destructive" : "default"}
            onClick={() =>
              mutation.mutate(undefined, {
                onSuccess: () => {
                  onSuccess?.(
                    isActive ? `${student.name} was deactivated.` : `${student.name} was activated.`
                  );
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
