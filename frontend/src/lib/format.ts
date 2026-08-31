/**
 * Small shared formatting helpers. Kept minimal — no i18n/locale-switching
 * infrastructure exists yet, so these hardcode `en-US` the same way the one
 * prior ad hoc usage did (`(tenant-admin)/tenant-admin/teachers/[teacherId]/page.tsx`).
 */

export function formatDateTime(iso: string): string {
  return new Intl.DateTimeFormat("en-US", { dateStyle: "medium", timeStyle: "short" }).format(
    new Date(iso)
  );
}

export function formatMoney(amount: number, currency?: string | null): string {
  return currency ? `${amount.toFixed(2)} ${currency}` : amount.toFixed(2);
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
