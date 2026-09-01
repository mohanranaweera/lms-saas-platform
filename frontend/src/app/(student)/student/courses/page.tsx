"use client";

import Link from "next/link";
import { useMemo } from "react";
import { useRouter } from "next/navigation";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { AccessStateBadge } from "@/components/students/enrollment-status-badges";
import { formatDateTime, shortId } from "@/lib/format";
import {
  useMyEnrollments,
  useMyEnrolledCourseSummaries,
  useMyReactivationRequests,
  indexCourseSummariesById,
  type EnrollmentSummaryResponse,
} from "@/lib/api/enrollments";

/**
 * Student "My Courses" (MVP-012 data plumbing; MVP-013 presentational
 * rework, SDASH-2 — replaces the prior `DataTable` admin-surface component
 * with a Course Card grid, mirroring `app/(public)/courses/page.tsx`'s
 * `CourseCard` pattern). `GET /api/v1/enrollments/my` — owner-only, plain
 * array (no pagination), so there is no page/filter state on this screen.
 *
 * Course name/category now resolve via `GET /api/v1/enrollments/my/courses`
 * (`useMyEnrolledCourseSummaries`) — a graceful-degradation read, NOT gated
 * by `QueryStateBoundary`: if it fails, or a given course id simply isn't
 * present in its result (the backend's own documented "omitted, not a 500"
 * case), the affected enrollment row still renders, falling back to
 * `shortId(courseId)` (`lib/format.ts`) instead of disappearing or blanking
 * the whole page. `useMyEnrollments` remains the only query gating this
 * page's loading/empty/error/permission-denied states.
 *
 * `accessExpiresAt` is only rendered when present; a `null` value on an
 * `ACTIVE` row means lifetime access (no `access_duration_days` configured on
 * the course), and is rendered as such rather than as a blank/dash.
 */
