import { CourseCreateForm } from "@/components/courses/course-create-form";

/**
 * Staff-facing course creation (PAR-05-02, Wave 2). `POST /api/v1/courses`
 * already accepts an explicit `teacherId` for a staff caller (mandatory,
 * validated same-tenant/`role = TEACHER` server-side,
 * `CourseService#createCourse`) — no backend change needed for this route.
 *
 * Permission-denied handling: unlike every other page in this module, this
 * route has no `GET` to fail with a 403 up front (course creation is a pure
 * `POST`) — an unauthorized caller's very first submit attempt 403s and is
 * surfaced inline via the form's own `ErrorState`
 * (`course-create-form.tsx`'s `pageError` branch), not a full-page
 * `PermissionDeniedState`. The Tenant Admin nav only ever links here for a
 * role plausibly holding `DomainArea.COURSES`/`CREATE_EDIT` — a UX
 * convenience only; the backend remains the sole authorization boundary.
 */
export default function TenantAdminNewCoursePage() {
  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">Create a course</h1>
        <p className="text-sm text-muted-foreground">
          Create a course on behalf of a teacher in your tenant. Work through each step, assign
          the owning teacher, then create it as Draft, Private, or Public.
        </p>
      </div>
      <CourseCreateForm mode="staff" />
    </div>
  );
}
