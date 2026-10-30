"use client";

import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { AlertCircle } from "lucide-react";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { ErrorState } from "@/components/states/error-state";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import { useAuth } from "@/lib/auth/auth-context";
import { canManageInstituteConfig } from "@/lib/auth/permissions";
import { isApiClientError } from "@/lib/api/error";
import { useTenantGatewayAccounts } from "@/lib/api/payment-gateway";
import {
  useTenantConfigDomain,
  useUpdateTenantConfigDomain,
  type TenantConfigProperty,
} from "@/lib/api/tenant-config";
import {
  BANK_DETAIL_KEYS,
  bankDetailsSchema,
  configValue,
  toChangedConfigEntries,
  type BankDetailsFormValues,
} from "@/lib/validation/tenant-config";
import { PaymentSettingsCard } from "./payment-settings-card";

type BankKey = (typeof BANK_DETAIL_KEYS)[number];

const BANK_FIELDS: { name: BankKey; label: string }[] = [
  { name: "bank_account_holder", label: "Account holder" },
  { name: "bank_account_number", label: "Account number" },
  { name: "bank_name", label: "Bank name" },
  { name: "bank_branch", label: "Branch" },
];

function bankFormValues(properties: TenantConfigProperty[]): BankDetailsFormValues {
  const read = (key: BankKey) => {
    const value = configValue(properties, key);
    return typeof value === "string" ? value : "";
  };
  return {
    bank_account_holder: read("bank_account_holder"),
    bank_account_number: read("bank_account_number"),
    bank_name: read("bank_name"),
    bank_branch: read("bank_branch"),
  };
}

/**
 * Settings → Payments → Bank details (SET-PAY-01). The institute's single bank
 * account (the four existing `PAYMENT.bank_*` keys, shown to students at
 * checkout for bank transfer) and the `bank_transfer_enabled` switch.
 *
 * `BRANDING_SETTINGS` VIEW/CREATE_EDIT server-side; every change is audit
 * logged with before/after by the backend. The switch is enforced by the
 * backend on slip upload; while online payment isn't available the backend
 * keeps bank transfer open regardless, which the warning below explains.
 */
export function BankDetailsSection() {
  const { session } = useAuth();
  const canManage = canManageInstituteConfig(session?.role ?? null);
  const query = useTenantConfigDomain("PAYMENT");

  return (
    <PaymentSettingsCard
      id="bank-details"
      title="Bank details"
      description="Shown to students who pay by bank transfer, so they know where to deposit."
      testId="bank-details-settings"
    >
      <QueryStateBoundary
        query={query}
        loadingLabel="Loading bank details…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
        genericErrorMessage="The bank details could not be loaded."
      >
        {(properties) => (
          <div className="flex flex-col gap-6">
            <BankAccountForm properties={properties} canManage={canManage} />
            <BankTransferSwitch properties={properties} canManage={canManage} />
          </div>
        )}
      </QueryStateBoundary>
    </PaymentSettingsCard>
  );
}

