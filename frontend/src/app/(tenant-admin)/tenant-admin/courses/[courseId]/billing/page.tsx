import { redirect } from "next/navigation";

/**
 * Former Fees & Billing tab. Removed by R2-D5: payment type and fee are edited
 * in the course edit form on the Overview tab, and a monthly fee change keeps
 * recording billing-period history server-side. The route stays reachable for
 * old links/bookmarks and lands on the course Overview.
 */
export default async function TenantAdminCourseBillingPage({
  params,
}: {
  params: Promise<{ courseId: string }>;
}) {
  const { courseId } = await params;
  redirect(`/tenant-admin/courses/${encodeURIComponent(courseId)}`);
}
