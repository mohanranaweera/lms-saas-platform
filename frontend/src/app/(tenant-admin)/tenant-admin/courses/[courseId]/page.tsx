"use client";

import { Suspense } from "react";
import { useParams, useSearchParams } from "next/navigation";
import { CheckCircle2 } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { LoadingState } from "@/components/states/loading-state";
import { CourseWorkspaceShell } from "@/components/courses/course-workspace-shell";
import { CourseEditDetailsForm } from "@/components/courses/admin/course-edit-details-form";

/**
 * Tenant Admin course workspace — Overview tab (`tenant-admin/courses/[courseId]`,
 * the workspace's index route). Wave 2 restructured this page from a single
 * flat "everything" screen into a tabbed workspace (`CourseWorkspaceShell`).
 * Overview holds the course edit form (`CourseEditDetailsForm`, G2/G3/G4:
 * mirrors Add New Course — teachers with primary, payment type and fee,
 * enrollment fee, additional settings, tags, links, cover). The Fees &
 * Billing tab was removed (R2-D5); publish/unpublish, archive/unarchive,
 * clone and delete stay on the Settings tab.
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
            <CourseEditDetailsForm
              courseId={course.id}
              portal="tenant-admin"
              dashboardHref="/tenant-admin/dashboard"
            />
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
