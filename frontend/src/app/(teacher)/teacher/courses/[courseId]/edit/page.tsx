"use client";

import Link from "next/link";
import { useParams, useSearchParams } from "next/navigation";
import { CheckCircle2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { CourseVisibilityControl } from "@/components/courses/course-visibility-control";
import { CourseEditDetailsForm } from "@/components/courses/admin/course-edit-details-form";
import { CourseArchiveControl } from "@/components/courses/course-archive-control";
import { CourseCloneAction } from "@/components/courses/course-clone-action";
import { useCourse } from "@/lib/api/courses";

/**
 * Teacher's course edit page. No tabbed workspace exists here (unlike the
 * Tenant Admin course detail route, which Wave 2 restructured into one —
 * see `components/courses/course-workspace-shell.tsx`) — this is a
 * deliberate, lower-risk choice for this wave rather than a gap: Teacher
 * only ever edits their OWN course (no cross-course-owner reassignment/
 * delete concerns to separate out), so the existing single-page structure
 * stays, with Wave 2's new capabilities appended as additional sections
 * rather than moved into new tab routes.
 *
 * Course details use the shared `CourseEditDetailsForm` (G3): payment type
 * and fee, enrollment fee, additional settings, tags, links and cover. The
 * teacher list is read-only here (the server refuses teacher-list changes
 * from a Teacher). The old Fees & Billing panel is no longer shown (R2-D5);
 * a monthly fee change still records billing-period history server-side.
 */
export default function EditCoursePage() {
  const params = useParams<{ courseId: string }>();
  const courseId = params.courseId;
  const query = useCourse(courseId);
  const searchParams = useSearchParams();
  const justCloned = searchParams.get("cloned") === "1";

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold text-foreground">Edit course</h1>
          <p className="text-sm text-muted-foreground">
            Update this course&apos;s details, payment type and fees. Visibility, archiving and
            cloning have their own actions below.
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button render={<Link href={`/teacher/courses/${courseId}/modules`} />} variant="outline">
            Manage modules & lessons
          </Button>
          <Button render={<Link href={`/teacher/live-classes?courseId=${courseId}`} />} variant="outline">
            Manage live classes
          </Button>
        </div>
      </div>

      {justCloned ? (
        <Alert role="status">
          <CheckCircle2 aria-hidden="true" />
          <AlertDescription>
            Course cloned. This is a new, separate Draft course with its own content copy — no
            enrollment, payment, or billing-period history carried over.
          </AlertDescription>
        </Alert>
      ) : null}

      <QueryStateBoundary
        query={query}
        loadingLabel="Loading course…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/teacher/dashboard" }}
      >
        {(course) => (
          <div className="flex flex-col gap-6">
            <CourseEditDetailsForm courseId={course.id} portal="teacher" dashboardHref="/teacher/dashboard" />
            <CourseVisibilityControl courseId={course.id} course={course} />
            <div className="grid gap-4 sm:grid-cols-2">
              <CourseArchiveControl courseId={course.id} course={course} />
              <CourseCloneAction
                courseId={course.id}
                course={course}
                onCloned={(clonedId) => `/teacher/courses/${clonedId}/edit?cloned=1`}
              />
            </div>
          </div>
        )}
      </QueryStateBoundary>
    </div>
  );
}
