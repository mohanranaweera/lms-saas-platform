"use client";

import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { AlertCircle, Ban } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
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
import { useRevokeEnrollment } from "@/lib/api/enrollments";
import {
  revokeEnrollmentSchema,
  type RevokeEnrollmentFormValues,
} from "@/lib/validation/student-actions";
import { shortId } from "@/lib/format";

interface RevokeEnrollmentDialogProps {
  studentId: string;
  enrollmentId: string;
  courseId: string;
  /**
   * Invoked right after a successful revoke, before the dialog closes — lets
   * the caller surface a brief, distinguishable confirmation (this app has
   * no toast library — see `components/ui/live-region.tsx`'s doc comment),
   * mirroring `students/page.tsx#createdNotice`'s established "brief
   * page-level notice" pattern rather than inventing a new one.
   */
  onSuccess?: () => void;
}

/**
 * Per-enrollment-row "Revoke" action (Wave 3, PAR-03-05 — ADR-016 decision
 * 2: reuses the existing `supersede()` mutation, no ledger/payment write). A
 * mandatory reason is required. Only ever rendered for a `current: true`
 * row (the caller in `enrollments-tab.tsx` is responsible for that — the
 * backend independently re-verifies the row is still current server-side
 * regardless).
 */
export function RevokeEnrollmentDialog({
  studentId,
  enrollmentId,
  courseId,
  onSuccess,
}: RevokeEnrollmentDialogProps) {
  const mutation = useRevokeEnrollment(studentId);
  const [open, setOpen] = useState(false);

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<RevokeEnrollmentFormValues>({
    resolver: zodResolver(revokeEnrollmentSchema),
    defaultValues: { reason: "" },
  });

  const errorMessage = mutation.isError
    ? isApiClientError(mutation.error)
      ? mutation.error.message
      : "Something went wrong. Please try again."
    : null;

  const onSubmit = handleSubmit((values) => {
    mutation.mutate(
      { enrollmentId, reason: values.reason },
      {
        onSuccess: () => {
          reset();
          setOpen(false);
          onSuccess?.();
        },
      }
    );
  });

  return (
    <AlertDialog
      open={open}
      onOpenChange={(nextOpen) => {
        setOpen(nextOpen);
        if (!nextOpen) {
          mutation.reset();
          reset();
        }
      }}
    >
      <AlertDialogTrigger
        render={<Button type="button" variant="destructive" size="sm" aria-label={`Revoke enrollment in ${shortId(courseId)}`} />}
      >
        <Ban aria-hidden="true" />
        Revoke
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Revoke enrollment in {shortId(courseId)}?</AlertDialogTitle>
          <AlertDialogDescription>
            This ends the student&apos;s access to this course. No refund is issued automatically
            — a separate refund action remains available on the payment if one is warranted.
          </AlertDialogDescription>
        </AlertDialogHeader>
        {errorMessage ? (
          <Alert variant="destructive">
            <AlertCircle aria-hidden="true" />
            <AlertDescription>{errorMessage}</AlertDescription>
          </Alert>
        ) : null}
        <form onSubmit={onSubmit} className="flex flex-col gap-1.5" noValidate>
          <Label htmlFor={`revoke-reason-${enrollmentId}`}>
            Reason<span className="font-normal text-muted-foreground"> (required)</span>
          </Label>
          <Textarea
            id={`revoke-reason-${enrollmentId}`}
            aria-required="true"
            aria-invalid={errors.reason ? true : undefined}
            aria-describedby={errors.reason ? `revoke-reason-${enrollmentId}-error` : undefined}
            placeholder="e.g. Refund processed outside the normal flow"
            {...register("reason")}
          />
          {errors.reason ? (
            <p id={`revoke-reason-${enrollmentId}-error`} role="alert" className="text-xs text-destructive">
              {errors.reason.message}
            </p>
          ) : null}
        </form>
        <AlertDialogFooter>
          <AlertDialogClose render={<Button type="button" variant="outline" />}>Cancel</AlertDialogClose>
          <Button
            type="button"
            variant="destructive"
            onClick={() => void onSubmit()}
            disabled={mutation.isPending}
            aria-busy={mutation.isPending}
          >
            {mutation.isPending ? "Revoking…" : "Revoke"}
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
