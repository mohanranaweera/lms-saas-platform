"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { AccessStateBadge } from "@/components/students/enrollment-status-badges";
import { formatDateTime, shortId } from "@/lib/format";
import {
  useMyEnrollments,
  useMyReactivationRequests,
  type EnrollmentSummaryResponse,
} from "@/lib/api/enrollments";

/**
 * Student "My Courses" (MVP-012). `GET /api/v1/enrollments/my` — owner-only,
 * plain array (no pagination), so there is no page/filter state on this
 * screen.
 *
 * There is no course-name lookup reachable for a Student caller
 * (`GET /api/v1/courses/{id}` 403s — `STUDENT` is absent from
 * `CourseAccessGuard`'s matrix), so each row renders a short id fragment
 * instead of a name (`shortId`, `lib/format.ts`) — this page never calls
 * `GET /api/v1/courses/{id}`.
 *
 * `accessExpiresAt` is only rendered when present; a `null` value on an
 * `ACTIVE` row means lifetime access (no `access_duration_days` configured on
 * the course), and is rendered as such rather than as a blank/dash.
 */
export default function StudentMyCoursesPage() {
  const router = useRouter();
  const query = useMyEnrollments();

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
   */
  const reactivationRequestsQuery = useMyReactivationRequests({ page: 0, size: 100 });
  const approvedUnfulfilledEnrollmentIds = new Set(
    (reactivationRequestsQuery.data?.content ?? [])
      .filter((request) => request.status === "APPROVED" && request.newOrderId === null)
      .map((request) => request.enrollmentId)
  );

  function renderExpiry(row: EnrollmentSummaryResponse): string {
    if (row.accessExpiresAt) return formatDateTime(row.accessExpiresAt);
    return row.state === "ACTIVE" ? "Lifetime access" : "—";
  }

  function renderReactivateAction(row: EnrollmentSummaryResponse) {
    if (row.state !== "EXPIRED") return null;
    if (approvedUnfulfilledEnrollmentIds.has(row.enrollmentId)) {
      return (
        <Link
          href={`/student/checkout/${row.courseId}`}
          aria-label={`Proceed to checkout for ${shortId(row.courseId)}`}
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
          aria-label={`Reactivation already requested for ${shortId(row.courseId)}`}
          className="text-sm text-muted-foreground hover:underline"
        >
          Reactivation already requested
        </Link>
      );
    }
    return (
      <Link
        href={`/student/payments/reactivation?enrollmentId=${row.enrollmentId}`}
        aria-label={`Reactivate ${shortId(row.courseId)}`}
        className="text-sm font-medium text-foreground hover:underline"
      >
        Reactivate
      </Link>
    );
  }

  const columns: DataTableColumn<EnrollmentSummaryResponse>[] = [
    {
      key: "course",
      header: "Course",
      cell: (row) => shortId(row.courseId),
      hideOnCard: true,
    },
    {
      key: "state",
      header: "Access",
      cell: (row) => <AccessStateBadge state={row.state} />,
      hideOnCard: true,
    },
    {
      key: "expiresAt",
      header: "Expires",
      cell: (row) => renderExpiry(row),
    },
    {
      key: "reactivate",
      header: "Reactivate",
      cell: (row) => renderReactivateAction(row) ?? "—",
      hideOnCard: true,
    },
  ];

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
          <DataTable
            columns={columns}
            rows={enrollments}
            rowKey={(row) => row.enrollmentId}
            caption="My enrolled courses"
            cardHeading={(row) => shortId(row.courseId)}
            cardHeadingAdornment={(row) => <AccessStateBadge state={row.state} />}
            cardFooter={(row) => renderReactivateAction(row)}
          />
        )}
      </QueryStateBoundary>
    </div>
  );
}
