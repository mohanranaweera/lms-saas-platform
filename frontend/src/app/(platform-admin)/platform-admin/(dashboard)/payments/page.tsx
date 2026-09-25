"use client";

import { useState } from "react";
import Link from "next/link";
import { Button } from "@/components/ui/button";
import { PageHeader } from "@/components/ui/page-header";
import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { EmptyState } from "@/components/states/empty-state";
import { LiveRegion } from "@/components/ui/live-region";
import { PaymentFilterControls } from "@/components/payments/payment-filter-controls";
import {
  PAYMENT_METHOD_LABEL,
  PaymentOperationalStateBadge,
} from "@/components/payments/status-badges";
import {
  usePlatformPaymentsDashboard,
  type PlatformLedgerEntryResponse,
} from "@/lib/api/platform-admin-payments";
import type { PaymentMethod, PaymentOperationalState } from "@/lib/api/ledger";
import { formatDateTime, formatMoney, shortId } from "@/lib/format";

/**
 * Cross-Tenant Payment Dashboard (PADASH-2, extended Wave 6 §4/§5). Read-only:
 * no refund/adjustment action is reachable from here — such actions stay on
 * the existing, already-reviewed tenant-scoped payment/refund endpoints.
 * Ledger-derived only (`GET /api/v1/platform-admin/payments/dashboard`), per
 * `.claude/rules/payments.md` §1's "ledger is the source of truth" rule
 * applied at platform scope — no currency/revenue total is computed or shown
 * (module plan §6: no endpoint anywhere computes one, and a client-side sum
 * across paginated cross-tenant rows would violate that rule).
 *
 * Wave 6 §4 adds `status`/`method` filter controls, the same
 * `courseTitle`/`operationalState`/`method`/`reference` columns as the
 * tenant-scoped dashboard. Per the backend's own documented tradeoff, this
 * platform-wide filter applies only within the already-paginated page, so a
 * filtered page may return fewer than `size` rows even when more matches
 * exist later — this UI does not paper over that with a fabricated "load
 * more" guarantee, it just renders whatever page the backend returns.
 */

const PAGE_SIZE = 20;

// `formatMoney(row.amount)` renders a bare decimal with no currency unit:
// `ledger_entry` (V19__create_payment_management_schema.sql) has no
// `currency` column — unlike `student_order`/`payment`, which each carry
// `currency VARCHAR(3) NOT NULL` — so `PlatformLedgerEntryResponse` genuinely
// cannot report one without a backend/schema change (see
// `docs/api/ledger-settlement-management.md`'s "known gaps" note, MVP-020
// review). Not fabricated here.
const columns: DataTableColumn<PlatformLedgerEntryResponse>[] = [
  {
    key: "tenant",
    header: "Tenant",
    cell: (row) => (
      <Link
        href={`/platform-admin/payments/${row.tenantId}`}
        className="font-medium text-foreground hover:underline"
      >
        {row.tenantName ?? shortId(row.tenantId, "Tenant")}
      </Link>
    ),
    hideOnCard: true,
  },
  {
    key: "entryType",
    header: "Entry type",
    cell: (row) => (row.entryType === "PAYMENT_CONFIRMED" ? "Payment confirmed" : "Refund"),
  },
  {
    key: "course",
    header: "Course",
    cell: (row) => row.courseTitle ?? "—",
  },
  {
    key: "status",
    header: "Status",
    cell: (row) =>
      row.operationalState ? <PaymentOperationalStateBadge state={row.operationalState} /> : "—",
  },
  {
    key: "method",
    header: "Method",
    cell: (row) => (row.method ? PAYMENT_METHOD_LABEL[row.method] : "—"),
  },
  {
    key: "reference",
    header: "Reference",
    cell: (row) => row.reference ?? "—",
  },
  {
    key: "amount",
    header: "Amount",
    cell: (row) => formatMoney(row.amount),
  },
  {
    key: "order",
    header: "Order",
    cell: (row) => shortId(row.orderId, "Order"),
  },
  {
    key: "payment",
    header: "Payment",
    cell: (row) => (row.paymentId ? shortId(row.paymentId, "Payment") : "—"),
  },
  {
    key: "reverses",
    header: "Reverses",
    cell: (row) => (row.reversesEntryId ? shortId(row.reversesEntryId, "Entry") : "—"),
    // Rendered so a refund row can be traced back to the entry it reverses
    // (MVP-020 review finding — `reversesEntryId` was typed but never shown).
  },
  {
    key: "createdAt",
    header: "Created at",
    cell: (row) => formatDateTime(row.createdAt),
    hideOnCard: true,
  },
];

