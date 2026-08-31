"use client";

import { useRef, useState, type FormEvent } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { AlertCircle, XCircle } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
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
import { LiveRegion } from "@/components/ui/live-region";
import { isApiClientError } from "@/lib/api/error";
import { useRejectReactivationRequest, type ReactivationRequestResponse } from "@/lib/api/enrollments";
import { reactivationRejectSchema, type ReactivationRejectFormValues } from "@/lib/validation/reactivation";

/**
 * Reject confirmation for the Reactivation Request Detail screen (MVP-012).
 * Rendering this component at all is gated by the caller
 * (`canApproveReactivation(role)` && `status === "SUBMITTED"` — UX
 * convenience only); `POST /api/v1/reactivation-requests/{id}/reject`
 * independently re-enforces the literal Tenant-Admin-only + `SUBMITTED`
 * requirement server-side, and a forced 403/400/422 is surfaced inline,
 * dialog kept open. `REJECTED` is a one-directional terminal transition —
 * there is no reversal endpoint. Mirrors `reject-slip-dialog.tsx` almost
 * verbatim.
 */
export function RejectReactivationDialog({
  reactivationRequest,
}: {
  reactivationRequest: ReactivationRequestResponse;
}) {
  const [open, setOpen] = useState(false);
  const [pageError, setPageError] = useState<string | null>(null);
  const mutation = useRejectReactivationRequest(reactivationRequest.id);

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<ReactivationRejectFormValues>({
    resolver: zodResolver(reactivationRejectSchema),
    defaultValues: { reason: "" },
  });

  const onSubmit = handleSubmit(async (values) => {
    setPageError(null);
    try {
      await mutation.mutateAsync({ reason: values.reason.trim() });
      setOpen(false);
      reset();
    } catch (error) {
      setPageError(
        isApiClientError(error) ? error.message : "Something went wrong. Please try again."
      );
    }
  });

  // See `reject-slip-dialog.tsx`'s identical comment for the double-submit
  // race this ref guards against and why it lives on the raw `onSubmit` prop
  // rather than inside the `handleSubmit(...)`-wrapped callback.
  const isSubmittingRef = useRef(false);

  function handleFormSubmit(event: FormEvent<HTMLFormElement>) {
    if (isSubmittingRef.current) {
      event.preventDefault();
      return;
    }
    isSubmittingRef.current = true;
    void onSubmit(event).finally(() => {
      isSubmittingRef.current = false;
    });
  }

  return (
    <AlertDialog
      open={open}
      onOpenChange={(nextOpen, eventDetails) => {
        if (eventDetails.reason === "escape-key") {
          eventDetails.cancel();
          return;
        }
        setOpen(nextOpen);
        if (!nextOpen) {
          mutation.reset();
          setPageError(null);
          reset();
          isSubmittingRef.current = false;
        }
      }}
    >
      <AlertDialogTrigger render={<Button type="button" variant="outline" size="sm" />}>
        <XCircle aria-hidden="true" />
        Reject
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Reject reactivation request</AlertDialogTitle>
          <AlertDialogDescription>
            This is a one-directional, terminal decision — there is no reopen/reversal path. The
            student&apos;s access stays expired.
          </AlertDialogDescription>
        </AlertDialogHeader>

        {pageError ? (
          <Alert variant="destructive">
            <AlertCircle aria-hidden="true" />
            <AlertDescription>{pageError}</AlertDescription>
          </Alert>
        ) : null}

        <form
          className="flex flex-col gap-3"
          noValidate
          aria-busy={mutation.isPending}
          onSubmit={handleFormSubmit}
        >
          <LiveRegion message={mutation.isPending ? "Submitting rejection…" : ""} />

          <div className="flex flex-col gap-1.5">
            <Label htmlFor={`reactivation-reject-reason-${reactivationRequest.id}`}>Reason</Label>
            <Input
              id={`reactivation-reject-reason-${reactivationRequest.id}`}
              autoComplete="off"
              disabled={mutation.isPending}
              aria-invalid={!!errors.reason}
              aria-describedby={
                errors.reason ? `reactivation-reject-reason-${reactivationRequest.id}-error` : undefined
              }
              {...register("reason")}
            />
            {errors.reason ? (
              <p
                id={`reactivation-reject-reason-${reactivationRequest.id}-error`}
                role="alert"
                className="text-xs text-destructive"
              >
                {errors.reason.message}
              </p>
            ) : null}
          </div>

          <AlertDialogFooter>
            <AlertDialogClose
              render={<Button type="button" variant="outline" disabled={mutation.isPending} />}
            >
              Cancel
            </AlertDialogClose>
            <Button
              type="submit"
              variant="destructive"
              disabled={mutation.isPending}
              aria-busy={mutation.isPending}
            >
              {mutation.isPending ? "Submitting…" : "Reject"}
            </Button>
          </AlertDialogFooter>
        </form>
      </AlertDialogContent>
    </AlertDialog>
  );
}
