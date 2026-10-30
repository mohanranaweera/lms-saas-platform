"use client";

import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { ErrorState } from "@/components/states/error-state";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { useAuth } from "@/lib/auth/auth-context";
import { canManageInstituteConfig } from "@/lib/auth/permissions";
import { isApiClientError } from "@/lib/api/error";
import {
  useTenantConfigDomain,
  useUpdateTenantConfigDomain,
  type TenantConfigProperty,
} from "@/lib/api/tenant-config";
import {
  CURRENCY_OPTIONS,
  configValue,
  currencyConfigSchema,
  type CurrencyConfigFormValues,
} from "@/lib/validation/tenant-config";
import { PaymentSettingsCard } from "./payment-settings-card";

const SELECT_CLASS =
  "h-9 w-full rounded-lg border border-input bg-transparent px-2.5 py-1 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 disabled:cursor-not-allowed disabled:opacity-50 dark:bg-input/30";

function currentCurrency(properties: TenantConfigProperty[]): string {
  const value = configValue(properties, "default_currency");
  return typeof value === "string" && value ? value : "USD";
}

/**
 * Settings → Payments → Currency (SET-PAY-01; moved from the General tab).
 * Edits `GENERAL.default_currency` — same key and backend validation as
 * before (`BRANDING_SETTINGS` VIEW/CREATE_EDIT, any `^[A-Z]{3}$` code). New
 * orders, receipts and reports snapshot the currency, so payments already
 * recorded keep theirs.
 */
export function CurrencySection() {
  const { session } = useAuth();
  const canManage = canManageInstituteConfig(session?.role ?? null);
  const query = useTenantConfigDomain("GENERAL");

  return (
    <PaymentSettingsCard
      id="currency"
      title="Currency settings"
      testId="currency-settings"
      action={query.data ? <Badge variant="secondary">{currentCurrency(query.data)}</Badge> : null}
    >
      <QueryStateBoundary
        query={query}
        loadingLabel="Loading currency settings…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
        genericErrorMessage="The currency settings could not be loaded."
      >
        {(properties) => <CurrencyForm properties={properties} canManage={canManage} />}
      </QueryStateBoundary>
    </PaymentSettingsCard>
  );
}

function CurrencyForm({ properties, canManage }: { properties: TenantConfigProperty[]; canManage: boolean }) {
  const updateMutation = useUpdateTenantConfigDomain("GENERAL");
  const current = currentCurrency(properties);
  const [pageError, setPageError] = useState<{ message: string; code?: string } | null>(null);
  const [saved, setSaved] = useState(false);
  const pending = updateMutation.isPending;
  // A stored code outside the curated list stays selectable.
  const options = CURRENCY_OPTIONS.some((option) => option.code === current)
    ? CURRENCY_OPTIONS
    : [...CURRENCY_OPTIONS, { code: current, label: current }];

  const {
    register,
    handleSubmit,
    setError,
    reset,
    formState: { errors, isDirty },
  } = useForm<CurrencyConfigFormValues>({
    resolver: zodResolver(currencyConfigSchema),
    defaultValues: { default_currency: current },
  });

  const onSubmit = handleSubmit(async (values) => {
    setPageError(null);
    setSaved(false);
    if (values.default_currency === current) {
      reset(values);
      return;
    }
    try {
      const updated = await updateMutation.mutateAsync({ default_currency: values.default_currency });
      reset({ default_currency: currentCurrency(updated) });
      setSaved(true);
    } catch (error) {
      if (isApiClientError(error)) {
        const fieldError = error.fieldErrors.find((entry) => entry.field === "default_currency");
        if (fieldError) {
          setError("default_currency", { type: "server", message: fieldError.message });
        } else if (error.status === 403) {
          setPageError({ message: "You don't have permission to change the currency.", code: error.code });
        } else {
          setPageError({ message: error.message, code: error.code });
        }
        return;
      }
      setPageError({ message: "An unexpected error occurred. Please try again." });
    }
  });

  return (
    <form onSubmit={onSubmit} noValidate aria-busy={pending} className="flex max-w-xl flex-col gap-4">
      {pageError ? <ErrorState message={pageError.message} code={pageError.code} /> : null}
      <div className="flex flex-col gap-1.5">
        <Label htmlFor="default-currency">Currency students are charged in</Label>
        <select
          id="default-currency"
          className={SELECT_CLASS}
          disabled={!canManage || pending}
          aria-invalid={errors.default_currency ? true : undefined}
          aria-describedby={`default-currency-help${errors.default_currency ? " default-currency-error" : ""}`}
          {...register("default_currency")}
        >
          {options.map((option) => (
            <option key={option.code} value={option.code}>
              {option.label}
            </option>
          ))}
        </select>
        <p id="default-currency-help" className="text-xs text-muted-foreground">
          Applies to every course, receipt and report from here on. Payments already recorded keep the currency they
          were taken in.
        </p>
        {errors.default_currency ? (
          <p id="default-currency-error" role="alert" className="text-sm text-destructive">
            {errors.default_currency.message}
          </p>
        ) : null}
      </div>
      {canManage ? (
        <div className="flex flex-wrap items-center gap-3">
          <Button type="submit" disabled={pending || !isDirty} aria-busy={pending} data-testid="currency-save">
            {pending ? "Saving…" : "Save currency"}
          </Button>
          {saved ? (
            <p role="status" className="text-sm text-muted-foreground">
              Currency saved.
            </p>
          ) : null}
        </div>
      ) : (
        <p className="text-sm text-muted-foreground">Only a Tenant Admin can change the currency.</p>
      )}
    </form>
  );
}
