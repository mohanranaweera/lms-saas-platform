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
  AUDIT_LOG_FILTER_DEFAULT_VALUES,
  auditLogFilterSchema,
  type AuditLogFilterFormValues,
} from "@/lib/validation/audit-log";

/**
 * Starting suggestions only, surfaced via native `<datalist>` — per
 * `docs/api/audit-log-management.md`, the backend accepts any `action`/
 * `targetEntity` string and simply returns zero rows for a value that
 * matches nothing (deliberate anti-enumeration behavior, plan §12). These
 * are never validated against client-side and never restrict what a caller
 * can type; free text for any other already-shipped module's action/target
 * (slip review, reactivation requests, exam scheduling/marking/results) is
 * always accepted.
 */
const KNOWN_ACTIONS = ["course.price_changed", "material.deleted", "payment.refunded"];
const KNOWN_TARGET_ENTITIES = ["course", "material", "payment_refund"];

export interface AuditLogFilterFormProps {
  /** Unique per page instance, so ids don't collide if this form is ever rendered twice on one page. */
  idPrefix: string;
  /**
   * Pre-populates the form, e.g. from the URL's query string on direct
   * navigation/back-forward (`app/(tenant-admin)/tenant-admin/audit-log/
   * page.tsx`'s URL-sync). Defaults to the all-empty state. When this prop's
   * value changes after mount (browser back/forward changing the URL out
   * from under an already-mounted form), the form resets to match — see the
   * `useEffect` below.
   */
  initialValues?: AuditLogFilterFormValues;
  /** Raw, bare-`YYYY-MM-DD`/exact-string form values — the caller (the page) owns converting these to `useAuditLogSearch`'s ISO-instant query params via `toAuditLogQueryParams`, and owns URL-syncing them. */
  onApply: (values: AuditLogFilterFormValues) => void;
  onClear: () => void;
  /** Disables Apply/Clear while the list query they drive is already fetching, so a rapid double-click can't fire overlapping requests. Defaults to `false`. */
  disabled?: boolean;
}

/**
 * Date-range + action + target-entity filter form for the Tenant Admin Audit
 * Log Viewer. React Hook Form + Zod (`lib/validation/audit-log.ts`) per
 * `.claude/rules/frontend.md`'s "every form uses RHF + a Zod schema" rule,
 * following `components/attendance/attendance-filter-form.tsx`'s exact
 * pattern — the only client-side rule enforced (`from` must not be after
 * `to`) mirrors the backend's own `400 VALIDATION_ERROR` rule as a UX
 * convenience only. `DateInput` takes raw string values/callbacks rather
 * than a native input's change event, so its two fields are wired via
 * `useWatch`/`setValue` rather than `register` — the same reason
 * `attendance-filter-form.tsx`'s course `Select` can't use `register`
 * directly.
 *
 * This form is a controlled *view* over the page's own URL-derived filter
 * state, not the source of truth itself: `onApply`/`onClear` hand raw,
 * validated form values up to the page, which owns converting them to API
 * query params (`toAuditLogQueryParams`) and syncing them into the URL's
 * query string (`app/(tenant-admin)/tenant-admin/audit-log/page.tsx`'s
 * URL-sync). `initialValues` flows the other direction — from the URL back
 * into this form — so a direct navigation or a browser back/forward always
 * re-renders this form to match what the URL actually says.
 */
export function AuditLogFilterForm({
  idPrefix,
  initialValues = AUDIT_LOG_FILTER_DEFAULT_VALUES,
  onApply,
  onClear,
  disabled = false,
}: AuditLogFilterFormProps) {
  const {
    register,
    control,
    setValue,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<AuditLogFilterFormValues>({
    resolver: zodResolver(auditLogFilterSchema),
    defaultValues: initialValues,
  });

  // Keeps the form in sync when `initialValues` changes out from under an
  // already-mounted form instance — the browser back/forward buttons
  // navigate the URL directly, bypassing this form's own `onApply`/
  // `handleClear` handlers entirely, so this is the only path that re-syncs
  // the rendered fields in that case (plan addition: URL-sync, item 3).
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
    reset(AUDIT_LOG_FILTER_DEFAULT_VALUES);
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

      <div className="flex flex-col gap-1.5 sm:min-w-40">
        <Label htmlFor={`${idPrefix}-target-entity`}>Target entity</Label>
        <Input
          id={`${idPrefix}-target-entity`}
          list={`${idPrefix}-target-entity-options`}
          placeholder="Any target"
          disabled={disabled}
          {...register("targetEntity")}
        />
        <datalist id={`${idPrefix}-target-entity-options`}>
          {KNOWN_TARGET_ENTITIES.map((entity) => (
            <option key={entity} value={entity} />
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
