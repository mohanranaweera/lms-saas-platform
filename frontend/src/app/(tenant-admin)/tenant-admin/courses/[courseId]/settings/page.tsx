"use client";

import { useParams } from "next/navigation";
import { CourseWorkspaceShell } from "@/components/courses/course-workspace-shell";
import { CourseVisibilityControl } from "@/components/courses/course-visibility-control";
import { CourseTeacherReassignForm } from "@/components/courses/course-teacher-reassign-form";
import { CourseArchiveControl } from "@/components/courses/course-archive-control";
import { CourseCloneAction } from "@/components/courses/course-clone-action";
import { CourseDeleteAction } from "@/components/courses/course-delete-action";
import { useAuth } from "@/lib/auth/auth-context";

/**
 * Tenant Admin course workspace — Settings tab (Wave 2). Consolidates
 * publish/unpublish, teacher reassignment (Tenant-Admin-only, unchanged),
 * archive/unarchive (new), clone (new), and delete (Tenant-Admin-only,
 * unchanged) — moved here from the old flat detail page.
 *
 * `isTenantAdmin` gates the two Tenant-Admin-only actions the same way the
 * pre-Wave-2 page did: a UX convenience only (a Teacher who directly
 * navigates here for a course they own would otherwise see these
 * non-functional for them — the backend independently 403s both regardless),
 * never the authorization boundary itself (`frontend/CLAUDE.md`).
 */
export default function TenantAdminCourseSettingsPage() {
  const params = useParams<{ courseId: string }>();
  const courseId = params.courseId;
  const { session } = useAuth();
  const isTenantAdmin = session?.role === "TENANT_ADMIN";

  return (
    <CourseWorkspaceShell
      courseId={courseId}
      basePath={`/tenant-admin/courses/${courseId}`}
      dashboardHref="/tenant-admin/dashboard"
    >
      {(course) => (
        <div className="flex flex-col gap-4">
          <div className="grid gap-4 sm:grid-cols-2">
            <CourseVisibilityControl courseId={course.id} course={course} />
            <CourseArchiveControl courseId={course.id} course={course} />
          </div>
          {isTenantAdmin ? <CourseTeacherReassignForm courseId={course.id} course={course} /> : null}
          <CourseCloneAction courseId={course.id} course={course} />
          {isTenantAdmin ? <CourseDeleteAction courseId={course.id} course={course} /> : null}
        </div>
      )}
    </CourseWorkspaceShell>
  );
}
