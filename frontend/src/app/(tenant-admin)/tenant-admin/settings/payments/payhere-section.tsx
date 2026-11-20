"use client";

import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { AlertCircle } from "lucide-react";
import { useAuth } from "@/lib/auth/auth-context";
import { canManagePaymentGateway } from "@/lib/auth/permissions";
import { isApiClientError } from "@/lib/api/error";
import { formatDateTime } from "@/lib/format";
import {
  useDisablePayHereAccount,
  useSavePayHereAccount,
  useTenantGatewayAccounts,
  type PaymentGatewayAccount,
} from "@/lib/api/payment-gateway";
import { payHereAccountSchema, type PayHereAccountFormValues } from "@/lib/validation/payment-gateway";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { ErrorState } from "@/components/states/error-state";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
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
import { PaymentSettingsCard } from "./payment-settings-card";

/**
 * Settings → Payments → Payment gateway (PAY-GW-01 / ADR-017; moved here from
 * the standalone Online payments page by SET-PAY-01, behaviour unchanged).
 * Connects the institute's own PayHere merchant account so students can pay
 * online and the money goes straight to the institute.
 *
 * The merchant secret is write-only: the input is never prefilled and no
 * response ever contains it. Finance Staff / Auditor see status only; the
 * backend enforces `PAYMENT_GATEWAY` on every call regardless of what this
 * section shows. A platform switch-off is shown as such and blocks editing
 * (only a Platform Admin can undo it).
 */
export function PayHereSection() {
  const { session } = useAuth();
  const canManage = canManagePaymentGateway(session?.role ?? null);
  const query = useTenantGatewayAccounts();
  const account = query.data?.[0] ?? null;
  const active = account?.status === "ACTIVE";

  return (
    <PaymentSettingsCard
      id="payment-gateway"
      title="Payment gateway — PayHere"
      description="Connect your institute's PayHere merchant account so students can pay online. Payments go straight to your account."
      testId="payment-gateway-settings"
      action={
        query.isSuccess ? (
          <Badge variant={active ? "default" : "outline"}>{active ? "PayHere active" : "Not active"}</Badge>
        ) : null
      }
    >
      <QueryStateBoundary
        query={query}
        loadingLabel="Loading online payment settings…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
      >
        {(accounts) => <OnlinePaymentsBody account={accounts[0] ?? null} canManage={canManage} />}
      </QueryStateBoundary>
    </PaymentSettingsCard>
  );
}

function OnlinePaymentsBody({
  account,
  canManage,
}: {
  account: PaymentGatewayAccount | null;
  canManage: boolean;
}) {
  const platformDisabled = account?.disabledBy === "PLATFORM";

  return (
    <div className="flex max-w-2xl flex-col gap-6">
      <AccountStatusCard account={account} />

      {account?.status === "ACTIVE" && !account.checkoutReady ? (
        <Alert role="status">
          <AlertCircle aria-hidden="true" />
          <AlertDescription>
            Your PayHere account is saved, but students can&apos;t pay online yet: the platform&apos;s
            PayHere connection isn&apos;t finished (PayHere needs a public address to send payment
            confirmations to). Until then, students only see &quot;Pay by bank transfer&quot;. Contact
            platform support if this persists.
          </AlertDescription>
        </Alert>
      ) : null}

      {platformDisabled ? (
        <Alert variant="destructive">
          <AlertCircle aria-hidden="true" />
          <AlertDescription>
            Online payments were switched off by the platform
            {account?.disabledReason ? ` (${account.disabledReason})` : ""}. Contact platform
            support to have them switched back on.
          </AlertDescription>
        </Alert>
      ) : null}

      {canManage && !platformDisabled ? <PayHereAccountForm account={account} /> : null}

      {canManage && account?.status === "ACTIVE" ? <DisableOnlinePayments /> : null}

      {!canManage ? (
        <p className="text-sm text-muted-foreground">
          Only a Tenant Admin can change these settings.
        </p>
      ) : null}
    </div>
  );
}

function AccountStatusCard({ account }: { account: PaymentGatewayAccount | null }) {
  if (!account) {
    return (
      <div className="rounded-lg border border-dashed border-border p-4 text-sm">
        <p className="font-medium text-foreground">Online payments are not set up</p>
        <p className="text-muted-foreground">
          Students can only pay by bank transfer until you connect a PayHere account below.
        </p>
      </div>
    );
  }
  const active = account.status === "ACTIVE";
  return (
    <section aria-label="PayHere account status" className="rounded-lg border border-border p-4">
      <div className="flex flex-wrap items-center gap-2">
        <h3 className="text-base font-medium text-foreground">PayHere</h3>
        <Badge variant={active ? "default" : "outline"}>{active ? "Active" : "Switched off"}</Badge>
        <Badge variant="outline">{account.mode === "LIVE" ? "Live" : "Sandbox (test)"}</Badge>
      </div>
      <dl className="mt-3 grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
        <dt className="text-muted-foreground">Merchant ID</dt>
        <dd className="font-mono text-foreground">{account.merchantIdMasked}</dd>
        <dt className="text-muted-foreground">Merchant secret</dt>
        <dd className="text-foreground">{account.secretConfigured ? "Saved (hidden)" : "Not set"}</dd>
        <dt className="text-muted-foreground">Last updated</dt>
        <dd className="text-foreground">{formatDateTime(account.updatedAt)}</dd>
      </dl>
    </section>
  );
}

