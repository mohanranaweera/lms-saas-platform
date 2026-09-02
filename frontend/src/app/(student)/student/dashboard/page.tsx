"use client";

import Link from "next/link";
import { useMemo } from "react";
import { useRouter } from "next/navigation";
import { AlertTriangle, CheckCircle2, Receipt } from "lucide-react";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { EmptyState } from "@/components/states/empty-state";
import { StatCard } from "@/components/dashboard/stat-card";
import { AccessStateBadge } from "@/components/students/enrollment-status-badges";
import { formatDateTime, formatMoney, shortId } from "@/lib/format";
import {
  useMyEnrollments,
  useMyEnrolledCourseSummaries,
  indexCourseSummariesById,
} from "@/lib/api/enrollments";
import { useLedgerHistory, type LedgerHistoryEntryResponse } from "@/lib/api/ledger";

const RECENT_COURSES_LIMIT = 5;

function describeMostRecentPayment(entry: LedgerHistoryEntryResponse): string {
  const label = entry.entryType === "REFUND" ? "Refund" : "Payment confirmed";
  return `${label} — ${formatMoney(entry.amount)}`;
}

/**
 * Student Overview (MVP-013 SDASH-1 — rebuild of the prior static
 * `EmptyState`-only placeholder). Per plan §4.1: two independent React Query
 * reads (`useMyEnrollments`, `useLedgerHistory`) composed client-side into
 * stat cards, an expired-access alert, and a short recent-courses list. No
 * new backend call is needed for this page itself.
 *
 * The two primary queries are deliberately NOT wrapped in one shared
 * `QueryStateBoundary` — plan §13 requires a failed ledger read to never
 * blank a successful enrollments read (and vice versa). The enrollment-
 * derived section (stat cards, expired-access alert, recent courses) uses
 * its own `QueryStateBoundary` keyed to `useMyEnrollments`; the payment-
 * activity card handles `useLedgerHistory`'s loading/error/success states
 * locally in its own section, since it's a single card rather than a shape
 * `QueryStateBoundary`'s render-prop composes well as a section of its own.
 * A failure in either section leaves the other fully interactive.
 *
 * `useMyEnrolledCourseSummaries` (course name resolution for the recent-
 * courses list) is a third, non-blocking read — same graceful-degradation
 * treatment as `student/courses/page.tsx`: a failure, or a course id missing
 * from its result, falls back to `shortId(courseId)` per row rather than
 * hiding the recent-courses section.
 */
export default function StudentDashboardPage() {
  const router = useRouter();
  const enrollmentsQuery = useMyEnrollments();
  const ledgerQuery = useLedgerHistory();
  const courseSummaryQuery = useMyEnrolledCourseSummaries();

  const courseSummaryById = useMemo(
    () => indexCourseSummariesById(courseSummaryQuery.data),
    [courseSummaryQuery.data]
  );

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">Overview</h1>
        <p className="text-sm text-muted-foreground">
          Your enrollments, courses, and recent payment activity at a glance.
        </p>
      </div>

      <QueryStateBoundary
        query={enrollmentsQuery}
        loadingLabel="Loading your enrollments…"
        loginPath="/login"
        isEmpty={(data) => data.length === 0}
        emptyState={{
          title: "You have no active enrollments yet",
          description:
            "Once you enroll in a course, your access status and recent courses will show up here.",
          action: { label: "Browse courses", onClick: () => router.push("/courses") },
        }}
      >
        {(enrollments) => {
          const activeCount = enrollments.filter((row) => row.state === "ACTIVE").length;
          const expiredRows = enrollments.filter((row) => row.state === "EXPIRED");
          const recentCourses = enrollments
            .filter((row) => row.state === "ACTIVE")
            .slice(0, RECENT_COURSES_LIMIT);

          return (
            <div className="flex flex-col gap-6">
              <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
                <StatCard
                  label="Active courses"
                  value={activeCount}
                  icon={<CheckCircle2 className="size-4" />}
                />
                <StatCard
                  label="Needs attention"
                  value={expiredRows.length}
                  hint={expiredRows.length > 0 ? "Access has expired on these courses" : undefined}
                  icon={<AlertTriangle className="size-4" />}
                  tone={expiredRows.length > 0 ? "warning" : "default"}
                />
              </div>

              {expiredRows.length > 0 ? (
                <div
                  role="alert"
                  className="flex flex-col gap-2 rounded-xl border border-warning/40 bg-warning/5 p-4 sm:flex-row sm:items-center sm:justify-between"
                >
                  <p className="text-sm text-foreground">
                    {expiredRows.length === 1
                      ? "One of your courses has expired access."
                      : `${expiredRows.length} of your courses have expired access.`}
                  </p>
                  <Link
                    href="/student/payments/reactivation"
                    className="text-sm font-medium text-foreground hover:underline"
                  >
                    Reactivate access
                  </Link>
                </div>
              ) : null}

              <div className="flex flex-col gap-3">
                <h2 className="text-base font-semibold text-foreground">Recent courses</h2>
                {recentCourses.length === 0 ? (
                  <EmptyState
                    title="No active courses to show yet"
                    description={
                      expiredRows.length > 0
                        ? "All your enrollments have expired. Reactivate access to see your courses here again."
                        : "Courses you currently have active access to will appear here."
                    }
                    action={
                      expiredRows.length > 0
                        ? {
                            label: "Reactivate access",
                            onClick: () => router.push("/student/payments/reactivation"),
                          }
                        : { label: "Browse courses", onClick: () => router.push("/courses") }
                    }
                  />
                ) : (
                  <ul
                    aria-label="Recent courses"
                    className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3"
                  >
                    {recentCourses.map((row) => {
                      const course = courseSummaryById.get(row.courseId);
                      const courseLabel = course?.name ?? shortId(row.courseId);
                      return (
                        <li
                          key={row.enrollmentId}
                          className="flex flex-col gap-2 rounded-xl border border-border bg-card p-4"
                        >
                          <div className="flex items-start justify-between gap-2">
                            <h3
                              className="text-sm font-medium text-foreground"
                              aria-live="polite"
                            >
                              {courseLabel}
                            </h3>
                            <AccessStateBadge state={row.state} />
                          </div>
                          {course ? (
                            <span className="w-fit rounded-md border border-border bg-muted px-2 py-0.5 text-xs font-medium text-muted-foreground">
                              {course.category}
                            </span>
                          ) : null}
                        </li>
                      );
                    })}
                  </ul>
                )}
                <Link
                  href="/student/courses"
                  className="w-fit text-sm font-medium text-foreground hover:underline"
                >
                  View all my courses
                </Link>
              </div>
            </div>
          );
        }}
      </QueryStateBoundary>

      <section className="flex flex-col gap-3" aria-labelledby="payment-activity-heading">
        <h2 id="payment-activity-heading" className="text-base font-semibold text-foreground">
          Payment activity
        </h2>
        <QueryStateBoundary query={ledgerQuery} loadingLabel="Loading payment activity…" loginPath="/login">
          {(entries) => (
            <StatCard
              label="Most recent payment"
              // `useLedgerHistory` is backend-sorted `createdAt` DESC
              // (`LedgerEntryRepository#findAllByOrderIdIn`), so `[0]` is
              // always the most recent entry — no client-side re-sort needed.
              value={entries.length === 0 ? "No payments yet" : describeMostRecentPayment(entries[0])}
              hint={entries.length > 0 ? formatDateTime(entries[0].createdAt) : undefined}
              icon={<Receipt className="size-4" />}
            />
          )}
        </QueryStateBoundary>
      </section>
    </div>
  );
}
