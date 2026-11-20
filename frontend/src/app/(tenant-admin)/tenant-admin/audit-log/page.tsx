import { TenantAdminAuditLogPageClient } from "./page-client";

/**
 * Reading `searchParams` opts this route out of static prerendering. As a
 * static route, a production build's client router reused one cached entry -
 * with its canonical URL - for `/tenant-admin/audit-log` and its query-string variants, so a
 * router.replace/push that changes the query wrote the previous one straight
 * back. The page still reads its state client-side from the URL.
 */
export default async function TenantAdminAuditLogPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  await searchParams;
  return <TenantAdminAuditLogPageClient />;
}
