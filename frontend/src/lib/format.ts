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
