"use client";

import type { ReactNode } from "react";
import { Archive } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { CourseStatusBadge } from "@/components/courses/course-status-badge";
import { CoursePricingModelBadge } from "@/components/courses/course-pricing-model-badge";
import { CourseWorkspaceTabs, COURSE_WORKSPACE_TABS } from "@/components/courses/course-workspace-tabs";
import { useCourse, type CourseResponse } from "@/lib/api/courses";
import { formatDateTime } from "@/lib/format";

/**
 * Shared scaffold for every course workspace tab page: fetches the course
 * once (`useCourse`, React-Query-cached — every tab page mounting this
 * independently is cheap, not 8 separate network round-trips per visit),
 * renders the loading/error/permission-denied states via the shared
 * `QueryStateBoundary`, the workspace heading (name, status, pricing model,
 * a loud archived banner when applicable — `.claude/rules/ui-ux.md`'s
 * "visually indicate scope/state" convention), and the tab nav — then hands
 * the resolved `course` to the caller's `children` render prop for the
 * tab-specific content only.
 */
export function CourseWorkspaceShell({
  courseId,
  basePath,
  dashboardHref,
  children,
}: {
  courseId: string;
  basePath: string;
  dashboardHref: string;
  children: (course: CourseResponse) => ReactNode;
}) {
  const query = useCourse(courseId);

  return (
    <QueryStateBoundary
      query={query}
      loadingLabel="Loading course…"
      loginPath="/login"
      permissionDenied={{ dashboardHref }}
    >
      {(course) => (
        <div className="flex flex-col gap-6">
          <div className="flex flex-col gap-2">
            <div className="flex flex-wrap items-center gap-2">
              <h1 className="text-xl font-semibold text-foreground">{course.name}</h1>
              <CourseStatusBadge status={course.status} />
              <CoursePricingModelBadge pricingModel={course.pricingModel} />
            </div>
            <p className="text-sm text-muted-foreground">{course.slug}</p>
            {course.archivedAt ? (
              <Alert>
                <Archive aria-hidden="true" />
                <AlertDescription>
                  This course is archived (since {formatDateTime(course.archivedAt)}) and hidden
                  from the default course list. Unarchive it from the Settings tab to make it
                  visible there again.
                </AlertDescription>
              </Alert>
            ) : null}
          </div>

          <CourseWorkspaceTabs basePath={basePath} tabs={COURSE_WORKSPACE_TABS} />

          {children(course)}
        </div>
      )}
    </QueryStateBoundary>
  );
}
