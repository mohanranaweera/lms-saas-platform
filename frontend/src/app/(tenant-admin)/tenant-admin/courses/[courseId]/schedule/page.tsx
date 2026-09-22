"use client";

import { useParams } from "next/navigation";
import { CourseWorkspaceShell } from "@/components/courses/course-workspace-shell";
import { CourseWorkspacePlaceholder } from "@/components/courses/course-workspace-placeholder";

/** Deliberate Wave 2 scope-boundary placeholder — see `CourseWorkspacePlaceholder`'s doc comment. */
export default function TenantAdminCourseSchedulePage() {
  const params = useParams<{ courseId: string }>();
  const courseId = params.courseId;

  return (
    <CourseWorkspaceShell
      courseId={courseId}
      basePath={`/tenant-admin/courses/${courseId}`}
      dashboardHref="/tenant-admin/dashboard"
    >
      {() => (
        <CourseWorkspacePlaceholder
          title="Schedule isn't available yet"
          description="Class scheduling for this course will live here in a later release."
        />
      )}
    </CourseWorkspaceShell>
  );
}
