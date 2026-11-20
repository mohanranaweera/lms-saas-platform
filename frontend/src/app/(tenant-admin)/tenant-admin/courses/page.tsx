"use client";

import { CourseListPanel } from "@/components/courses/admin/course-list-panel";

/**
 * Tenant Admin "Courses" list (Klass parity, CM-A01 / EP-C01–C09). The
 * server scopes `GET /api/v1/courses` to the caller's tenant and role; this
 * page only renders what it returns. Each row links into the existing course
 * workspace (`/tenant-admin/courses/[courseId]` and its Fees & Billing,
 * Settings, Roster, Access, Sessions … tabs) through the Edit action, and to
 * the Klass-style Modules table through the Modules action.
 */
export default function TenantAdminCoursesPage() {
  return <CourseListPanel />;
}
