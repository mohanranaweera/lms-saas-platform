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
import { useRejectSlip, type PaymentSlipResponse } from "@/lib/api/payment-slips";
import { slipRejectSchema, type SlipRejectFormValues } from "@/lib/validation/payment-slip";

/**
 * Reject confirmation for the Slip Detail screen (SLIP-3). Rendering this
 * component at all is gated by the caller (`canReviewSlips(role)` &&
 * `slip.status === "UNDER_REVIEW"` — UX convenience only); `POST
 * /api/v1/payment-slips/{id}/reject` independently re-enforces the literal
 * Tenant Admin/Finance Staff + `UNDER_REVIEW` requirement server-side, and a
 * forced 403/400/409 is surfaced inline, dialog kept open — mirrors
 * `refund-dialog.tsx`'s exact discipline. `REJECTED` is a one-directional
 * terminal transition; there is no reversal endpoint.
 */
export function RejectSlipDialog({ slip }: { slip: PaymentSlipResponse }) {
  const [open, setOpen] = useState(false);
  const [pageError, setPageError] = useState<string | null>(null);
  const mutation = useRejectSlip(slip.id);

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<SlipRejectFormValues>({
    resolver: zodResolver(slipRejectSchema),
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

  // `disabled={mutation.isPending}` alone isn't a hard guard here: a fast
  // native double-click can fire a second submission before React has
  // committed the re-render that flips the button to disabled — the same
  // race proven (and fixed) in `approve-slip-dialog.tsx`. `onSubmit` above
  // is RHF-driven (`handleSubmit(...)`), and reading/writing a ref *inside*
  // the callback passed to `handleSubmit` trips React Compiler's ref-safety
  // lint rule. This wrapper sidesteps that: it is assigned directly to the
  // `<form>` element's `onSubmit` prop — a separate function that is never
  // itself passed into `handleSubmit` — so the ref read/write happens
  // purely at the DOM-event-handler level, which the rule allows.
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
          // Defensive only — see `approve-slip-dialog.tsx`'s identical
          // cleanup for the rationale (no current path reaches here while
          // pending, since Escape/outside-press/Cancel are all blocked).
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
          <AlertDialogTitle>Reject slip {slip.referenceNumber}</AlertDialogTitle>
          <AlertDialogDescription>
            This is a one-directional, terminal decision — there is no reopen/reversal path.
            Enrollment stays inactive.
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
            <Label htmlFor={`reject-reason-${slip.id}`}>Reason</Label>
            <Input
              id={`reject-reason-${slip.id}`}
              autoComplete="off"
              disabled={mutation.isPending}
              aria-invalid={!!errors.reason}
              aria-describedby={errors.reason ? `reject-reason-${slip.id}-error` : undefined}
              {...register("reason")}
            />
            {errors.reason ? (
              <p id={`reject-reason-${slip.id}-error`} role="alert" className="text-xs text-destructive">
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
