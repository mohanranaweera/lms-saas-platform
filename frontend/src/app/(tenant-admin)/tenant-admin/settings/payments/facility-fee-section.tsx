"use client";

import { useState } from "react";
import { useForm, useWatch } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { ErrorState } from "@/components/states/error-state";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
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
  FACILITY_FEE_MODES,
  facilityFeeConfigSchema,
  facilityFeeFormValues,
  toFacilityFeeChanges,
  type FacilityFeeConfigFormValues,
  type FacilityFeeMode,
} from "@/lib/validation/tenant-config";

const MODE_LABELS: Record<FacilityFeeMode, { title: string; help: string }> = {
  NONE: { title: "No facility fee", help: "Students pay only course fees and any postage." },
  PER_COURSE: {
    title: "Once per course",
    help: "Each course in a payment adds its facility fee once. Courses can set their own fee.",
  },
  PER_LINE: {
    title: "On every item",
    help: "Every month, module or session paid for adds the course's facility fee. Courses can set their own fee.",
  },
  PER_ORDER: {
    title: "Once per payment",
    help: "The default fee is added once per payment, however many courses it covers. Course fees are not used.",
  },
};

/**
 * Tenant Admin → Payments → Facility fee (multi-course checkout, V90). Reads
 * and writes the existing tenant config `PAYMENT` domain
 * (`facility_fee_mode`, `facility_fee_amount`) - `BRANDING_SETTINGS`
 * VIEW/CREATE_EDIT server-side. `canManageInstituteConfig` only decides
 * whether the form is editable (UX); the backend re-checks every `PUT`, and a
 * 403 on the read renders the permission-denied state in place. Changes only
 * affect future quotes; existing orders keep their snapshot.
 */
export function FacilityFeeSection() {
  const { session } = useAuth();
  const canManage = canManageInstituteConfig(session?.role ?? null);
  const query = useTenantConfigDomain("PAYMENT");

  return (
    <section aria-labelledby="facility-fee-heading" className="flex max-w-2xl flex-col gap-4" data-testid="facility-fee-settings">
      <div>
        <h2 id="facility-fee-heading" className="text-base font-medium text-foreground">
          Facility fee
        </h2>
        <p className="text-sm text-muted-foreground">
          An extra fee added at checkout on top of course fees. Changes apply to new payments only.
        </p>
      </div>
      <QueryStateBoundary
        query={query}
        loadingLabel="Loading facility fee settings…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
        genericErrorMessage="The facility fee settings could not be loaded."
      >
        {(properties) => <FacilityFeeForm properties={properties} canManage={canManage} />}
      </QueryStateBoundary>
    </section>
  );
}

