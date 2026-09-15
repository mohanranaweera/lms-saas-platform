"use client";

import { useRef, useState } from "react";
import { AlertCircle, CheckCircle2 } from "lucide-react";
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
import { useApproveTenant } from "@/lib/api/platform-admin-tenants";

export interface TenantDialogTenant {
  id: string;
  name: string;
}

/**
 * Approve confirmation for a `pending_approval` tenant, shared by the Tenant
 * List row action (`(dashboard)/tenants/page.tsx`) and the Tenant Detail
 * screen (`(dashboard)/tenants/[tenantId]/page.tsx`). Rendering this
 * component at all is gated by the caller (`tenant.status ===
 * "pending_approval"` — UX convenience only); `POST
 * /api/v1/platform-admin/tenants/{id}/approve` independently re-enforces the
 * literal Platform-Admin-only + pending-approval requirement server-side,
 * and a `409 CONFLICT` ("Tenant is not pending approval" — already processed
 * by another admin) is surfaced inline with the dialog kept open — mirrors
 * `approve-slip-dialog.tsx`'s exact discipline, simplified: no flags, no
 * reason field, just a plain confirmation naming the tenant.
 */
export function ApproveTenantDialog({ tenant }: { tenant: TenantDialogTenant }) {
  const [open, setOpen] = useState(false);
  const [pageError, setPageError] = useState<string | null>(null);
  const mutation = useApproveTenant(tenant.id);

  // Guards against a fast native double-click firing a second submission
  // before React commits the re-render that disables the button — the same
  // race proven (and fixed) in `approve-slip-dialog.tsx`.
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
        // Blocked only while a submission is in flight — mirrors
        // `approve-slip-dialog.tsx`'s rationale for an irreversible action,
        // scoped to the pending window since there is no in-progress form
        // field here to lose once idle.
        if (eventDetails.reason === "escape-key" && mutation.isPending) {
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
      <AlertDialogTrigger render={<Button type="button" size="sm" />}>
        <CheckCircle2 aria-hidden="true" />
        Approve
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Approve {tenant.name}?</AlertDialogTitle>
          <AlertDialogDescription>
            This activates the tenant on its requested plan and grants it a login path. There
            is no reversal from this screen.
          </AlertDialogDescription>
        </AlertDialogHeader>

        {pageError ? (
          <Alert variant="destructive">
            <AlertCircle aria-hidden="true" />
            <AlertDescription>{pageError}</AlertDescription>
          </Alert>
        ) : null}

        <LiveRegion message={mutation.isPending ? "Submitting approval…" : ""} />

        <AlertDialogFooter>
          <AlertDialogClose
            render={<Button type="button" variant="outline" disabled={mutation.isPending} />}
          >
            Cancel
          </AlertDialogClose>
          <Button
            type="button"
            onClick={handleConfirm}
            disabled={mutation.isPending}
            aria-busy={mutation.isPending}
          >
            {mutation.isPending ? "Submitting…" : "Approve"}
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
