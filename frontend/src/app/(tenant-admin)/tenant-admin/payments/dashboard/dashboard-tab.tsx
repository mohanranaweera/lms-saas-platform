"use client";

import { useState } from "react";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { EmptyState } from "@/components/states/empty-state";
import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import { Button } from "@/components/ui/button";
import {
  LedgerEntryTypeBadge,
  PAYMENT_METHOD_LABEL,
  PaymentOperationalStateBadge,
} from "@/components/payments/status-badges";
import { PaymentFilterControls } from "@/components/payments/payment-filter-controls";
import { formatDateTime, formatMoney } from "@/lib/format";
import {
  useLedgerDashboard,
  type LedgerHistoryEntryResponse,
  type PaymentMethod,
  type PaymentOperationalState,
} from "@/lib/api/ledger";

const PAGE_SIZE = 20;

const columns: DataTableColumn<LedgerHistoryEntryResponse>[] = [
  {
    key: "entryType",
    header: "Type",
    cell: (row) => <LedgerEntryTypeBadge entryType={row.entryType} />,
    hideOnCard: true,
  },
  { key: "course", header: "Course", cell: (row) => row.courseTitle ?? "—" },
  {
    key: "status",
    header: "Status",
    cell: (row) => (row.operationalState ? <PaymentOperationalStateBadge state={row.operationalState} /> : "—"),
  },
  {
    key: "method",
    header: "Method",
    cell: (row) => (row.method ? PAYMENT_METHOD_LABEL[row.method] : "—"),
  },
  { key: "reference", header: "Reference", cell: (row) => row.reference ?? "—" },
  { key: "amount", header: "Amount", cell: (row) => formatMoney(row.amount) },
  { key: "orderId", header: "Order", cell: (row) => row.orderId, hideOnCard: true },
  { key: "createdAt", header: "Date", cell: (row) => formatDateTime(row.createdAt) },
];

/**
 * Tenant Admin Payment Dashboard tab (PAY-3, extended Wave 6 §4/§5) —
 * unchanged core behavior from the pre-Wave-6 dashboard page, now with
 * `status`/`method` filter controls wired to the new query params and the
 * extended `courseTitle`/`operationalState`/`method`/`reference` columns.
 * Derived entirely from `ledger_entry` rows, never `order`/`payment`
 * directly, per `.claude/rules/payments.md` §2.
 */
export function PaymentDashboardTab() {
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState<PaymentOperationalState | undefined>(undefined);
  const [method, setMethod] = useState<PaymentMethod | undefined>(undefined);
  const query = useLedgerDashboard({ page, size: PAGE_SIZE, status, method });

  const filtersActive = Boolean(status || method);

  function handleStatusChange(next: PaymentOperationalState | undefined) {
    setStatus(next);
    setPage(0);
  }

  function handleMethodChange(next: PaymentMethod | undefined) {
    setMethod(next);
    setPage(0);
  }

  function resetFilters() {
    setStatus(undefined);
    setMethod(undefined);
    setPage(0);
  }

  return (
    <div className="flex flex-col gap-4">
      <PaymentFilterControls
        idPrefix="tenant-admin-payment-dashboard"
        status={status}
        method={method}
        onStatusChange={handleStatusChange}
        onMethodChange={handleMethodChange}
        disabled={query.isFetching}
      />

      <QueryStateBoundary
        query={query}
        loadingLabel="Loading payment ledger…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
        // Only the genuine zero-data case (no filters, page 0, nothing
        // recorded yet ever) swaps the whole surface for `EmptyState` — a
        // filtered or `page > 0` empty result is handled inside `children`
        // below instead, so the filter/pagination controls stay on screen.
        isEmpty={(data) => data.content.length === 0 && page === 0 && !filtersActive}
        emptyState={{
          title: "No payments yet",
          description: "No payments have been recorded for your tenant yet.",
        }}
      >
        {(data) => (
          <div className="flex flex-col gap-4">
            {data.content.length === 0 ? (
              <EmptyState
                title={filtersActive ? "No payments match your filters" : "No more results"}
                description={
                  filtersActive
                    ? "Try a different status/method filter, or clear your filters."
                    : "There are no ledger entries on this page. Go back to an earlier page."
                }
                action={filtersActive ? { label: "Reset filters", onClick: resetFilters } : undefined}
              />
            ) : (
              <DataTable
                columns={columns}
                rows={data.content}
                rowKey={(row) => row.id}
                caption="Payment ledger"
                cardHeading={(row) => row.courseTitle ?? formatMoney(row.amount)}
                cardHeadingAdornment={(row) => <LedgerEntryTypeBadge entryType={row.entryType} />}
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
    </div>
  );
}