function FacilityFeeForm({ properties, canManage }: { properties: TenantConfigProperty[]; canManage: boolean }) {
  const updateMutation = useUpdateTenantConfigDomain("PAYMENT");
  const [current, setCurrent] = useState<FacilityFeeConfigFormValues>(() => facilityFeeFormValues(properties));
  const [pageError, setPageError] = useState<{ message: string; code?: string } | null>(null);
  const [saved, setSaved] = useState(false);

  const {
    register,
    handleSubmit,
    setError,
    reset,
    control,
    formState: { errors, isDirty },
  } = useForm<FacilityFeeConfigFormValues>({
    resolver: zodResolver(facilityFeeConfigSchema),
    defaultValues: current,
  });
  const mode = useWatch({ control, name: "facility_fee_mode" });
  const pending = updateMutation.isPending;

  const onSubmit = handleSubmit(async (values) => {
    setPageError(null);
    setSaved(false);
    const changes = toFacilityFeeChanges(values, current);
    if (Object.keys(changes).length === 0) {
      reset(values);
      return;
    }
    try {
      const result = await updateMutation.mutateAsync(changes);
      const next = facilityFeeFormValues(result);
      setCurrent(next);
      reset(next);
      setSaved(true);
    } catch (error) {
      if (isApiClientError(error)) {
        let mapped = false;
        for (const fieldError of error.fieldErrors) {
          if (fieldError.field === "facility_fee_mode" || fieldError.field === "facility_fee_amount") {
            setError(fieldError.field, { type: "server", message: fieldError.message });
            mapped = true;
          }
        }
        if (error.status === 403) {
          setPageError({ message: "You don't have permission to change the facility fee.", code: error.code });
        } else if (!mapped) {
          setPageError({ message: error.message, code: error.code });
        }
        return;
      }
      setPageError({ message: "An unexpected error occurred. Please try again." });
    }
  });

  if (!canManage) {
    return (
      <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 rounded-lg border border-border p-4 text-sm" data-testid="facility-fee-readonly">
        <dt className="text-muted-foreground">Charged</dt>
        <dd className="text-foreground">{MODE_LABELS[current.facility_fee_mode].title}</dd>
        <dt className="text-muted-foreground">Default amount</dt>
        <dd className="text-foreground">{current.facility_fee_amount}</dd>
        <dd className="col-span-2 pt-1 text-xs text-muted-foreground">Only a Tenant Admin can change these settings.</dd>
      </dl>
    );
  }

  return (
    <form onSubmit={onSubmit} noValidate aria-busy={pending} className="flex flex-col gap-4 rounded-lg border border-border p-4">
      {pageError ? <ErrorState message={pageError.message} code={pageError.code} /> : null}

      <fieldset disabled={pending} className="flex flex-col gap-2">
        <legend className="text-sm font-medium text-foreground">How the facility fee is charged</legend>
        {FACILITY_FEE_MODES.map((value) => (
          <label key={value} className="flex items-start gap-2 text-sm">
            <input
              type="radio"
              value={value}
              className="mt-1"
              aria-describedby={`facility-fee-mode-${value}-help`}
              data-testid={`facility-fee-mode-${value}`}
              {...register("facility_fee_mode")}
            />
            <span className="flex flex-col">
              <span className="text-foreground">{MODE_LABELS[value].title}</span>
              <span id={`facility-fee-mode-${value}-help`} className="text-xs text-muted-foreground">
                {MODE_LABELS[value].help}
              </span>
            </span>
          </label>
        ))}
        {errors.facility_fee_mode ? (
          <p role="alert" className="text-sm text-destructive">
            {errors.facility_fee_mode.message}
          </p>
        ) : null}
      </fieldset>

      <div className="flex flex-col gap-1.5">
        <Label htmlFor="facility-fee-amount">Default facility fee</Label>
        <Input
          id="facility-fee-amount"
          inputMode="decimal"
          autoComplete="off"
          disabled={pending}
          aria-invalid={errors.facility_fee_amount ? true : undefined}
          aria-describedby={`facility-fee-amount-help${errors.facility_fee_amount ? " facility-fee-amount-error" : ""}`}
          data-testid="facility-fee-amount"
          {...register("facility_fee_amount")}
        />
        <p id="facility-fee-amount-help" className="text-xs text-muted-foreground">
          {mode === "NONE"
            ? "Kept for later; no facility fee is charged while this is set to No facility fee."
            : "0 or more, up to 2 decimals. Courses using the institute default are charged this amount."}
        </p>
        {errors.facility_fee_amount ? (
          <p id="facility-fee-amount-error" role="alert" className="text-sm text-destructive">
            {errors.facility_fee_amount.message}
          </p>
        ) : null}
      </div>

      <div className="flex flex-wrap items-center gap-3">
        <Button type="submit" disabled={pending || !isDirty} aria-busy={pending} data-testid="facility-fee-save">
          {pending ? "Saving…" : "Save facility fee"}
        </Button>
        {saved ? (
          <p role="status" className="text-sm text-muted-foreground">
            Facility fee saved. New payments use it from now on.
          </p>
        ) : null}
      </div>
    </form>
  );
}
