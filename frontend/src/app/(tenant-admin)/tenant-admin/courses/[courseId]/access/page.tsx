"use client";

import { useParams } from "next/navigation";
import { CourseWorkspaceShell } from "@/components/courses/course-workspace-shell";
import { CourseAccessForm } from "@/components/courses/course-access-form";

/**
 * Tenant Admin course workspace — Access tab (Wave 2). A dedicated read/edit
 * view for `accessDurationDays`/`enrollmentRule` — both already present on
 * `CourseResponse`/`CourseUpdateRequest`, previously buried inside the
 * general edit form's "Enrollment & access" step. No backend change needed.
 */
export default function TenantAdminCourseAccessPage() {
  const params = useParams<{ courseId: string }>();
  const courseId = params.courseId;

  return (
    <CourseWorkspaceShell
      courseId={courseId}
      basePath={`/tenant-admin/courses/${courseId}`}
      dashboardHref="/tenant-admin/dashboard"
    >
      {(course) => <CourseAccessForm courseId={course.id} course={course} />}
    </CourseWorkspaceShell>
  );
}
