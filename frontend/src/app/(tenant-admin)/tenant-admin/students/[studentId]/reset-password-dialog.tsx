"use client";

import { useState } from "react";
import { AlertCircle, Check, Copy, KeyRound } from "lucide-react";
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
import { useResetStudentPassword, type StudentResponse } from "@/lib/api/students";

/**
 * "Reset password" action (Wave 3, PAR-03-05) — `POST
 * /v1/students/{id}/reset-password`, staff `STUDENTS`/`CREATE_EDIT`. Shows
 * the one-time temporary password in this dialog's own local state only —
 * never written to React Query's cache, never logged, never persisted beyond
 * this component unmounting (closing the dialog discards it for good), per
 * `.claude/rules/security.md`'s password-reset requirement and root
 * `CLAUDE.md`'s "never activate enrollment from a frontend success page"
 * sibling principle (a value only the backend generated and returned once is
 * treated with the same one-shot discipline).
 */
export function ResetPasswordDialog({ student }: { student: StudentResponse }) {
  const mutation = useResetStudentPassword(student.id);
  const [open, setOpen] = useState(false);
  const [copied, setCopied] = useState(false);

  const errorMessage = mutation.isError
    ? isApiClientError(mutation.error)
      ? mutation.error.message
      : "Something went wrong. Please try again."
    : null;

  async function handleCopy(password: string) {
    try {
      await navigator.clipboard.writeText(password);
      setCopied(true);
    } catch {
      // Clipboard access can fail (permissions, insecure context) — the
      // password remains visible on screen for manual copy either way, so
      // this is a silent no-op rather than a surfaced error.
    }
  }

  return (
    <AlertDialog
      open={open}
      onOpenChange={(nextOpen) => {
        // A reset is in flight — dismissing now (Escape/overlay-click/the
        // built-in X close button all funnel through this same callback)
        // would leave the user thinking they cancelled while the request
        // still completes in the background, per `create-student-sheet.tsx`/
        // `enroll-student-sheet.tsx`'s identical guard.
        if (!nextOpen && mutation.isPending) {
          return;
        }
        setOpen(nextOpen);
        if (!nextOpen) {
          mutation.reset();
          setCopied(false);
        }
      }}
    >
      <AlertDialogTrigger render={<Button type="button" variant="outline" />}>
        <KeyRound aria-hidden="true" />
        Reset password
      </AlertDialogTrigger>
      <AlertDialogContent>
        {mutation.data ? (
          <>
            <AlertDialogHeader>
              <AlertDialogTitle>Temporary password</AlertDialogTitle>
              <AlertDialogDescription>
                Share this one-time temporary password with {student.name} outside this app.
                {student.name} must change it on next sign-in. This password will not be shown
                again once you close this dialog.
              </AlertDialogDescription>
            </AlertDialogHeader>
            <div className="flex items-center gap-2 rounded-md border border-border bg-muted/40 px-3 py-2">
              <code className="flex-1 overflow-x-auto text-sm font-medium text-foreground">
                {mutation.data.temporaryPassword}
              </code>
              <Button
                type="button"
                variant="outline"
                size="icon-sm"
                aria-label="Copy temporary password"
                onClick={() => void handleCopy(mutation.data!.temporaryPassword)}
              >
                {copied ? <Check aria-hidden="true" /> : <Copy aria-hidden="true" />}
              </Button>
            </div>
            {copied ? (
              <p role="status" className="text-xs text-muted-foreground">
                Copied to clipboard.
              </p>
            ) : null}
            <AlertDialogFooter>
              <AlertDialogClose render={<Button type="button" />}>Done</AlertDialogClose>
            </AlertDialogFooter>
          </>
        ) : (
          <>
            <AlertDialogHeader>
              <AlertDialogTitle>Reset {student.name}&apos;s password?</AlertDialogTitle>
              <AlertDialogDescription>
                A new temporary password will be generated. {student.name} will need to change it
                on their next sign-in.
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
                onClick={() => mutation.mutate()}
                disabled={mutation.isPending}
                aria-busy={mutation.isPending}
              >
                {mutation.isPending ? "Resetting…" : "Reset password"}
              </Button>
            </AlertDialogFooter>
          </>
        )}
      </AlertDialogContent>
    </AlertDialog>
  );
}
