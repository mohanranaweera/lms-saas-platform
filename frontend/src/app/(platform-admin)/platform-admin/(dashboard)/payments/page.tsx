"use client";

import { useState } from "react";
import Link from "next/link";
import { Button } from "@/components/ui/button";
import { PageHeader } from "@/components/ui/page-header";
import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { EmptyState } from "@/components/states/empty-state";
import { LiveRegion } from "@/components/ui/live-region";
import {
  usePlatformPaymentsDashboard,
  type PlatformLedgerEntryResponse,
} from "@/lib/api/platform-admin-payments";
import { formatDateTime, formatMoney, shortId } from "@/lib/format";

/**
 * Cross-Tenant Payment Dashboard (PADASH-2). Read-only: no refund/adjustment
 * action is reachable from here — such actions stay on the existing,
 * already-reviewed tenant-scoped payment/refund endpoints. Ledger-derived
 * only (`GET /api/v1/platform-admin/payments/dashboard`), per
 * `.claude/rules/payments.md` §1's "ledger is the source of truth" rule
 * applied at platform scope — no currency/revenue total is computed or shown
 * (module plan §6: no endpoint anywhere computes one, and a client-side sum
 * across paginated cross-tenant rows would violate that rule).
 *
 * There is exactly one empty state here (not two): this endpoint takes no
 * filter params, so "no results match your filter" can never actually
 * occur — fabricating that second variant would misrepresent a condition
 * that isn't real.
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
  const query = usePlatformPaymentsDashboard({ page, size: PAGE_SIZE });
  // Background refetch (page-turn) only — `keepPreviousData` (see
  // `usePlatformPaymentsDashboard`) keeps `query.status === "success"` with
  // stale content during this, so it must be surfaced separately, mirroring
  // `audit-log/page.tsx`'s exact pattern.
  const isRefetching = query.isFetching && query.data !== undefined;

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Payments"
        description="Platform-wide, ledger-derived payment and refund history — every row names its tenant. Read-only: no refund/adjustment action is reachable from here."
      />

      <QueryStateBoundary
        query={query}
        loginPath="/platform-admin/login"
        permissionDenied={{ dashboardHref: "/platform-admin/dashboard" }}
        isEmpty={(data) => data.content.length === 0 && page === 0}
        emptyState={{
          title: "No payments recorded platform-wide yet",
          description:
            "Confirmed payments and refunds across every tenant will appear here as they happen.",
        }}
      >
        {(data) =>
          data.content.length === 0 ? (
            <EmptyState
              title="No more results"
              description="There are no payments on this page. Go back to an earlier page."
              action={{ label: "Go to previous page", onClick: () => setPage((p) => Math.max(0, p - 1)) }}
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
