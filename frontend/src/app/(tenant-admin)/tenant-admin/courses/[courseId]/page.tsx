"use client";

import { useParams } from "next/navigation";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { CourseEditForm } from "@/components/courses/course-edit-form";
import { CourseVisibilityControl } from "@/components/courses/course-visibility-control";
import { CoursePriceChangeForm } from "@/components/courses/course-price-change-form";
import { CourseTeacherReassignForm } from "@/components/courses/course-teacher-reassign-form";
import { CourseDeleteAction } from "@/components/courses/course-delete-action";
import { useCourse } from "@/lib/api/courses";
import { useAuth } from "@/lib/auth/auth-context";

/**
 * Tenant Admin course detail / approval surface. Composes the same
 * structural edit form Teacher uses (`CourseEditForm`) plus visibility and
 * price actions shared with the Teacher edit page, and additionally the two
 * Tenant-Admin-only actions (`CourseTeacherReassignForm`,
 * `CourseDeleteAction`) that must never render in Teacher UI.
 *
 * `GET /api/v1/courses/{id}` succeeds for a Teacher requesting their own
 * course (ownership-scoped access, not Tenant-Admin-scoped), so a Teacher who
 * directly navigates to this URL for a course they own would otherwise see
 * these two admin-only controls rendered as if live — the backend
 * independently 403s both `POST .../teacher` and `DELETE` regardless
 * (`CourseService.reassignTeacher`/`deleteCourse`, both hard-checked against
 * `Role.TENANT_ADMIN`), so this is not an authorization bypass, but showing a
 * non-functional "Delete course" button to a Teacher is misleading UX and
 * breaks this project's "hide actions outside the caller's permission set"
 * convention (`.claude/rules/ui-ux.md` §1). Gated here on the caller's own
 * decoded role — a UX convenience only, never the authorization boundary
 * (`frontend/CLAUDE.md`).
 *
 * There is no module/lesson management surface here — that stays
 * Teacher-only per the module plan §11.
 */
export default function TenantAdminCourseDetailPage() {
  const params = useParams<{ courseId: string }>();
  const courseId = params.courseId;
  const query = useCourse(courseId);
  const { session } = useAuth();
  const isTenantAdmin = session?.role === "TENANT_ADMIN";

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">
          Course details{query.data ? `: ${query.data.name}` : ""}
        </h1>
        <p className="text-sm text-muted-foreground">
          {isTenantAdmin
            ? "Review and manage this course on behalf of your tenant — update its details, control visibility and price, reassign its teacher, or delete it."
            : "Review and manage this course — update its details, and control its visibility and price."}
        </p>
      </div>

      <QueryStateBoundary
        query={query}
        loadingLabel="Loading course…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
      >
        {(course) => (
          <div className="flex flex-col gap-6">
            <CourseEditForm key={course.id} courseId={course.id} course={course} />
            <div className="grid gap-4 sm:grid-cols-2">
              <CourseVisibilityControl courseId={course.id} course={course} />
              <CoursePriceChangeForm courseId={course.id} course={course} />
            </div>
            {isTenantAdmin ? (
              <>
                <CourseTeacherReassignForm courseId={course.id} course={course} />
                <CourseDeleteAction courseId={course.id} course={course} />
              </>
            ) : null}
          </div>
        )}
      </QueryStateBoundary>
    </div>
  );
}
