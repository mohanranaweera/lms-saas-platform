"use client";

import { useParams } from "next/navigation";
import { CourseWorkspaceShell } from "@/components/courses/course-workspace-shell";
import { CourseBillingPanel } from "@/components/courses/course-billing-panel";

/**
 * Tenant Admin course workspace — Fees & Billing tab (Wave 2). Content is
 * entirely driven by `course.pricingModel` — see `CourseBillingPanel`'s doc
 * comment for the per-model breakdown (`FREE`/`ONE_TIME`/`MONTHLY`/
 * `SESSION`/`CUSTOM`).
 */
export default function TenantAdminCourseBillingPage() {
  const params = useParams<{ courseId: string }>();
  const courseId = params.courseId;

  return (
    <CourseWorkspaceShell
      courseId={courseId}
      basePath={`/tenant-admin/courses/${courseId}`}
      dashboardHref="/tenant-admin/dashboard"
    >
      {(course) => <CourseBillingPanel courseId={courseId} course={course} />}
    </CourseWorkspaceShell>
  );
}
