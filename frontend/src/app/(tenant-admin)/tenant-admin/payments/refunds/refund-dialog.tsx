"use client";

import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { AlertCircle, RotateCcw } from "lucide-react";
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
import { useCreateRefund } from "@/lib/api/payments";
import type { LedgerHistoryEntryResponse } from "@/lib/api/ledger";
import {
  refundSchema,
  REFUND_AMOUNT_HELPER_TEXT,
  type RefundFormValues,
} from "@/lib/validation/refund";

/**
 * Refund confirmation, following
 * `(tenant-admin)/tenant-admin/teachers/teacher-decision-dialog.tsx`'s
 * `AlertDialog` + `useMutation` pattern: trigger button, dialog naming the
 * specific payment/order/amount, an inline RHF+Zod form, and the mutation
 * error surfaced inline (never silently dropped) rather than closing the
 * dialog on failure.
 *
 * Rendering this component at all is gated by the caller
 * (`canProcessRefunds(role)` — UX convenience only); `POST
 * /api/v1/payments/{id}/refunds` independently re-enforces the literal
 * Tenant Admin/Finance Staff requirement server-side (403 otherwise), and a
 * 403 surfaces here exactly like any other mutation failure, inline, dialog
 * kept open.
 */
export function RefundDialog({ entry }: { entry: LedgerHistoryEntryResponse }) {
  // Rows this dialog is rendered for are always `PAYMENT_CONFIRMED` entries
  // (see refunds `page.tsx`'s filter), which always carry a non-null
  // `paymentId` in practice — but that's an assumption about the caller, not
  // something the type system can see. Hooks must still run unconditionally
  // on every render (React's rules-of-hooks), so the runtime guard below sits
  // *after* every hook call and only skips the render output — `paymentId`
  // falls back to `""` purely to give `useCreateRefund` a well-typed
  // argument; that mutation can never actually fire once this returns `null`.
  const paymentId = entry.paymentId;

  const [open, setOpen] = useState(false);
  const [pageError, setPageError] = useState<string | null>(null);
  // A stable key for this dialog-open, sent with every submit so a
  // resubmission (e.g. retrying after a slow/ambiguous network response)
  // replays the original refund server-side instead of creating a second
  // one — regenerated on each fresh open (see `onOpenChange` below), never
  // reused across two genuinely separate refund attempts.
  const [idempotencyKey, setIdempotencyKey] = useState<string>(() => crypto.randomUUID());
  const mutation = useCreateRefund(paymentId ?? "");

  const {
    register,
    handleSubmit,
    setError,
    reset,
    formState: { errors },
  } = useForm<RefundFormValues>({
    resolver: zodResolver(refundSchema),
    defaultValues: { amount: "", reason: "" },
  });

  if (!paymentId) {
    return null;
  }

  const onSubmit = handleSubmit(async (values) => {
    setPageError(null);
    try {
      await mutation.mutateAsync({
        amount: Number(values.amount),
        reason: values.reason.trim(),
        idempotencyKey,
      });
      setOpen(false);
      reset();
    } catch (error) {
      if (isApiClientError(error)) {
        let mappedToField = false;
        for (const fieldError of error.fieldErrors) {
          if (fieldError.field === "amount" || fieldError.field === "reason") {
            setError(fieldError.field, { type: "server", message: fieldError.message });
            mappedToField = true;
          }
        }
        if (!mappedToField) {
          setPageError(error.message);
        }
        return;
      }
      setPageError("Something went wrong. Please try again.");
    }
  });

  return (
    <AlertDialog
      open={open}
      onOpenChange={(nextOpen, eventDetails) => {
        // This is a destructive, money-moving confirmation with an
        // in-progress amount/reason form — an accidental Escape press must
        // not silently discard it. `disablePointerDismissal` already blocks
        // scrim/outside-press for AlertDialog; Escape is not covered by that
        // flag at all (confirmed against the @base-ui/react primitive), so
        // it's blocked explicitly here. Only the Cancel button and a
        // successful submit may close this dialog.
        if (eventDetails.reason === "escape-key") {
          eventDetails.cancel();
          return;
        }
        setOpen(nextOpen);
        if (nextOpen) {
          // Fresh open — a new logical refund attempt, so a fresh dedup key.
          setIdempotencyKey(crypto.randomUUID());
        } else {
          mutation.reset();
          setPageError(null);
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
            aria-label={`Refund payment ${paymentId} for order ${entry.orderId}`}
          />
        }
      >
        <RotateCcw aria-hidden="true" />
        Refund
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Refund payment {paymentId}</AlertDialogTitle>
          <AlertDialogDescription>
            Order {entry.orderId} — originally confirmed for {entry.amount.toFixed(2)}. This
            creates a new refund record and ledger entry; the original payment is never modified.
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
          onSubmit={onSubmit}
        >
          <LiveRegion message={mutation.isPending ? "Submitting refund…" : ""} />

          <div className="flex flex-col gap-1.5">
            <Label htmlFor={`refund-amount-${paymentId}`}>Refund amount</Label>
            <Input
              id={`refund-amount-${paymentId}`}
              inputMode="decimal"
              autoComplete="off"
              disabled={mutation.isPending}
              aria-invalid={!!errors.amount}
              aria-describedby={
                [
                  `refund-amount-${paymentId}-helper`,
                  errors.amount ? `refund-amount-${paymentId}-error` : undefined,
                ]
                  .filter(Boolean)
                  .join(" ") || undefined
              }
              {...register("amount")}
            />
            <p id={`refund-amount-${paymentId}-helper`} className="text-xs text-muted-foreground">
              {REFUND_AMOUNT_HELPER_TEXT} Cannot exceed the refundable remainder of this payment.
            </p>
            {errors.amount ? (
              <p
                id={`refund-amount-${paymentId}-error`}
                role="alert"
                className="text-xs text-destructive"
              >
                {errors.amount.message}
              </p>
            ) : null}
          </div>

          <div className="flex flex-col gap-1.5">
            <Label htmlFor={`refund-reason-${paymentId}`}>Reason</Label>
            <Input
              id={`refund-reason-${paymentId}`}
              autoComplete="off"
              disabled={mutation.isPending}
              aria-invalid={!!errors.reason}
              aria-describedby={errors.reason ? `refund-reason-${paymentId}-error` : undefined}
              {...register("reason")}
            />
            {errors.reason ? (
              <p
                id={`refund-reason-${paymentId}-error`}
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
              {mutation.isPending ? "Submitting…" : "Submit refund"}
            </Button>
          </AlertDialogFooter>
        </form>
      </AlertDialogContent>
    </AlertDialog>
  );
}