export default function StudentMyCoursesPage() {
  const router = useRouter();
  const query = useMyEnrollments();
  const courseSummaryQuery = useMyEnrolledCourseSummaries();

  /**
   * `canRequestReactivation` (from `GET /api/v1/enrollments/my`) is `false`
   * on an `EXPIRED` row exactly when a `SUBMITTED` reactivation request is
   * open for it — it does NOT account for a request that has since been
   * `APPROVED` (see `EnrollmentExpiryService#resolveAccessState`, which only
   * checks `findCurrentOpenByEnrollmentId`, a `SUBMITTED`-only lookup). So an
   * `APPROVED`, not-yet-fulfilled (`newOrderId == null`) request reports
   * `canRequestReactivation: true` here too — this fetch is what tells the
   * two states apart, so the approved case gets a "Proceed to checkout" CTA
   * instead of being offered another "Reactivate" submission (which would
   * just 409 against the still-open approved request). A size of 100 is used
   * rather than the endpoint's own 20-row default since this is a
   * cross-reference over the caller's full history, not a paged view of it —
   * acceptable at MVP student-history scale (mirrors the accepted per-row
   * lookup tradeoff already used in the tenant-admin queue).
   *
   * Unlike `courseSummaryQuery` (a purely cosmetic degrade to `shortId`),
   * this query gates which CTA is actionable at all — while it's pending or
   * has failed, `approvedUnfulfilledEnrollmentIds` would otherwise default to
   * empty and let an EXPIRED row that's actually `APPROVED`/unfulfilled
   * render a clickable "Reactivate" link instead of "Proceed to checkout",
   * risking a real duplicate-submission attempt. `renderReactivateAction`
   * below renders a neutral, non-interactive placeholder for EXPIRED rows
   * until `reactivationRequestsQuery.isSuccess` is true (and a `role="alert"`
   * retry affordance if the read fails outright), rather than ever exposing
   * an action derived from an unknown/failed state.
   */
  const reactivationRequestsQuery = useMyReactivationRequests({ page: 0, size: 100 });
  const approvedUnfulfilledEnrollmentIds = new Set(
    (reactivationRequestsQuery.data?.content ?? [])
      .filter((request) => request.status === "APPROVED" && request.newOrderId === null)
      .map((request) => request.enrollmentId)
  );

  const courseSummaryById = useMemo(
    () => indexCourseSummariesById(courseSummaryQuery.data),
    [courseSummaryQuery.data]
  );

  function renderExpiry(row: EnrollmentSummaryResponse): string {
    if (row.accessExpiresAt) return formatDateTime(row.accessExpiresAt);
    return row.state === "ACTIVE" ? "Lifetime access" : "—";
  }

  function renderReactivateAction(row: EnrollmentSummaryResponse, courseLabel: string) {
    if (row.state !== "EXPIRED") return null;
    if (reactivationRequestsQuery.isError) {
      return (
        <span role="alert" className="flex flex-wrap items-center gap-2 text-sm text-destructive">
          Couldn&apos;t check access status.
          <button
            type="button"
            onClick={() => reactivationRequestsQuery.refetch()}
            className="font-medium underline hover:no-underline"
          >
            Retry
          </button>
        </span>
      );
    }
    if (!reactivationRequestsQuery.isSuccess) {
      return (
        <span className="text-sm text-muted-foreground" aria-live="polite">
          Checking access status…
        </span>
      );
    }
    if (approvedUnfulfilledEnrollmentIds.has(row.enrollmentId)) {
      return (
        <Link
          href={`/student/checkout/${row.courseId}`}
          aria-label={`Proceed to checkout for ${courseLabel}`}
          className="text-sm font-medium text-foreground hover:underline"
        >
          Proceed to checkout
        </Link>
      );
    }
    if (!row.canRequestReactivation) {
      return (
        <Link
          href="/student/payments/reactivation"
          aria-label={`Reactivation already requested for ${courseLabel}`}
          className="text-sm text-muted-foreground hover:underline"
        >
          Reactivation already requested
        </Link>
      );
    }
    return (
      <Link
        href={`/student/payments/reactivation?enrollmentId=${row.enrollmentId}`}
        aria-label={`Reactivate ${courseLabel}`}
        className="text-sm font-medium text-foreground hover:underline"
      >
        Reactivate
      </Link>
    );
  }

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">My courses</h1>
        <p className="text-sm text-muted-foreground">
          Your enrollments and current course access.
        </p>
      </div>

      <QueryStateBoundary
        query={query}
        loadingLabel="Loading your courses…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/student/dashboard" }}
        isEmpty={(data) => data.length === 0}
        emptyState={{
          title: "You have no active enrollments yet",
          description: "Browse the course catalog to find a course to enroll in.",
          action: { label: "Browse courses", onClick: () => router.push("/courses") },
        }}
      >
        {(enrollments) => (
          <ul
            aria-label="My enrolled courses"
            className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3"
          >
            {enrollments.map((row) => {
              const course = courseSummaryById.get(row.courseId);
              const courseLabel = course?.name ?? shortId(row.courseId);
              return (
                <li
                  key={row.enrollmentId}
                  className="flex h-full flex-col gap-3 rounded-xl border border-border bg-card p-5"
                >
                  <div className="flex items-start justify-between gap-2">
                    <h2
                      className="text-base font-semibold text-foreground"
                      aria-live="polite"
                    >
                      {courseLabel}
                    </h2>
                    {course ? (
                      <span className="shrink-0 rounded-md border border-border bg-muted px-2 py-0.5 text-xs font-medium text-muted-foreground">
                        {course.category}
                      </span>
                    ) : null}
                  </div>
                  <div>
                    <AccessStateBadge state={row.state} />
                  </div>
                  <p className="text-sm text-muted-foreground">{renderExpiry(row)}</p>
                  <div className="mt-auto flex items-center pt-2">
                    {renderReactivateAction(row, courseLabel) ?? (
                      <span className="text-sm text-muted-foreground">—</span>
                    )}
                  </div>
                </li>
              );
            })}
          </ul>
        )}
      </QueryStateBoundary>
    </div>
  );
}
