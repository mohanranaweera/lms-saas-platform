"use client";

import Link from "next/link";
import { useMemo } from "react";
import { AccessStateBadge } from "@/components/students/enrollment-status-badges";
import { formatDateTime, shortId } from "@/lib/format";
import {
  indexCourseSummariesById,
  useMyEnrolledCourseSummaries,
  useMyEnrollments,
  useMyReactivationRequests,
  type EnrollmentSummaryResponse,
} from "@/lib/api/enrollments";

/**
 * Legacy paid-access renewal entry point (ADR-013) for EXPIRED legacy
 * enrollments, restored after KLASS-T5b replaced the old My Courses grid with
 * the Klass catalog (KLASS-T8c). Free participation (ADR-028) never expires,
 * so only `EXPIRED` rows of `GET /api/v1/enrollments/my` are surfaced here.
 *
 * Everything below is display-only: the backend decides whether a request may
 * be submitted (`POST /enrollments/{id}/reactivation-requests` 409s on a
 * duplicate) and activation only ever follows an approved request plus a new,
 * backend-confirmed order/payment.
 *
 * CTA selection is the original My Courses logic, unchanged:
 * - `canRequestReactivation` is `false` exactly when a `SUBMITTED` request is
 *   open, but an `APPROVED`, not-yet-fulfilled (`newOrderId == null`) request
 *   still reports `true` - the reactivation-request list is what tells the two
 *   apart, so the approved case gets "Proceed to checkout" instead of another
 *   (409-bound) submission.
 * - Until that list has loaded successfully, an EXPIRED row shows a neutral,
 *   non-interactive "Checking access status…" placeholder (also on failure,
 *   with a Retry), never an action derived from an unknown state.
 */
export function useLegacyReactivationState() {
  const enrollmentsQuery = useMyEnrollments();
  const courseSummaryQuery = useMyEnrolledCourseSummaries();
  // Cross-reference over the caller's full history, not a paged view of it.
  const requestsQuery = useMyReactivationRequests({ page: 0, size: 100 });

  const courseSummaryById = useMemo(
    () => indexCourseSummariesById(courseSummaryQuery.data),
    [courseSummaryQuery.data]
  );

  const approvedUnfulfilledEnrollmentIds = useMemo(
    () =>
      new Set(
        (requestsQuery.data?.content ?? [])
          .filter((request) => request.status === "APPROVED" && request.newOrderId === null)
          .map((request) => request.enrollmentId)
      ),
    [requestsQuery.data]
  );

  const expiredRows = useMemo(
    () => (enrollmentsQuery.data ?? []).filter((row) => row.state === "EXPIRED"),
    [enrollmentsQuery.data]
  );

  function courseLabel(row: EnrollmentSummaryResponse): string {
    return courseSummaryById.get(row.courseId)?.name ?? shortId(row.courseId);
  }

  function courseCategory(row: EnrollmentSummaryResponse): string | null {
    return courseSummaryById.get(row.courseId)?.category ?? null;
  }

  return {
    enrollmentsQuery,
    requestsQuery,
    approvedUnfulfilledEnrollmentIds,
    expiredRows,
    courseLabel,
    courseCategory,
  };
}

type LegacyReactivationState = ReturnType<typeof useLegacyReactivationState>;

export function LegacyReactivationAction({
  row,
  label,
  state,
  reactivateText,
}: {
  row: EnrollmentSummaryResponse;
  label: string;
  state: LegacyReactivationState;
  /** Visible CTA text; the accessible name is `${reactivateText} for ${label}`. */
  reactivateText: string;
}) {
  const { requestsQuery, approvedUnfulfilledEnrollmentIds } = state;
  if (row.state !== "EXPIRED") return null;

  if (!requestsQuery.isSuccess) {
    return (
      <span className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground" aria-live="polite">
        Checking access status…
        {requestsQuery.isError ? (
          <button
            type="button"
            onClick={() => requestsQuery.refetch()}
            aria-label={`Retry checking access status for ${label}`}
            className="font-medium text-foreground underline hover:no-underline"
          >
            Retry
          </button>
        ) : null}
      </span>
    );
  }

  if (approvedUnfulfilledEnrollmentIds.has(row.enrollmentId)) {
    return (
      <Link
        href={`/student/checkout/${row.courseId}`}
        aria-label={`Proceed to checkout for ${label}`}
        data-testid="legacy-proceed-to-checkout"
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
        aria-label={`Reactivation already requested for ${label}`}
        data-testid="legacy-reactivation-pending"
        className="text-sm text-muted-foreground hover:underline"
      >
        Reactivation already requested
      </Link>
    );
  }

  return (
    <Link
      href={`/student/payments/reactivation?enrollmentId=${encodeURIComponent(row.enrollmentId)}`}
      aria-label={`${reactivateText} for ${label}`}
      data-testid="legacy-reactivate"
      className="text-sm font-medium text-blue-700 hover:underline dark:text-blue-300"
    >
      {reactivateText}
    </Link>
  );
}

export function LegacyEnrollmentCard({
  row,
  state,
  reactivateText,
}: {
  row: EnrollmentSummaryResponse;
  state: LegacyReactivationState;
  reactivateText: string;
}) {
  const label = state.courseLabel(row);
  const category = state.courseCategory(row);
  return (
    <li
      className="flex h-full flex-col gap-3 rounded-xl border border-border bg-card p-4 shadow-sm"
      data-testid="legacy-expired-enrollment"
      data-enrollment-id={row.enrollmentId}
    >
      <div className="flex items-start justify-between gap-2">
        <h3 className="text-base font-semibold text-foreground">{label}</h3>
        {category ? (
          <span className="shrink-0 rounded-md border border-border bg-muted px-2 py-0.5 text-xs font-medium text-muted-foreground">
            {category}
          </span>
        ) : null}
      </div>
      <div>
        <AccessStateBadge state={row.state} />
      </div>
      {row.accessExpiresAt ? (
        <p className="text-sm text-muted-foreground">Access ended {formatDateTime(row.accessExpiresAt)}</p>
      ) : null}
      <div className="mt-auto flex items-center pt-1">
        <LegacyReactivationAction row={row} label={label} state={state} reactivateText={reactivateText} />
      </div>
    </li>
  );
}

/**
 * Enrolled-view section on the student catalog listing EXPIRED legacy
 * enrollments with their renewal action. Fetches once for the whole page.
 * Silent degradation: while `/enrollments/my` loads, fails, or has no EXPIRED
 * rows, nothing renders and the catalog grid is unaffected.
 */
export function ExpiredLegacyAccessSection() {
  const state = useLegacyReactivationState();
  if (!state.enrollmentsQuery.isSuccess || state.expiredRows.length === 0) return null;

  return (
    <section
      aria-labelledby="legacy-expired-access-heading"
      className="flex flex-col gap-3 rounded-xl border border-border bg-card/50 p-4"
      data-testid="legacy-expired-access"
    >
      <div>
        <h2 id="legacy-expired-access-heading" className="text-base font-semibold text-foreground">
          Expired paid access
        </h2>
        <p className="text-sm text-muted-foreground">
          Your paid access to these courses has expired. Request reactivation to renew it.
        </p>
      </div>
      <ul
        aria-label="My enrolled courses with expired access"
        className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3"
      >
        {state.expiredRows.map((row) => (
          <LegacyEnrollmentCard key={row.enrollmentId} row={row} state={state} reactivateText="Reactivate access" />
        ))}
      </ul>
    </section>
  );
}
