"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { ArrowLeft } from "lucide-react";
import { ModuleLessonEditor } from "@/components/courses/module-lesson-editor";
import { CourseWorkspaceShell } from "@/components/courses/course-workspace-shell";

/**
 * Tenant Admin course-scoped module, lesson, material and LessonMedia editor.
 * This is the editor that previously lived at `.../modules`; that route is
 * now the Klass-style Modules table, which links here ("Lesson materials &
 * videos") so material and Media Library management stays reachable.
 */
export default function TenantAdminCourseContentPage() {
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
            href={`/tenant-admin/courses/${courseId}/modules`}
            className="inline-flex w-fit items-center gap-1 text-sm font-medium text-muted-foreground hover:text-foreground hover:underline"
          >
            <ArrowLeft className="size-4" aria-hidden="true" />
            Back to modules
          </Link>
          <div>
            <h2 className="text-lg font-semibold text-foreground">Modules &amp; sessions</h2>
            <p className="text-sm text-muted-foreground">
              Session materials &amp; videos: edit the modules, sessions, materials, and Media Library videos for{" "}
              {course.name}.
            </p>
          </div>
          <ModuleLessonEditor courseId={course.id} />
        </div>
      )}
    </CourseWorkspaceShell>
  );
}
