"use client";

import { PackageOpen } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { formatCurrency, type OfferState } from "@/lib/api/klass-student";
import { useStaffPaymentOffers } from "@/lib/api/staff-payments";

const STATE: Record<OfferState, { label: string; variant: "default" | "warning" | "outline" }> = {
  PAID: { label: "Paid", variant: "default" },
  PENDING: { label: "Pending", variant: "warning" },
  AVAILABLE: { label: "Not paid", variant: "outline" },
};

/** "Paid resources of <course>" (Klass S1): read-only list of PAID lessons with the student's state. */
export function PaidResourcesPanel({
  studentId,
  courseId,
  courseTitle,
}: {
  studentId: string;
  courseId: string;
  courseTitle: string;
}) {
  const query = useStaffPaymentOffers(studentId, courseId, "LESSON");
  return (
    <section className="flex flex-col rounded-lg border border-border bg-card" aria-labelledby="paid-resources-heading">
      <h2 id="paid-resources-heading" className="border-b border-border px-4 py-3 text-base font-semibold text-foreground">
        Paid resources of {courseTitle}
      </h2>
      <div className="p-4">
        <QueryStateBoundary
          query={query}
          loadingLabel="Loading paid resources…"
          loginPath="/login"
          permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
          isEmpty={(data) => !data.supported || data.items.length === 0}
          emptyState={{
            title: "No paid resources available",
            description: `${courseTitle} has no separately paid lessons.`,
            icon: <PackageOpen className="size-6 text-muted-foreground" aria-hidden="true" />,
          }}
        >
          {(data) => (
            <ul className="flex flex-col divide-y divide-border" aria-label="Paid resources" data-testid="paid-resources">
              {data.items.map((item) => {
                const state = STATE[item.state] ?? STATE.AVAILABLE;
                return (
                  <li key={item.lessonId ?? item.label} className="flex flex-wrap items-center justify-between gap-2 py-2">
                    <span className="min-w-0 text-foreground">{item.label}</span>
                    <span className="flex items-center gap-2">
                      <span className="text-xs text-muted-foreground">{formatCurrency(item.amount, data.currency)}</span>
                      <Badge variant={state.variant}>{state.label}</Badge>
                    </span>
                  </li>
                );
              })}
            </ul>
          )}
        </QueryStateBoundary>
      </div>
    </section>
  );
}
