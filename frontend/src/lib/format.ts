/**
 * Small shared formatting helpers. They default to `en-US` (the historical
 * output); I18N-01's `useLocaleFormat()` (`src/i18n/use-locale-format.ts`)
 * passes the resolved UI locale on localized surfaces. Only presentation
 * changes - the amount/currency/instant are never altered.
 */

export function formatDateTime(iso: string, locale?: string): string {
  return new Intl.DateTimeFormat(locale ?? "en-US", { dateStyle: "medium", timeStyle: "short" }).format(
    new Date(iso)
  );
}

export function formatMoney(amount: number, currency?: string | null, locale?: string): string {
  const value = locale
    ? new Intl.NumberFormat(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(amount)
    : amount.toFixed(2);
  return currency ? `${value} ${currency}` : value;
}

/**
 * Renders a short id fragment in place of a display name. MVP-012's approved
 * workaround for a confirmed API gap: there is no name-resolution endpoint
 * reachable for either a Student caller (`GET /api/v1/courses/{id}` 403s —
 * `STUDENT` is absent from `CourseAccessGuard`'s matrix) or a staff caller in
 * the reactivation queue (`ReactivationRequestResponse` carries no `courseId`
 * at all, only `enrollmentId`). Callers must never call a name-lookup
 * endpoint speculatively from these surfaces — render this instead.
 * `label` defaults to `"Course"` (the common case); pass `"Enrollment"` for
 * reactivation-request rows, which only ever carry an `enrollmentId`.
 */
export function shortId(id: string, label: string = "Course"): string {
  return `${label} #${id.slice(0, 8)}`;
}

/**
 * Renders a raw backend role enum value (e.g. `TENANT_ADMIN`) as a
 * human-readable label ("Tenant Admin") for display-only copy such as a
 * role-context banner. Never used for any authorization decision — role
 * comparisons elsewhere always compare against the raw enum string
 * (`lib/auth/permissions.ts`).
 */
export function formatRoleLabel(role: string): string {
  return role
    .toLowerCase()
    .split("_")
    .map((word) => (word.length > 0 ? word.charAt(0).toUpperCase() + word.slice(1) : word))
    .join(" ");
}
