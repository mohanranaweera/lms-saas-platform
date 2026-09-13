import { z } from "zod";
import type { AuditLogSearchParams } from "@/lib/api/audit-log";

/**
 * Zod schema for the Audit Log Viewer's date-range + action + target-entity
 * filter form (`components/audit-log/audit-log-filter-form.tsx`). Mirrors
 * `lib/validation/attendance.ts`'s style exactly (per `.claude/rules/
 * frontend.md`'s "every form uses RHF + a Zod schema" rule): this is UX
 * convenience only (the backend's `AuditLogSearchCriteria` compact
 * constructor remains the sole authority and independently re-validates,
 * `400 VALIDATION_ERROR`, on every request), scoped here to the one
 * cross-field rule the backend also enforces — `from` must not be after `to`
 * (plan §12).
 *
 * `from`/`to` are plain form-level strings (native `<input type="date">`
 * values, i.e. bare `YYYY-MM-DD`) so a controlled `Input` always has a
 * defined value; `toAuditLogQueryParams` below converts a day-granularity
 * selection to the full ISO-8601 `Instant` strings `useAuditLogSearch`
 * actually requires (start-of-day for `from`, end-of-day for `to`) only at
 * query-build time — the same convention
 * `lib/validation/attendance.ts#toAttendanceQueryParams` already establishes.
 */

/**
 * Unit-test note (post-ship review finding #13): this project has no
 * Vitest/Jest (or similar) unit-test runner configured anywhere in
 * `frontend/` (only `playwright test`, see `package.json`'s `scripts`) and no
 * precedent for a standalone unit-test file under `lib/validation/`.
 * `toAuditLogQueryParams` and `auditLogFilterSchema`'s `.refine()` are
 * therefore covered end-to-end instead: `e2e/audit-log.spec.ts`'s
 * "date-range, action, and target-entity filters are reflected in the
 * outgoing request" test exercises `toAuditLogQueryParams`'s conversion via
 * the real outgoing query string, and its "filter form blocks submission
 * when 'from' is after 'to'" test exercises the schema's `.refine()` rule
 * directly through the rendered form. Adding a Vitest config for this one
 * file would be a larger, out-of-scope infra change.
 */
export const auditLogFilterSchema = z
  .object({
    /** Bare `YYYY-MM-DD`, or empty string for "no lower bound". */
    from: z.string(),
    /** Bare `YYYY-MM-DD`, or empty string for "no upper bound". */
    to: z.string(),
    action: z.string(),
    targetEntity: z.string(),
  })
  .refine((values) => !values.from || !values.to || values.from <= values.to, {
    message: '"From" date must not be after "to" date.',
    path: ["to"],
  });

export type AuditLogFilterFormValues = z.infer<typeof auditLogFilterSchema>;

export const AUDIT_LOG_FILTER_DEFAULT_VALUES: AuditLogFilterFormValues = {
  from: "",
  to: "",
  action: "",
  targetEntity: "",
};

/**
 * Converts a validated day-granularity filter selection into
 * `useAuditLogSearch`'s query params: `from` becomes that day's start-of-day
 * instant, `to` becomes that day's end-of-day instant (`23:59:59.999`), both
 * in UTC. Blank fields are omitted rather than sent as empty strings.
 */
export function toAuditLogQueryParams(
  values: AuditLogFilterFormValues
): Pick<AuditLogSearchParams, "from" | "to" | "action" | "targetEntity"> {
  return {
    from: values.from ? `${values.from}T00:00:00.000Z` : undefined,
    to: values.to ? `${values.to}T23:59:59.999Z` : undefined,
    action: values.action ? values.action : undefined,
    targetEntity: values.targetEntity ? values.targetEntity : undefined,
  };
}
