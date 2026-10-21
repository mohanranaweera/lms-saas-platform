"use client";

import { useState } from "react";
import { AlertCircle } from "lucide-react";
import { isApiClientError } from "@/lib/api/error";
import { formatDateTime } from "@/lib/format";
import {
  usePlatformDisableTenantGateway,
  usePlatformEnableTenantGateway,
  usePlatformTenantGateway,
  type PaymentGatewayAccount,
} from "@/lib/api/payment-gateway";
import { LoadingState } from "@/components/states/loading-state";
import { ErrorState } from "@/components/states/error-state";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
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

/**
 * Platform Admin view of one tenant's online payments (PAY-GW-01, ADR-017
 * owner decisions 3-4). Status only - never a merchant secret. Switching off
 * requires a reason; only an account the platform switched off can be
 * switched back on here. The tenant is named on every action, per the UI
 * rules for cross-tenant platform actions.
 */
export function OnlinePaymentsCard({ tenantId, tenantName }: { tenantId: string; tenantName: string }) {
  const query = usePlatformTenantGateway(tenantId);

  return (
    <section aria-labelledby="online-payments-heading" className="rounded-lg border border-border">
      <div className="border-b border-border px-4 py-3">
        <h2 id="online-payments-heading" className="text-base font-semibold text-foreground">
          Online payments
        </h2>
        <p className="text-sm text-muted-foreground">
          {tenantName}&apos;s own PayHere account. The platform takes no commission on these payments.
        </p>
      </div>
      <div className="px-4 py-3">
        {query.isPending ? (
          <LoadingState label="Loading online payment status…" />
        ) : query.isError ? (
          <ErrorState
            message={isApiClientError(query.error) ? query.error.message : "Couldn't load online payment status."}
            onRetry={() => query.refetch()}
          />
        ) : query.data.length === 0 ? (
          <p className="text-sm text-muted-foreground">
            {tenantName} has not connected an online payment account. Students pay by bank transfer.
          </p>
        ) : (
          <AccountDetails tenantId={tenantId} tenantName={tenantName} account={query.data[0]} />
        )}
      </div>
    </section>
  );
}

function AccountDetails({
  tenantId,
  tenantName,
  account,
}: {
  tenantId: string;
  tenantName: string;
  account: PaymentGatewayAccount;
}) {
  const active = account.status === "ACTIVE";
  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2 text-sm">
        <span className="font-medium text-foreground">PayHere</span>
        <Badge variant={active ? "default" : "outline"}>{active ? "Active" : "Switched off"}</Badge>
        <Badge variant="outline">{account.mode === "LIVE" ? "Live" : "Sandbox"}</Badge>
        <span className="font-mono text-muted-foreground">{account.merchantIdMasked}</span>
      </div>
      {!active ? (
        <p className="text-sm text-muted-foreground">
          Switched off by {account.disabledBy === "PLATFORM" ? "the platform" : "the institute"}
          {account.disabledAt ? ` on ${formatDateTime(account.disabledAt)}` : ""}
          {account.disabledReason ? ` — ${account.disabledReason}` : ""}.
        </p>
      ) : null}
      {active ? <PlatformDisableAction tenantId={tenantId} tenantName={tenantName} /> : null}
      {!active && account.disabledBy === "PLATFORM" ? (
        <PlatformEnableAction tenantId={tenantId} tenantName={tenantName} />
      ) : null}
    </div>
  );
}

function PlatformDisableAction({ tenantId, tenantName }: { tenantId: string; tenantName: string }) {
  const mutation = usePlatformDisableTenantGateway(tenantId);
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState("");
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  async function handleConfirm() {
    setErrorMessage(null);
    if (!reason.trim()) {
      setErrorMessage("A reason is required.");
      return;
    }
    try {
      await mutation.mutateAsync(reason.trim());
      setOpen(false);
      setReason("");
    } catch (error) {
      setErrorMessage(isApiClientError(error) ? error.message : "Something went wrong. Please try again.");
    }
  }

  return (
    <AlertDialog open={open} onOpenChange={setOpen}>
      <AlertDialogTrigger render={<Button type="button" variant="destructive" className="w-fit" />}>
        Switch off online payments
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Switch off online payments for {tenantName}?</AlertDialogTitle>
          <AlertDialogDescription>
            {tenantName}&apos;s students will only be able to pay by bank transfer, and the institute
            cannot switch it back on itself. Payments already in progress still complete.
          </AlertDialogDescription>
        </AlertDialogHeader>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="platform-disable-reason">Reason (required)</Label>
          <Textarea
            id="platform-disable-reason"
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            aria-invalid={errorMessage === "A reason is required." ? true : undefined}
          />
        </div>
        {errorMessage ? (
          <Alert variant="destructive">
            <AlertCircle aria-hidden="true" />
            <AlertDescription>{errorMessage}</AlertDescription>
          </Alert>
        ) : null}
        <AlertDialogFooter>
          <AlertDialogClose render={<Button type="button" variant="outline" />}>Cancel</AlertDialogClose>
          <Button
            type="button"
            variant="destructive"
            onClick={handleConfirm}
            disabled={mutation.isPending}
            aria-busy={mutation.isPending}
          >
            {mutation.isPending ? "Switching off…" : `Switch off for ${tenantName}`}
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}

function PlatformEnableAction({ tenantId, tenantName }: { tenantId: string; tenantName: string }) {
  const mutation = usePlatformEnableTenantGateway(tenantId);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  async function handleEnable() {
    setErrorMessage(null);
    try {
      await mutation.mutateAsync();
    } catch (error) {
      setErrorMessage(isApiClientError(error) ? error.message : "Something went wrong. Please try again.");
    }
  }

  return (
    <div className="flex flex-col gap-2">
      <Button
        type="button"
        className="w-fit"
        onClick={handleEnable}
        disabled={mutation.isPending}
        aria-busy={mutation.isPending}
      >
        {mutation.isPending ? "Switching on…" : `Switch online payments back on for ${tenantName}`}
      </Button>
      {errorMessage ? (
        <p role="alert" className="text-sm text-destructive">
          {errorMessage}
        </p>
      ) : null}
    </div>
  );
}
