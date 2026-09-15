import { z } from "zod";
import type { PlatformAuditLogSearchParams } from "@/lib/api/platform-admin-audit-log";

/**
 * Zod schema for the Platform Audit Log's date-range + action filter form
 * (`components/platform-admin/platform-audit-log-filter-form.tsx`). Adapted
 * from `lib/validation/audit-log.ts` — the one difference is there is no
 * `targetEntity` field here, since `GET /api/v1/platform-admin/audit-log`
 * accepts no such param (that's a tenant-scoped-only filter on the separate
 * endpoint). UX convenience only: the backend independently re-validates
 * (`400 VALIDATION_ERROR`) on every request, scoped here to the same one
 * cross-field rule the backend also enforces — `from` must not be after `to`.
 */
export const platformAuditLogFilterSchema = z
  .object({
    /** Bare `YYYY-MM-DD`, or empty string for "no lower bound". */
    from: z.string(),
    /** Bare `YYYY-MM-DD`, or empty string for "no upper bound". */
    to: z.string(),
    action: z.string(),
  })
  .refine((values) => !values.from || !values.to || values.from <= values.to, {
    message: '"From" date must not be after "to" date.',
    path: ["to"],
  });

export type PlatformAuditLogFilterFormValues = z.infer<typeof platformAuditLogFilterSchema>;

export const PLATFORM_AUDIT_LOG_FILTER_DEFAULT_VALUES: PlatformAuditLogFilterFormValues = {
  from: "",
  to: "",
  action: "",
};

/**
 * Converts a validated day-granularity filter selection into
 * `usePlatformAuditLog`/`usePlatformAuditLogTenantDrillDown`'s query params —
 * `from` becomes that day's start-of-day instant, `to` becomes that day's
 * end-of-day instant (`23:59:59.999`), both in UTC, mirroring
 * `lib/validation/audit-log.ts#toAuditLogQueryParams`'s exact convention.
 */
export function toPlatformAuditLogQueryParams(
  values: PlatformAuditLogFilterFormValues
): Pick<PlatformAuditLogSearchParams, "from" | "to" | "action"> {
  return {
    from: values.from ? `${values.from}T00:00:00.000Z` : undefined,
    to: values.to ? `${values.to}T23:59:59.999Z` : undefined,
    action: values.action ? values.action : undefined,
  };
}
