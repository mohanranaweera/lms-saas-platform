"use client";

import { useState } from "react";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { EmptyState } from "@/components/states/empty-state";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { PaymentOperationalStateBadge } from "@/components/payments/status-badges";
import { formatMoney } from "@/lib/format";
import { useCourses } from "@/lib/api/courses";
import { useLedgerCourseSummary } from "@/lib/api/ledger";

/**
 * Tenant Admin Course Payment Summary tab (Wave 6 §4/§5). `GET
 * /api/v1/ledger/courses/{courseId}/summary` — tenant-scoped,
 * `PAYMENTS_SLIPS`/`VIEW`-gated. This module has no existing per-course
 * navigation context to inherit for a tenant-admin payments screen, so this
 * follows `tenant-admin/attendance/reports/page.tsx`'s established course-
 * selector pattern (`useCourses()` tenant-wide list, no ownership filter)
 * rather than requiring a `courseId` route param.
 */
export function CourseSummaryTab() {
  const coursesQuery = useCourses();
  const [courseId, setCourseId] = useState<string>("");

  const summaryQuery = useLedgerCourseSummary(courseId, { enabled: courseId.length > 0 });

  const courseOptions = coursesQuery.data?.content ?? [];

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-col gap-1.5 sm:w-72">
        <Label htmlFor="course-summary-course">Course</Label>
        <Select
          value={courseId || undefined}
          onValueChange={(value) => setCourseId(value ?? "")}
          disabled={coursesQuery.isPending}
        >
          <SelectTrigger id="course-summary-course" className="w-full">
            <SelectValue placeholder="Select a course…" />
          </SelectTrigger>
          <SelectContent>
            {courseOptions.map((course) => (
              <SelectItem key={course.id} value={course.id}>
                {course.name}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      {courseId.length === 0 ? (
        <EmptyState
          title="Select a course"
          description="Choose a course above to see its payment summary, broken down by status."
        />
      ) : (
        <QueryStateBoundary
          query={summaryQuery}
          loadingLabel="Loading course payment summary…"
          loginPath="/login"
          permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
          isEmpty={(data) => data.totalOrders === 0}
          emptyState={{
            title: "No orders for this course yet",
            description: "No student has placed an order for this course yet.",
          }}
        >
          {(summary) => (
            <div className="flex flex-col gap-4 rounded-lg border border-border p-4">
              <div>
                <h3 className="text-base font-semibold text-foreground">
                  {summary.courseTitle ?? "Untitled course"}
                </h3>
                <p className="text-sm text-muted-foreground">
                  {summary.totalOrders} total order{summary.totalOrders === 1 ? "" : "s"}
                </p>
              </div>
              <dl className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
                {summary.byState.map((bucket) => (
                  <div
                    key={bucket.state}
                    className="flex flex-col gap-1.5 rounded-lg border border-border p-3"
                  >
                    <dt>
                      <PaymentOperationalStateBadge state={bucket.state} />
                    </dt>
                    <dd className="text-sm text-muted-foreground">
                      {bucket.count} order{bucket.count === 1 ? "" : "s"} —{" "}
                      <span className="font-medium text-foreground">
                        {formatMoney(bucket.totalAmount)}
                      </span>
                    </dd>
                  </div>
                ))}
              </dl>
            </div>
          )}
        </QueryStateBoundary>
      )}
    </div>
  );
}
