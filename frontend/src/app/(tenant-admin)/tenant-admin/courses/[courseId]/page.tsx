"use client";

import { Suspense } from "react";
import { useParams, useSearchParams } from "next/navigation";
import { CheckCircle2 } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { LoadingState } from "@/components/states/loading-state";
import { CourseWorkspaceShell } from "@/components/courses/course-workspace-shell";
import { CourseEditForm } from "@/components/courses/course-edit-form";

/**
 * Tenant Admin course workspace — Overview tab (`tenant-admin/courses/[courseId]`,
 * the workspace's index route). Wave 2 restructured this page from a single
 * flat "everything" screen into a tabbed workspace (`CourseWorkspaceShell`) —
 * Overview now holds only the structural course-details edit form
 * (`CourseEditForm`, unchanged); publish/unpublish, teacher reassignment,
 * archive/unarchive, clone, and delete moved to the Settings tab, and
 * price/pricing-model/billing-configuration controls moved to the Fees &
 * Billing tab — see those routes.
 *
 * `?created=1`/`?cloned=1` are one-time, purely-informational confirmations
 * appended by `course-create-form.tsx`'s and `CourseCloneAction`'s redirects
 * — neither ever activates or confirms anything itself (the course already
 * exists server-side by the time this page renders); this only decides
 * whether to show a banner, matching `course-edit-form.tsx`'s "Course
 * details saved." confirmation pattern.
 */
function TenantAdminCourseOverviewPageContent() {
  const params = useParams<{ courseId: string }>();
  const courseId = params.courseId;
  const searchParams = useSearchParams();
  const justCreated = searchParams.get("created") === "1";
  const justCloned = searchParams.get("cloned") === "1";

  return (
    <div className="flex flex-col gap-6">
      <CourseWorkspaceShell
        courseId={courseId}
        basePath={`/tenant-admin/courses/${courseId}`}
        dashboardHref="/tenant-admin/dashboard"
      >
        {(course) => (
          <div className="flex flex-col gap-6">
            {justCreated ? (
              <Alert role="status">
                <CheckCircle2 aria-hidden="true" />
                <AlertDescription>Course created.</AlertDescription>
              </Alert>
            ) : null}
            {justCloned ? (
              <Alert role="status">
                <CheckCircle2 aria-hidden="true" />
                <AlertDescription>
                  Course cloned. This is a new, separate Draft course with its own content copy —
                  no enrollment, payment, or billing-period history carried over.
                </AlertDescription>
              </Alert>
            ) : null}
            <CourseEditForm key={course.id} courseId={course.id} course={course} />
          </div>
        )}
      </CourseWorkspaceShell>
    </div>
  );
}

/**
 * `useSearchParams()` requires a `Suspense` boundary (mirrors
 * `tenant-admin/audit-log/page.tsx`'s identical wrapper) — this page is
 * already fully client-rendered, so in practice this never visibly suspends.
 */
export default function TenantAdminCourseOverviewPage() {
  return (
    <Suspense fallback={<LoadingState label="Loading course…" />}>
      <TenantAdminCourseOverviewPageContent />
    </Suspense>
  );
}
