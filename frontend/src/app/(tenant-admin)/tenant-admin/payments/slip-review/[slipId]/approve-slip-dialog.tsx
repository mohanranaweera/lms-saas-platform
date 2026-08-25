"use client";

import { useRef, useState, type ChangeEvent, type FormEvent } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { AlertCircle, CheckCircle2 } from "lucide-react";
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
import { useApproveSlip, type PaymentSlipResponse } from "@/lib/api/payment-slips";
import { SlipFlagBadge } from "@/components/payments/status-badges";
import { slipOverrideReasonSchema, type SlipOverrideReasonFormValues } from "@/lib/validation/payment-slip";

/**
 * Approve confirmation for the Slip Detail screen (SLIP-3/SLIP-4). Rendering
 * this component at all is gated by the caller (`canReviewSlips(role)` &&
 * `slip.status === "UNDER_REVIEW"` — UX convenience only); `POST
 * /api/v1/payment-slips/{id}/approve` independently re-enforces the literal
 * Tenant Admin/Finance Staff + `UNDER_REVIEW` requirement server-side
 * regardless of what renders here, and a forced 403/409 is surfaced inline,
 * dialog kept open — mirrors `refund-dialog.tsx`'s exact discipline.
 *
 * Two shapes, driven by `slip.flags`:
 * - No active flags: a plain confirmation, no reason field, submits `{}`.
 * - One or more flags: a warning callout lists them and a non-blank
 *   `overrideReason` is required — "Approve anyway" stays natively
 *   `disabled` until the field is non-blank (UX convenience only; the
 *   backend independently rejects a reasonless override with `409` before
 *   any state change, per `.claude/rules/payments.md` §3).
 */
