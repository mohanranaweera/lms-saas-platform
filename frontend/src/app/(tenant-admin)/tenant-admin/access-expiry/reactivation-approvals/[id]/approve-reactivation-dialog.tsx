"use client";

import { useRef, useState, type FormEvent } from "react";
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
import { useApproveReactivationRequest, type ReactivationRequestResponse } from "@/lib/api/enrollments";
import {
  reactivationApproveNoteSchema,
  type ReactivationApproveNoteFormValues,
} from "@/lib/validation/reactivation";

/**
 * Approve confirmation for the Reactivation Request Detail screen (MVP-012).
 * Rendering this component at all is gated by the caller
 * (`canApproveReactivation(role)` && `status === "SUBMITTED"` — UX
 * convenience only); `POST /api/v1/reactivation-requests/{id}/approve`
 * independently re-enforces the literal Tenant-Admin-only + `SUBMITTED`
 * requirement server-side, and a forced 403/409 is surfaced inline, dialog
 * kept open — mirrors `approve-slip-dialog.tsx`'s exact discipline.
 *
 * Unlike the slip approve dialog, `note` is always optional here (no
 * conditional-required flag path) — the field is shown once and the submit
 * button is never natively `disabled` on its content, only while the
 * mutation itself is pending. Approving here never activates enrollment or
 * course access by itself: it only lets the student place a new order.
 */
export function ApproveReactivationDialog({
  reactivationRequest,
}: {
  reactivationRequest: ReactivationRequestResponse;
}) {
  const [open, setOpen] = useState(false);
  const [pageError, setPageError] = useState<string | null>(null);
  const mutation = useApproveReactivationRequest(reactivationRequest.id);

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<ReactivationApproveNoteFormValues>({
    resolver: zodResolver(reactivationApproveNoteSchema),
    defaultValues: { note: "" },
  });

  const onSubmit = handleSubmit(async (values) => {
    setPageError(null);
    try {
      const trimmed = values.note.trim();
      await mutation.mutateAsync(trimmed.length > 0 ? { note: trimmed } : {});
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
  // race proven (and fixed) in `approve-slip-dialog.tsx`. This wrapper is
  // assigned directly to the `<form>` element's `onSubmit` prop (never itself
  // passed into `handleSubmit`), so the ref read/write happens purely at the
  // DOM-event-handler level, which React Compiler's ref-safety rule allows.
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
        // An in-progress note must not be silently discarded by an accidental
        // Escape press — mirrors `approve-slip-dialog.tsx`'s exact rationale.
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
          // pending).
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
          <AlertDialogTitle>Approve reactivation request</AlertDialogTitle>
          <AlertDialogDescription>
            This lets the student place a new order to restore access to this course. It does not
            activate access by itself — activation still requires a confirmed payment or an
            approved manual payment slip.
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
          <LiveRegion message={mutation.isPending ? "Submitting approval…" : ""} />
          <div className="flex flex-col gap-1.5">
            <Label htmlFor={`approve-note-${reactivationRequest.id}`}>Note (optional)</Label>
            <Input
              id={`approve-note-${reactivationRequest.id}`}
              autoComplete="off"
              disabled={mutation.isPending}
              aria-invalid={!!errors.note}
              aria-describedby={errors.note ? `approve-note-${reactivationRequest.id}-error` : undefined}
              {...register("note")}
            />
            {errors.note ? (
              <p
                id={`approve-note-${reactivationRequest.id}-error`}
                role="alert"
                className="text-xs text-destructive"
              >
                {errors.note.message}
              </p>
            ) : null}
          </div>
          <AlertDialogFooter>
            <AlertDialogClose
              render={<Button type="button" variant="outline" disabled={mutation.isPending} />}
            >
              Cancel
            </AlertDialogClose>
            <Button type="submit" disabled={mutation.isPending} aria-busy={mutation.isPending}>
              {mutation.isPending ? "Submitting…" : "Approve"}
            </Button>
          </AlertDialogFooter>
        </form>
      </AlertDialogContent>
    </AlertDialog>
  );
}
