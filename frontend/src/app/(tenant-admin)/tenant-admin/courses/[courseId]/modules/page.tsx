"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { ArrowLeft } from "lucide-react";
import { ModuleLessonEditor } from "@/components/courses/module-lesson-editor";
import { CourseWorkspaceShell } from "@/components/courses/course-workspace-shell";

/** Tenant Admin course-scoped module, lesson, material, and LessonMedia editor. */
export default function TenantAdminCourseModulesPage() {
  const params = useParams<{ courseId: string }>();
  const courseId = params.courseId;

  return (
    <CourseWorkspaceShell
      courseId={courseId}
      basePath={`/tenant-admin/courses/${courseId}`}
      dashboardHref="/tenant-admin/dashboard"
    >
      {(course) => (
        <div className="flex flex-col gap-4">
          <Link
            href={`/tenant-admin/courses/${courseId}`}
            className="inline-flex w-fit items-center gap-1 text-sm font-medium text-muted-foreground hover:text-foreground hover:underline"
          >
            <ArrowLeft className="size-4" aria-hidden="true" />
            Back to course overview
          </Link>
          <div>
            <h2 className="text-lg font-semibold text-foreground">Modules & lessons</h2>
            <p className="text-sm text-muted-foreground">
              Edit the modules, lessons, materials, and Media Library videos for {course.name}.
            </p>
          </div>
          <ModuleLessonEditor courseId={course.id} />
        </div>
      )}
    </CourseWorkspaceShell>
  );
}