function BankAccountForm({ properties, canManage }: { properties: TenantConfigProperty[]; canManage: boolean }) {
  const updateMutation = useUpdateTenantConfigDomain("PAYMENT");
  const current = bankFormValues(properties);
  const [editing, setEditing] = useState(false);
  const [pageError, setPageError] = useState<{ message: string; code?: string } | null>(null);
  const [saved, setSaved] = useState(false);
  const pending = updateMutation.isPending;

  const {
    register,
    handleSubmit,
    setError,
    reset,
    formState: { errors },
  } = useForm<BankDetailsFormValues>({
    resolver: zodResolver(bankDetailsSchema),
    defaultValues: current,
  });

  const cancel = () => {
    reset(current);
    setPageError(null);
    setEditing(false);
  };

  const onSubmit = handleSubmit(async (values) => {
    setPageError(null);
    setSaved(false);
    const trimmed = Object.fromEntries(
      Object.entries(values).map(([key, value]) => [key, value.trim()])
    ) as BankDetailsFormValues;
    const changes = toChangedConfigEntries(trimmed, current);
    if (Object.keys(changes).length === 0) {
      setEditing(false);
      return;
    }
    try {
      const updated = await updateMutation.mutateAsync(changes);
      reset(bankFormValues(updated));
      setEditing(false);
      setSaved(true);
    } catch (error) {
      if (isApiClientError(error)) {
        let mapped = false;
        for (const fieldError of error.fieldErrors) {
          if ((BANK_DETAIL_KEYS as readonly string[]).includes(fieldError.field)) {
            setError(fieldError.field as BankKey, { type: "server", message: fieldError.message });
            mapped = true;
          }
        }
        if (error.status === 403) {
          setPageError({ message: "You don't have permission to change the bank details.", code: error.code });
        } else if (!mapped) {
          setPageError({ message: error.message, code: error.code });
        }
        return;
      }
      setPageError({ message: "An unexpected error occurred. Please try again." });
    }
  });

  const readOnly = !canManage || !editing;
  const noneSet = BANK_FIELDS.every((field) => !current[field.name]);

  return (
    <form onSubmit={onSubmit} noValidate aria-busy={pending} className="flex flex-col gap-4" aria-label="Bank account">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="rounded-full bg-primary/10 px-2.5 py-0.5 text-xs font-medium text-primary">Account #1</span>
        {canManage && !editing ? (
          <Button type="button" variant="outline" size="sm" onClick={() => { setSaved(false); setEditing(true); }} data-testid="bank-details-edit">
            Edit
          </Button>
        ) : null}
      </div>

      {noneSet && !editing ? (
        <p className="text-sm text-muted-foreground" data-testid="bank-details-empty">
          No bank account added yet. Students paying by bank transfer will see &quot;Bank not set&quot;
          {canManage ? " — select Edit to add one." : "."}
        </p>
      ) : null}

      {pageError ? <ErrorState message={pageError.message} code={pageError.code} /> : null}

      <fieldset disabled={pending} className="grid grid-cols-1 gap-4 md:grid-cols-2">
        <legend className="sr-only">Bank account details</legend>
        {BANK_FIELDS.map((field) => {
          const fieldError = errors[field.name];
          const inputId = `bank-${field.name}`;
          return (
            <div key={field.name} className="flex flex-col gap-1.5">
              <Label htmlFor={inputId}>{field.label}</Label>
              <Input
                id={inputId}
                autoComplete="off"
                readOnly={readOnly}
                aria-invalid={fieldError ? true : undefined}
                aria-describedby={fieldError ? `${inputId}-error` : undefined}
                {...register(field.name)}
              />
              {fieldError ? (
                <p id={`${inputId}-error`} role="alert" className="text-sm text-destructive">
                  {fieldError.message}
                </p>
              ) : null}
            </div>
          );
        })}
      </fieldset>

      {editing ? (
        <div className="flex flex-wrap items-center gap-3">
          <Button type="submit" disabled={pending} aria-busy={pending} data-testid="bank-details-save">
            {pending ? "Saving…" : "Save bank details"}
          </Button>
          <Button type="button" variant="outline" onClick={cancel} disabled={pending}>
            Cancel
          </Button>
        </div>
      ) : null}
      {saved ? (
        <p role="status" className="text-sm text-muted-foreground">
          Bank details saved. Students see them the next time they pay by bank transfer.
        </p>
      ) : null}
      {!canManage ? <p className="text-sm text-muted-foreground">Only a Tenant Admin can change the bank details.</p> : null}
    </form>
  );
}

function BankTransferSwitch({ properties, canManage }: { properties: TenantConfigProperty[]; canManage: boolean }) {
  const updateMutation = useUpdateTenantConfigDomain("PAYMENT");
  const gatewayQuery = useTenantGatewayAccounts();
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const enabled = configValue(properties, "bank_transfer_enabled") !== false;
  const account = gatewayQuery.data?.[0] ?? null;
  // Known only when the gateway read succeeded; otherwise no warning is guessed.
  const onlineReady = gatewayQuery.isSuccess ? account?.status === "ACTIVE" && account.checkoutReady : true;
  const pending = updateMutation.isPending;

  const toggle = async (next: boolean) => {
    setErrorMessage(null);
    try {
      await updateMutation.mutateAsync({ bank_transfer_enabled: next });
    } catch (error) {
      setErrorMessage(
        isApiClientError(error)
          ? error.status === 403
            ? "You don't have permission to change this setting."
            : error.message
          : "Something went wrong. Please try again."
      );
    }
  };

  return (
    <div className="flex flex-col gap-3 border-t border-border pt-4" data-testid="bank-transfer-setting">
      <div className="flex items-start gap-3">
        <Switch
          id="bank-transfer-enabled"
          checked={enabled}
          onCheckedChange={(next) => void toggle(next)}
          disabled={!canManage || pending}
          aria-describedby="bank-transfer-enabled-help"
          aria-busy={pending}
          className="mt-0.5"
        />
        <div className="flex flex-col gap-0.5">
          <Label htmlFor="bank-transfer-enabled">Accept bank transfer</Label>
          <p id="bank-transfer-enabled-help" className="text-sm text-muted-foreground">
            Students can pay by bank transfer and upload the payment slip. Turning this off stops new slip uploads; slips
            already submitted are still reviewed.
          </p>
        </div>
      </div>
      {!enabled && !onlineReady ? (
        <Alert role="status" data-testid="bank-transfer-fallback-warning">
          <AlertCircle aria-hidden="true" />
          <AlertDescription>
            Online payment isn&apos;t available yet, so students can still pay by bank transfer. Bank transfer turns off
            once your PayHere account is active and ready for checkout.
          </AlertDescription>
        </Alert>
      ) : null}
      {errorMessage ? (
        <Alert variant="destructive">
          <AlertCircle aria-hidden="true" />
          <AlertDescription>{errorMessage}</AlertDescription>
        </Alert>
      ) : null}
    </div>
  );
}
