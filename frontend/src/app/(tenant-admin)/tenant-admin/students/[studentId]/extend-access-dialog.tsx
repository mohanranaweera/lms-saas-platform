"use client";

import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { AlertCircle, CalendarPlus } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
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
import { useAccessExtensions, useExtendAccess } from "@/lib/api/access-policy";
import type { EnrollmentHistoryEntryResponse } from "@/lib/api/enrollments";
import { formatDateTime, shortId } from "@/lib/format";
import {
  endOfLocalDayIso,
  extendAccessSchema,
  type ExtendAccessFormValues,
} from "@/lib/validation/devices-access";

/**
 * Wave 10 manual access extension (ADR-022, product-owner decision D1): a
 * Tenant Admin moves the end of a current, time-limited enrollment - also an
 * already expired one - with a mandatory reason. Nothing about payments or the
 * original enrollment changes; the backend writes an append-only extension row
 * and the audit entries, and re-checks `ACCESS_EXPIRY`/`CREATE_EDIT`.
 */
export function ExtendAccessDialog({
  studentId,
  enrollment,
  onSuccess,
}: {
  studentId: string;
  enrollment: EnrollmentHistoryEntryResponse;
  onSuccess: (message: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const mutation = useExtendAccess(studentId);
  const history = useAccessExtensions(enrollment.enrollmentId, open);
  const idBase = `extend-${enrollment.enrollmentId}`;
  const currentEnd = enrollment.effectiveExpiresAt ?? enrollment.accessExpiresAt;

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<ExtendAccessFormValues>({
    resolver: zodResolver(extendAccessSchema),
    defaultValues: { newExpiryDate: "", reason: "" },
  });

  const errorMessage = mutation.isError
    ? isApiClientError(mutation.error)
      ? (mutation.error.fieldErrors[0]?.message ?? mutation.error.message)
      : "Something went wrong. Please try again."
    : null;

  const onSubmit = handleSubmit((values) => {
    mutation.mutate(
      {
        enrollmentId: enrollment.enrollmentId,
        newExpiresAt: endOfLocalDayIso(values.newExpiryDate),
        reason: values.reason,
      },
      {
        onSuccess: (extension) => {
          reset();
          setOpen(false);
          onSuccess(`Access to ${shortId(enrollment.courseId)} now runs until ${formatDateTime(extension.newExpiresAt)}.`);
        },
      }
    );
  });

  return (
    <AlertDialog
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        if (!next) {
          mutation.reset();
          reset();
        }
      }}
    >
      <AlertDialogTrigger
        render={
          <Button
            type="button"
            variant="outline"
            size="sm"
            aria-label={`Extend access to ${shortId(enrollment.courseId)}`}
          />
        }
      >
        <CalendarPlus aria-hidden="true" />
        Extend
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Extend access to {shortId(enrollment.courseId)}</AlertDialogTitle>
          <AlertDialogDescription>
            {currentEnd ? `Access currently ends ${formatDateTime(currentEnd)}. ` : ""}
            {enrollment.accessActive
              ? "The student keeps access until the new date."
              : "Access has ended - extending it restores access now, without a new payment."}
          </AlertDialogDescription>
        </AlertDialogHeader>
        {errorMessage ? (
          <Alert variant="destructive">
            <AlertCircle aria-hidden="true" />
            <AlertDescription>{errorMessage}</AlertDescription>
          </Alert>
        ) : null}
        <form onSubmit={onSubmit} className="flex flex-col gap-3" noValidate>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor={`${idBase}-date`}>New end date</Label>
            <Input
              id={`${idBase}-date`}
              type="date"
              aria-required="true"
              aria-invalid={errors.newExpiryDate ? true : undefined}
              aria-describedby={errors.newExpiryDate ? `${idBase}-date-error` : undefined}
              {...register("newExpiryDate")}
            />
            {errors.newExpiryDate ? (
              <p id={`${idBase}-date-error`} role="alert" className="text-xs text-destructive">
                {errors.newExpiryDate.message}
              </p>
            ) : null}
          </div>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor={`${idBase}-reason`}>
              Reason<span className="font-normal text-muted-foreground"> (required)</span>
            </Label>
            <Textarea
              id={`${idBase}-reason`}
              aria-required="true"
              aria-invalid={errors.reason ? true : undefined}
              aria-describedby={errors.reason ? `${idBase}-reason-error` : undefined}
              placeholder="e.g. Medical leave during the term"
              {...register("reason")}
            />
            {errors.reason ? (
              <p id={`${idBase}-reason-error`} role="alert" className="text-xs text-destructive">
                {errors.reason.message}
              </p>
            ) : null}
          </div>
        </form>
        {history.data && history.data.length > 0 ? (
          <div className="flex flex-col gap-1">
            <p className="text-xs font-medium text-foreground">Previous extensions</p>
            <ul className="flex flex-col gap-1 text-xs text-muted-foreground">
              {history.data.map((item) => (
                <li key={item.id}>
                  {formatDateTime(item.grantedAt)}: {formatDateTime(item.previousExpiresAt)} to{" "}
                  {formatDateTime(item.newExpiresAt)} ({item.reason})
                </li>
              ))}
            </ul>
          </div>
        ) : null}
        <AlertDialogFooter>
          <AlertDialogClose render={<Button type="button" variant="outline" />}>Cancel</AlertDialogClose>
          <Button
            type="button"
            onClick={() => void onSubmit()}
            disabled={mutation.isPending}
            aria-busy={mutation.isPending}
          >
            {mutation.isPending ? "Extending…" : "Extend access"}
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
