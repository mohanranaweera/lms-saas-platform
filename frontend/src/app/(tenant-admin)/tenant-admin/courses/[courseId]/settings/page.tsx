"use client";

import { useParams } from "next/navigation";
import { CourseWorkspaceShell } from "@/components/courses/course-workspace-shell";
import { CourseVisibilityControl } from "@/components/courses/course-visibility-control";
import { CourseArchiveControl } from "@/components/courses/course-archive-control";
import { CourseCloneAction } from "@/components/courses/course-clone-action";
import { CourseDeleteAction } from "@/components/courses/course-delete-action";
import { CourseChargesSection } from "@/components/courses/admin/course-charges-section";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { useKlassCourse } from "@/lib/api/klass-admin";
import { useAuth } from "@/lib/auth/auth-context";

/**
 * Tenant Admin course workspace — Settings tab (Wave 2). Consolidates
 * publish/unpublish, archive/unarchive, the course's facility fee and postage
 * (moved here from Overview), clone, and delete (Tenant-Admin-only).
 * Teacher assignment moved to the Overview edit form's teacher picker (G2);
 * the old UUID-typing reassignment form is gone.
 *
 * `isTenantAdmin` gates the Tenant-Admin-only delete action the same way the
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
          <CourseCharges courseId={course.id} />
          <CourseCloneAction courseId={course.id} course={course} />
          {isTenantAdmin ? <CourseDeleteAction courseId={course.id} course={course} /> : null}
        </div>
      )}
    </CourseWorkspaceShell>
  );
}

/** Facility fee and postage (V90): loads the full course record the section edits; saves on its own. */
function CourseCharges({ courseId }: { courseId: string }) {
  const query = useKlassCourse(courseId);
  return (
    <QueryStateBoundary
      query={query}
      loadingLabel="Loading facility fee and postage…"
      loginPath="/login"
      permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
      genericErrorMessage="The facility fee and postage settings could not be loaded."
    >
      {(klassCourse) => <CourseChargesSection key={`charges-${klassCourse.id}`} course={klassCourse} />}
    </QueryStateBoundary>
  );
}
