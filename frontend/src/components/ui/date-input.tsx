"use client";

import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

/**
 * Shared "Date Input" primitive (`docs/ui-ux/component-library-spec.md`
 * §1.10). Per that spec's own "Open questions" §2 and the MVP-019 plan
 * (§11), the full popover calendar-picker sub-component is explicitly
 * deferred — this ships only the two labeled native `<input type="date">`
 * elements ("From"/"To") the Audit Log Viewer (this component's first
 * consumer) actually needs, following `components/attendance/attendance-
 * filter-form.tsx`'s existing hand-rolled date-range markup/class
 * conventions rather than inventing new ones.
 *
 * Value/convention: both `fromValue`/`toValue` and the `onFromChange`/
 * `onToChange` callbacks use bare `YYYY-MM-DD` strings (a native date input's
 * own value format) — never a full ISO instant. Callers convert to an
 * ISO-8601 instant only at query-build time, using a start-of-day (`from`)
 * / end-of-day (`to`) UTC convention — see
 * `lib/validation/audit-log.ts#toAuditLogQueryParams` and
 * `lib/validation/attendance.ts#toAttendanceQueryParams` for the established
 * precedent this mirrors.
 */
export interface DateInputProps {
  /** Unique per rendered instance, so ids don't collide if this component is ever rendered twice on one page. */
  idPrefix: string;
  fromLabel?: string;
  toLabel?: string;
  fromValue: string;
  toValue: string;
  onFromChange: (value: string) => void;
  onToChange: (value: string) => void;
  disabled?: boolean;
  /** Marks both inputs `aria-invalid` and wires `aria-describedby` to `errorId` — the caller renders the actual error text (e.g. a `role="alert"` paragraph with that same `id`). */
  invalid?: boolean;
  errorId?: string;
}

export function DateInput({
  idPrefix,
  fromLabel = "From",
  toLabel = "To",
  fromValue,
  toValue,
  onFromChange,
  onToChange,
  disabled = false,
  invalid = false,
  errorId,
}: DateInputProps) {
  return (
    <fieldset className="m-0 flex flex-col gap-1.5 border-0 p-0 sm:flex-row sm:gap-3">
      <legend className="sr-only">Date range</legend>
      <div className="flex flex-col gap-1.5">
        <Label htmlFor={`${idPrefix}-from`}>{fromLabel}</Label>
        <Input
          id={`${idPrefix}-from`}
          type="date"
          value={fromValue}
          onChange={(event) => onFromChange(event.target.value)}
          disabled={disabled}
          aria-invalid={invalid}
          aria-describedby={invalid ? errorId : undefined}
        />
      </div>
      <div className="flex flex-col gap-1.5">
        <Label htmlFor={`${idPrefix}-to`}>{toLabel}</Label>
        <Input
          id={`${idPrefix}-to`}
          type="date"
          value={toValue}
          onChange={(event) => onToChange(event.target.value)}
          disabled={disabled}
          aria-invalid={invalid}
          aria-describedby={invalid ? errorId : undefined}
        />
      </div>
    </fieldset>
  );
}
