"use client";

import { useParams } from "next/navigation";
import { CourseWorkspaceShell } from "@/components/courses/course-workspace-shell";
import { ClassSessionCreateForm } from "@/components/live-classes/class-session-create-form";

/** Tenant Admin course-scoped live-class scheduling, using the shared backend-authoritative form. */
export default function TenantAdminNewCourseLiveClassPage() {
  const params = useParams<{ courseId: string }>();
  const courseId = params.courseId;
  const coursePath = `/tenant-admin/courses/${courseId}`;

  return (
    <CourseWorkspaceShell
      courseId={courseId}
      basePath={coursePath}
      dashboardHref="/tenant-admin/dashboard"
    >
      {(course) => (
        <div className="flex flex-col gap-6">
          <div>
            <h2 className="text-lg font-semibold text-foreground">Schedule a live class</h2>
            <p className="text-sm text-muted-foreground">
              Schedule a Zoom-backed live class for {course.name}.
            </p>
          </div>
          <div className="w-full max-w-xl">
            <ClassSessionCreateForm
              backHref={`${coursePath}/sessions`}
              successPath="/tenant-admin/live-classes"
              initialCourseId={courseId}
            />
          </div>
        </div>
      )}
    </CourseWorkspaceShell>
  );
}
