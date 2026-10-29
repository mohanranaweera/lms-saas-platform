import { redirect } from "next/navigation";

/** Legacy route: current Tenant Admin create workflow lives in the Courses list Add course dialog. */
export default function LegacyTenantAdminNewCoursePage() {
  redirect("/tenant-admin/courses");
}