export function ApproveSlipDialog({ slip }: { slip: PaymentSlipResponse }) {
  const [open, setOpen] = useState(false);
  const [pageError, setPageError] = useState<string | null>(null);
  const mutation = useApproveSlip(slip.id);
  const hasFlags = slip.flags.length > 0;

  // Tracked in local state (rather than RHF's `watch()`) purely to compute
  // the native `disabled` state below without opting this component out of
  // React Compiler memoization — mirrors `material-upload-form.tsx`'s exact
  // rationale for its own `selectedFile` state. RHF's own validation
  // (`errors.overrideReason`) is unaffected and still drives the submit path.
  const [overrideReasonValue, setOverrideReasonValue] = useState("");

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<SlipOverrideReasonFormValues>({
    resolver: zodResolver(slipOverrideReasonSchema),
    defaultValues: { overrideReason: "" },
  });

  const submitDisabled =
    mutation.isPending || (hasFlags && overrideReasonValue.trim().length === 0);

  const onSubmit = handleSubmit(async (values) => {
    setPageError(null);
    try {
      await mutation.mutateAsync({ overrideReason: values.overrideReason.trim() });
      setOpen(false);
      reset();
      setOverrideReasonValue("");
    } catch (error) {
      setPageError(
        isApiClientError(error) ? error.message : "Something went wrong. Please try again."
      );
    }
  });

  // `disabled={mutation.isPending}` alone isn't a hard guard here: a fast
  // native double-click can fire a second submission before React has
  // committed the re-render that flips the button to disabled — a real race
  // reproduced via Playwright. This ref closes that gap synchronously,
  // independent of render timing, and is shared by both submit paths below
  // (they're mutually exclusive per dialog instance — only one of the two
  // ever renders, driven by `hasFlags`).
  const isSubmittingRef = useRef(false);

  // The no-flags path has no form field at all, so it bypasses RHF's
  // schema-gated submit handler entirely and calls the mutation directly.
  // The ref is read/written directly inside this handler, which is safe:
  // it's a plain event-handler function, not itself passed as an argument
  // into `handleSubmit(...)`.
  async function handleConfirmNoFlags() {
    if (isSubmittingRef.current) return;
    isSubmittingRef.current = true;
    setPageError(null);
    try {
      await mutation.mutateAsync({});
      setOpen(false);
    } catch (error) {
      setPageError(
        isApiClientError(error) ? error.message : "Something went wrong. Please try again."
      );
    } finally {
      isSubmittingRef.current = false;
    }
  }

  // The flagged "Approve anyway" path is RHF-driven (`onSubmit` above, built
  // via `handleSubmit(...)`). Reading/writing a ref *inside* the callback
  // passed to `handleSubmit` trips React Compiler's ref-safety lint rule
  // (it flags that as a ref access during render). This wrapper sidesteps
  // that: it is assigned directly to the `<form>` element's `onSubmit` prop
  // — a separate function that is never itself passed into `handleSubmit`
  // — so the ref read/write here happens purely at the DOM-event-handler
  // level, which the rule allows. It calls the already-constructed
  // `onSubmit` (which still runs RHF validation and the mutation) and only
  // gates a synchronous second invocation from a fast double-click.
  function handleFlaggedFormSubmit(event: FormEvent<HTMLFormElement>) {
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
        // Approval is irreversible (activates enrollment) and, in the
        // flagged case, has an in-progress reason field — an accidental
        // Escape press must not silently discard either. Mirrors
        // `refund-dialog.tsx`'s exact rationale/discipline.
        if (eventDetails.reason === "escape-key") {
          eventDetails.cancel();
          return;
        }
        setOpen(nextOpen);
        if (!nextOpen) {
          mutation.reset();
          setPageError(null);
          reset();
          setOverrideReasonValue("");
          // Defensive only: today there is no way to reach this branch
          // while `isSubmittingRef.current` is `true` (Escape is blocked
          // while pending, the AlertDialog primitive blocks outside-press,
          // and Cancel is `disabled` while pending), so this never fires
          // mid-mutation in practice. It guards against a future relaxation
          // of any of those three blockers silently leaving the ref stuck
          // `true` (which would permanently disable both submit paths for
          // the rest of this component instance's lifetime).
          isSubmittingRef.current = false;
        }
      }}
    >
      <AlertDialogTrigger render={<Button type="button" size="sm" />}>
        <CheckCircle2 aria-hidden="true" />
        Approve
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Approve slip {slip.referenceNumber}</AlertDialogTitle>
          <AlertDialogDescription>
            {hasFlags
              ? "This slip was automatically flagged as a possible duplicate. Approving it activates enrollment immediately."
              : "Approving this slip activates enrollment immediately. There is no reversal from this screen."}
          </AlertDialogDescription>
        </AlertDialogHeader>

        {pageError ? (
          <Alert variant="destructive">
            <AlertCircle aria-hidden="true" />
            <AlertDescription>{pageError}</AlertDescription>
          </Alert>
        ) : null}

        {hasFlags ? (
          <div className="flex flex-col gap-2 rounded-md border border-warning/30 bg-warning/5 p-3">
            <p className="text-xs font-medium text-foreground">Active flags on this slip</p>
            <div className="flex flex-wrap gap-1">
              {slip.flags.map((flag) => (
                <SlipFlagBadge key={flag.id} flagType={flag.flagType} />
              ))}
            </div>
          </div>
        ) : null}

        {hasFlags ? (
          <form
            className="flex flex-col gap-3"
            noValidate
            aria-busy={mutation.isPending}
            onSubmit={handleFlaggedFormSubmit}
          >
            <LiveRegion message={mutation.isPending ? "Submitting approval…" : ""} />
            <div className="flex flex-col gap-1.5">
              <Label htmlFor={`override-reason-${slip.id}`}>Reason for overriding the flag(s)</Label>
              <Input
                id={`override-reason-${slip.id}`}
                autoComplete="off"
                disabled={mutation.isPending}
                aria-invalid={!!errors.overrideReason}
                aria-describedby={
                  errors.overrideReason ? `override-reason-${slip.id}-error` : undefined
                }
                {...register("overrideReason", {
                  onChange: (event: ChangeEvent<HTMLInputElement>) =>
                    setOverrideReasonValue(event.target.value),
                })}
              />
              {errors.overrideReason ? (
                <p
                  id={`override-reason-${slip.id}-error`}
                  role="alert"
                  className="text-xs text-destructive"
                >
                  {errors.overrideReason.message}
                </p>
              ) : null}
            </div>
            <AlertDialogFooter>
              <AlertDialogClose
                render={<Button type="button" variant="outline" disabled={mutation.isPending} />}
              >
                Cancel
              </AlertDialogClose>
              <Button type="submit" disabled={submitDisabled} aria-busy={mutation.isPending}>
                {mutation.isPending ? "Submitting…" : "Approve anyway"}
              </Button>
            </AlertDialogFooter>
          </form>
        ) : (
          <>
            <LiveRegion message={mutation.isPending ? "Submitting approval…" : ""} />
            <AlertDialogFooter>
              <AlertDialogClose
                render={<Button type="button" variant="outline" disabled={mutation.isPending} />}
              >
                Cancel
              </AlertDialogClose>
              <Button
                type="button"
                onClick={handleConfirmNoFlags}
                disabled={mutation.isPending}
                aria-busy={mutation.isPending}
              >
                {mutation.isPending ? "Submitting…" : "Approve"}
              </Button>
            </AlertDialogFooter>
          </>
        )}
      </AlertDialogContent>
    </AlertDialog>
  );
}
