"use client";

import { useEffect } from "react";
import { useForm, useWatch } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Filter, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { DateInput } from "@/components/ui/date-input";
import {
  PLATFORM_AUDIT_LOG_FILTER_DEFAULT_VALUES,
  platformAuditLogFilterSchema,
  type PlatformAuditLogFilterFormValues,
} from "@/lib/validation/platform-audit-log";

/**
 * Starting suggestions only, surfaced via native `<datalist>` — mirrors
 * `components/audit-log/audit-log-filter-form.tsx`'s own convention. Never
 * validated client-side and never restricts what a caller can type; the
 * tenant-approval actions this platform-level log records are included here
 * alongside the already-shipped tenant-scoped actions.
 */
const KNOWN_ACTIONS = ["tenant.approved", "tenant.rejected"];

export interface PlatformAuditLogFilterFormProps {
  /** Unique per page instance, so ids don't collide if this form is ever rendered twice on one page. */
  idPrefix: string;
  /** Pre-populates the form, e.g. from the URL's query string on direct navigation/back-forward. Defaults to the all-empty state. */
  initialValues?: PlatformAuditLogFilterFormValues;
  /** Raw, bare-`YYYY-MM-DD`/exact-string form values — the caller (the page) owns converting these to `usePlatformAuditLog`'s ISO-instant query params via `toPlatformAuditLogQueryParams`, and owns URL-syncing them. */
  onApply: (values: PlatformAuditLogFilterFormValues) => void;
  onClear: () => void;
  /** Disables Apply/Clear while the list query they drive is already fetching, so a rapid double-click can't fire overlapping requests. Defaults to `false`. */
  disabled?: boolean;
}

/**
 * Date-range + action filter form for the Platform Audit Log and its
 * per-tenant drill-down. Adapted from
 * `components/audit-log/audit-log-filter-form.tsx` — deliberately has no
 * `targetEntity` field, since `GET /api/v1/platform-admin/audit-log` (and its
 * `/tenants/{tenantId}` drill-down) accepts no such param, unlike the
 * tenant-scoped Audit Log Viewer this form is adapted from.
 */
export function PlatformAuditLogFilterForm({
  idPrefix,
  initialValues = PLATFORM_AUDIT_LOG_FILTER_DEFAULT_VALUES,
  onApply,
  onClear,
  disabled = false,
}: PlatformAuditLogFilterFormProps) {
  const {
    register,
    control,
    setValue,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<PlatformAuditLogFilterFormValues>({
    resolver: zodResolver(platformAuditLogFilterSchema),
    defaultValues: initialValues,
  });

  // Keeps the form in sync when `initialValues` changes out from under an
  // already-mounted form instance — browser back/forward navigates the URL
  // directly, bypassing this form's own `onApply`/`handleClear` handlers.
  useEffect(() => {
    reset(initialValues);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- `reset` is stable per RHF's own contract; re-running only when `initialValues` itself changes (the page memoizes it by value, not by reference-per-render) is the intended behavior.
  }, [initialValues]);

  const from = useWatch({ control, name: "from" });
  const to = useWatch({ control, name: "to" });
  const errorId = `${idPrefix}-date-range-error`;

  const onSubmit = handleSubmit((values) => {
    onApply(values);
  });

  function handleClear() {
    reset(PLATFORM_AUDIT_LOG_FILTER_DEFAULT_VALUES);
    onClear();
  }

  return (
    <form
      noValidate
      onSubmit={onSubmit}
      className="flex flex-col gap-3 rounded-lg border border-border p-4 sm:flex-row sm:flex-wrap sm:items-end"
    >
      <DateInput
        idPrefix={idPrefix}
        fromValue={from}
        toValue={to}
        onFromChange={(value) => setValue("from", value, { shouldValidate: true })}
        onToChange={(value) => setValue("to", value, { shouldValidate: true })}
        disabled={disabled}
        invalid={Boolean(errors.to)}
        errorId={errorId}
      />
      {errors.to ? (
        <p id={errorId} role="alert" className="text-xs text-destructive sm:basis-full">
          {errors.to.message}
        </p>
      ) : null}

      <div className="flex flex-col gap-1.5 sm:min-w-40">
        <Label htmlFor={`${idPrefix}-action`}>Action</Label>
        <Input
          id={`${idPrefix}-action`}
          list={`${idPrefix}-action-options`}
          placeholder="Any action"
          disabled={disabled}
          {...register("action")}
        />
        <datalist id={`${idPrefix}-action-options`}>
          {KNOWN_ACTIONS.map((action) => (
            <option key={action} value={action} />
          ))}
        </datalist>
      </div>

      <div className="flex gap-2">
        <Button type="submit" size="sm" disabled={disabled} aria-busy={disabled}>
          <Filter aria-hidden="true" />
          Apply filters
        </Button>
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={handleClear}
          disabled={disabled}
          aria-busy={disabled}
        >
          <X aria-hidden="true" />
          Clear filters
        </Button>
      </div>
    </form>
  );
}
