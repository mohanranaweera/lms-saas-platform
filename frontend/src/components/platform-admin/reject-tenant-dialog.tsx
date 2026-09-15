"use client";

import { useRef, useState } from "react";
import { AlertCircle, XCircle } from "lucide-react";
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
import { LiveRegion } from "@/components/ui/live-region";
import { isApiClientError } from "@/lib/api/error";
import { useRejectTenant } from "@/lib/api/platform-admin-tenants";
import type { TenantDialogTenant } from "./approve-tenant-dialog";

/**
 * Reject confirmation for a `pending_approval` tenant, shared by the Tenant
 * List row action and the Tenant Detail screen. Rendering this component at
 * all is gated by the caller (`tenant.status === "pending_approval"` — UX
 * convenience only); `POST /api/v1/platform-admin/tenants/{id}/reject`
 * independently re-enforces the literal Platform-Admin-only + pending-
 * approval requirement server-side, and a `409 CONFLICT` (already processed
 * by another admin) is surfaced inline with the dialog kept open. `REJECTED`
 * is a one-directional terminal transition; no login path is provisioned -
 * per docs/ui-ux/component-library-spec.md §4.3, Escape/scrim-click are
 * disabled unconditionally (not just while pending), requiring an explicit
 * Cancel/Confirm choice, since this is a destructive confirmation.
 */
export function RejectTenantDialog({ tenant }: { tenant: TenantDialogTenant }) {
  const [open, setOpen] = useState(false);
  const [pageError, setPageError] = useState<string | null>(null);
  const mutation = useRejectTenant(tenant.id);

  // Guards against a fast native double-click firing a second submission —
  // the same race proven (and fixed) in `approve-slip-dialog.tsx`.
  const isSubmittingRef = useRef(false);

  async function handleConfirm() {
    if (isSubmittingRef.current) return;
    isSubmittingRef.current = true;
    setPageError(null);
    try {
      await mutation.mutateAsync();
      setOpen(false);
    } catch (error) {
      if (isApiClientError(error) && error.status === 409) {
        setPageError(
          "This tenant was already processed by another admin — refresh to see its current status."
        );
      } else {
        setPageError(
          isApiClientError(error) ? error.message : "Something went wrong. Please try again."
        );
      }
    } finally {
      isSubmittingRef.current = false;
    }
  }

  return (
    <AlertDialog
      open={open}
      onOpenChange={(nextOpen, eventDetails) => {
        // Reject is a destructive, one-directional confirmation — per
        // docs/ui-ux/component-library-spec.md §4.3, Escape and scrim-click
        // (outside-press) are disabled unconditionally, not just while a
        // submission is in flight, so the admin must make an explicit
        // Cancel/Confirm choice. Only the Cancel button or a successful
        // submit may close this dialog.
        if (eventDetails.reason === "escape-key" || eventDetails.reason === "outside-press") {
          eventDetails.cancel();
          return;
        }
        setOpen(nextOpen);
        if (!nextOpen) {
          mutation.reset();
          setPageError(null);
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
          <AlertDialogTitle>Reject {tenant.name}?</AlertDialogTitle>
          <AlertDialogDescription>
            This is a one-directional, terminal decision — no login path is provisioned and
            there is no reopen/reversal path from this screen.
          </AlertDialogDescription>
        </AlertDialogHeader>

        {pageError ? (
          <Alert variant="destructive">
            <AlertCircle aria-hidden="true" />
            <AlertDescription>{pageError}</AlertDescription>
          </Alert>
        ) : null}

        <LiveRegion message={mutation.isPending ? "Submitting rejection…" : ""} />

        <AlertDialogFooter>
          <AlertDialogClose
            render={<Button type="button" variant="outline" disabled={mutation.isPending} />}
          >
            Cancel
          </AlertDialogClose>
          <Button
            type="button"
            variant="destructive"
            onClick={handleConfirm}
            disabled={mutation.isPending}
            aria-busy={mutation.isPending}
          >
            {mutation.isPending ? "Submitting…" : "Reject"}
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