function PayHereAccountForm({ account }: { account: PaymentGatewayAccount | null }) {
  const saveMutation = useSavePayHereAccount();
  const [pageError, setPageError] = useState<{ message: string; code?: string } | null>(null);
  const [saved, setSaved] = useState(false);
  const secretStored = account?.secretConfigured ?? false;

  const {
    register,
    handleSubmit,
    setError,
    reset,
    formState: { errors },
  } = useForm<PayHereAccountFormValues>({
    resolver: zodResolver(payHereAccountSchema),
    defaultValues: { merchantId: "", merchantSecret: "", mode: account?.mode ?? "SANDBOX" },
  });

  const onSubmit = handleSubmit(async (values) => {
    setPageError(null);
    setSaved(false);
    if (!secretStored && !values.merchantSecret.trim()) {
      setError("merchantSecret", { type: "manual", message: "Merchant secret is required." });
      return;
    }
    try {
      await saveMutation.mutateAsync(values);
      setSaved(true);
      reset({ merchantId: "", merchantSecret: "", mode: values.mode });
    } catch (error) {
      if (isApiClientError(error)) {
        let mapped = false;
        for (const fieldError of error.fieldErrors) {
          if (fieldError.field === "merchantId" || fieldError.field === "merchantSecret" || fieldError.field === "mode") {
            setError(fieldError.field, { type: "server", message: fieldError.message });
            mapped = true;
          }
        }
        if (!mapped) setPageError({ message: error.message, code: error.code });
        return;
      }
      setPageError({ message: "An unexpected error occurred. Please try again." });
    }
  });

  return (
    <section aria-labelledby="payhere-form-heading" className="flex flex-col gap-4">
      <div>
        <h3 id="payhere-form-heading" className="text-base font-medium text-foreground">
          {account ? "Update PayHere account" : "Connect PayHere"}
        </h3>
        <p className="text-sm text-muted-foreground">
          Find these in your PayHere merchant portal. Your merchant secret is stored encrypted and is
          never shown again.
        </p>
      </div>

      {pageError ? <ErrorState message={pageError.message} code={pageError.code} /> : null}

      <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4">
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="payhere-merchant-id">Merchant ID</Label>
          <Input
            id="payhere-merchant-id"
            autoComplete="off"
            aria-invalid={errors.merchantId ? true : undefined}
            aria-describedby={errors.merchantId ? "payhere-merchant-id-error" : undefined}
            {...register("merchantId")}
          />
          {errors.merchantId ? (
            <p id="payhere-merchant-id-error" role="alert" className="text-sm text-destructive">
              {errors.merchantId.message}
            </p>
          ) : null}
        </div>

        <div className="flex flex-col gap-1.5">
          <Label htmlFor="payhere-merchant-secret">Merchant secret</Label>
          <Input
            id="payhere-merchant-secret"
            type="password"
            autoComplete="new-password"
            placeholder={secretStored ? "Leave blank to keep the saved secret" : undefined}
            aria-invalid={errors.merchantSecret ? true : undefined}
            aria-describedby={errors.merchantSecret ? "payhere-merchant-secret-error" : undefined}
            {...register("merchantSecret")}
          />
          {errors.merchantSecret ? (
            <p id="payhere-merchant-secret-error" role="alert" className="text-sm text-destructive">
              {errors.merchantSecret.message}
            </p>
          ) : null}
        </div>

        <fieldset className="flex flex-col gap-2">
          <legend className="text-sm font-medium text-foreground">Mode</legend>
          <label className="flex items-center gap-2 text-sm">
            <input type="radio" value="SANDBOX" {...register("mode")} />
            Sandbox (test payments only)
          </label>
          <label className="flex items-center gap-2 text-sm">
            <input type="radio" value="LIVE" {...register("mode")} />
            Live (real payments)
          </label>
        </fieldset>

        <div className="flex items-center gap-3">
          <Button type="submit" disabled={saveMutation.isPending} aria-busy={saveMutation.isPending}>
            {saveMutation.isPending ? "Saving…" : account ? "Save changes" : "Connect PayHere"}
          </Button>
          {saved ? (
            <p role="status" className="text-sm text-muted-foreground">
              PayHere account saved. Students can now pay online.
            </p>
          ) : null}
        </div>
      </form>
    </section>
  );
}

function DisableOnlinePayments() {
  const disableMutation = useDisablePayHereAccount();
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState("");
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  async function handleConfirm() {
    setErrorMessage(null);
    try {
      await disableMutation.mutateAsync(reason);
      setOpen(false);
      setReason("");
    } catch (error) {
      setErrorMessage(isApiClientError(error) ? error.message : "Something went wrong. Please try again.");
    }
  }

  return (
    <AlertDialog open={open} onOpenChange={setOpen}>
      <AlertDialogTrigger render={<Button type="button" variant="outline" className="w-fit" />}>
        Switch off online payments
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Switch off online payments?</AlertDialogTitle>
          <AlertDialogDescription>
            Students will only be able to pay by bank transfer. Payments already in progress still
            complete. You can connect PayHere again at any time.
          </AlertDialogDescription>
        </AlertDialogHeader>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="disable-reason">Reason (optional)</Label>
          <Textarea id="disable-reason" value={reason} onChange={(event) => setReason(event.target.value)} />
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
            disabled={disableMutation.isPending}
            aria-busy={disableMutation.isPending}
          >
            {disableMutation.isPending ? "Switching off…" : "Switch off"}
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
