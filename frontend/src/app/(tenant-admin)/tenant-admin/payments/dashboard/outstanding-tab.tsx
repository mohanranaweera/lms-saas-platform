"use client";

import { useState } from "react";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { EmptyState } from "@/components/states/empty-state";
import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import { Button } from "@/components/ui/button";
import { PaymentOperationalStateBadge } from "@/components/payments/status-badges";
import { formatMoney, shortId } from "@/lib/format";
import { useLedgerOutstanding, type OutstandingOrderResponse } from "@/lib/api/ledger";

const PAGE_SIZE = 20;

const columns: DataTableColumn<OutstandingOrderResponse>[] = [
  { key: "course", header: "Course", cell: (row) => row.courseTitle ?? shortId(row.courseId, "Course") },
  {
    key: "status",
    header: "Status",
    cell: (row) => <PaymentOperationalStateBadge state={row.operationalState} />,
    hideOnCard: true,
  },
  { key: "amount", header: "Amount", cell: (row) => formatMoney(row.amount, row.currency) },
  { key: "student", header: "Student", cell: (row) => shortId(row.studentId, "Student") },
  { key: "order", header: "Order", cell: (row) => shortId(row.orderId, "Order"), hideOnCard: true },
];

/**
 * Tenant Admin Outstanding Payments tab (Wave 6 §4/§5). `GET
 * /api/v1/ledger/outstanding` — orders with no `PAID`/`REFUNDED`
 * `PaymentOperationalState` (i.e. `UNPAID`/`PENDING`/`UNDER_REVIEW`/
 * `REJECTED`), tenant-scoped, `PAYMENTS_SLIPS`/`VIEW`-gated. This endpoint
 * takes no filter params of its own — every row shown here is, by
 * definition, already "not fully paid", so there is exactly one empty state
 * (no separate "no results match your filter" variant, mirroring
 * `platform-admin/payments/page.tsx`'s identical reasoning for its own
 * filter-less endpoint).
 */
export function OutstandingPaymentsTab() {
  const [page, setPage] = useState(0);
  const query = useLedgerOutstanding({ page, size: PAGE_SIZE });

  return (
    <QueryStateBoundary
      query={query}
      loadingLabel="Loading outstanding payments…"
      loginPath="/login"
      permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
      isEmpty={(data) => data.content.length === 0 && page === 0}
      emptyState={{
        title: "No outstanding payments",
        description: "Every order in your tenant currently has a paid or refunded status.",
      }}
    >
      {(data) => (
        <div className="flex flex-col gap-4">
          {data.content.length === 0 ? (
            <EmptyState
              title="No more results"
              description="There are no outstanding orders on this page. Go back to an earlier page."
            />
          ) : (
            <DataTable
              columns={columns}
              rows={data.content}
              rowKey={(row) => row.orderId}
              caption="Outstanding payments"
              cardHeading={(row) => row.courseTitle ?? shortId(row.courseId, "Course")}
              cardHeadingAdornment={(row) => (
                <PaymentOperationalStateBadge state={row.operationalState} />
              )}
            />
          )}
          <div className="flex items-center justify-between">
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => setPage((current) => Math.max(0, current - 1))}
              disabled={page === 0}
            >
              Previous
            </Button>
            <span className="text-xs text-muted-foreground">
              Page {data.page + 1} of {Math.max(data.totalPages, 1)}
            </span>
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => setPage((current) => current + 1)}
              disabled={data.page + 1 >= data.totalPages}
            >
              Next
            </Button>
          </div>
        </div>
      )}
    </QueryStateBoundary>
  );
}