export default function PlatformAdminPaymentsPage() {
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState<PaymentOperationalState | undefined>(undefined);
  const [method, setMethod] = useState<PaymentMethod | undefined>(undefined);
  const query = usePlatformPaymentsDashboard({ page, size: PAGE_SIZE, status, method });
  // Background refetch (page-turn) only — `keepPreviousData` (see
  // `usePlatformPaymentsDashboard`) keeps `query.status === "success"` with
  // stale content during this, so it must be surfaced separately, mirroring
  // `audit-log/page.tsx`'s exact pattern.
  const isRefetching = query.isFetching && query.data !== undefined;
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
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Payments"
        description="Platform-wide, ledger-derived payment and refund history — every row names its tenant. Read-only: no refund/adjustment action is reachable from here."
      />

      <PaymentFilterControls
        idPrefix="platform-admin-payments"
        status={status}
        method={method}
        onStatusChange={handleStatusChange}
        onMethodChange={handleMethodChange}
        disabled={query.isFetching}
      />

      <QueryStateBoundary
        query={query}
        loginPath="/platform-admin/login"
        permissionDenied={{ dashboardHref: "/platform-admin/dashboard" }}
        isEmpty={(data) => data.content.length === 0 && page === 0 && !filtersActive}
        emptyState={{
          title: "No payments recorded platform-wide yet",
          description:
            "Confirmed payments and refunds across every tenant will appear here as they happen.",
        }}
      >
        {(data) =>
          data.content.length === 0 ? (
            <EmptyState
              title={filtersActive ? "No payments match your filters" : "No more results"}
              description={
                filtersActive
                  ? "Try a different status/method filter, or clear your filters."
                  : "There are no payments on this page. Go back to an earlier page."
              }
              action={
                filtersActive
                  ? { label: "Reset filters", onClick: resetFilters }
                  : { label: "Go to previous page", onClick: () => setPage((p) => Math.max(0, p - 1)) }
              }
            />
          ) : (
            <div className="flex flex-col gap-4" aria-busy={isRefetching}>
              <LiveRegion
                message={
                  isRefetching
                    ? "Updating…"
                    : `${data.totalElements} payment${data.totalElements === 1 ? "" : "s"} found`
                }
              />
              <div className={isRefetching ? "opacity-60" : undefined}>
                <DataTable
                  columns={columns}
                  rows={data.content}
                  rowKey={(row) => row.id}
                  caption="Platform payments"
                  cardHeading={(row) => (
                    <Link href={`/platform-admin/payments/${row.tenantId}`} className="hover:underline">
                      {row.tenantName ?? shortId(row.tenantId, "Tenant")}
                    </Link>
                  )}
                  cardHeadingAdornment={(row) => (
                    <span className="text-xs text-muted-foreground">{formatDateTime(row.createdAt)}</span>
                  )}
                />
              </div>

              <div className="flex items-center justify-between">
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => setPage((p) => Math.max(0, p - 1))}
                  disabled={page === 0 || query.isFetching}
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
                  onClick={() => setPage((p) => p + 1)}
                  disabled={data.page + 1 >= data.totalPages || query.isFetching}
                >
                  Next
                </Button>
              </div>
            </div>
          )
        }
      </QueryStateBoundary>
    </div>
  );
}
