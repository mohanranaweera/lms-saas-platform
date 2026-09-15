"use client";

import { useState } from "react";
import { useParams } from "next/navigation";
import { Button } from "@/components/ui/button";
import { PageHeader } from "@/components/ui/page-header";
import { Breadcrumbs } from "@/components/ui/breadcrumbs";
import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { EmptyState } from "@/components/states/empty-state";
import { LiveRegion } from "@/components/ui/live-region";
import { usePlatformAdminTenantDetail } from "@/lib/api/platform-admin-tenants";
import { StatusBadge } from "../../tenants/status-badge";
import {
  usePlatformPaymentsTenantDrillDown,
  type PlatformLedgerEntryResponse,
} from "@/lib/api/platform-admin-payments";
import { formatDateTime, formatMoney, shortId } from "@/lib/format";

/**
 * Tenant Payment Drill-down (PADASH-2). First real consumer of both
 * `Breadcrumbs` and `PageHeader`'s `showTenantContext` banner. Fetches BOTH
 * the tenant detail (for the banner/breadcrumb name+status — needed even
 * when the ledger drill-down itself returns zero rows) AND the ledger
 * drill-down (for the table) as two independent queries.
 *
 * If the tenant-detail query itself 404s, that is the primary error to
 * surface (the tenant doesn't exist) — its own `QueryStateBoundary` renders
 * first and gates the ledger table entirely, so a nonexistent tenant id
 * never even attempts the ledger fetch's own error/empty rendering.
 *
 * Read-only: no mutation control anywhere on this screen.
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
    key: "entryType",
    header: "Entry type",
    cell: (row) => (row.entryType === "PAYMENT_CONFIRMED" ? "Payment confirmed" : "Refund"),
    hideOnCard: true,
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

export default function PlatformAdminTenantPaymentsDrillDownPage() {
  const params = useParams<{ tenantId: string }>();
  const tenantId = params.tenantId;
  const [page, setPage] = useState(0);

  const tenantQuery = usePlatformAdminTenantDetail(tenantId);
  const ledgerQuery = usePlatformPaymentsTenantDrillDown(tenantId, { page, size: PAGE_SIZE });
  // Background refetch (page-turn) only — `keepPreviousData` (see
  // `usePlatformPaymentsTenantDrillDown`) keeps `ledgerQuery.status ===
  // "success"` with stale content during this, so it must be surfaced
  // separately, mirroring `audit-log/page.tsx`'s exact pattern.
  const isRefetching = ledgerQuery.isFetching && ledgerQuery.data !== undefined;

  return (
    <div className="flex flex-col gap-6">
      <QueryStateBoundary
        query={tenantQuery}
        loginPath="/platform-admin/login"
        permissionDenied={{ dashboardHref: "/platform-admin/dashboard" }}
        genericErrorMessage="Something went wrong loading this tenant. Please try again."
      >
        {(tenant) => (
          <>
            <Breadcrumbs
              items={[
                { label: "Payments", href: "/platform-admin/payments" },
                { label: tenant.name },
              ]}
            />
            <PageHeader
              title="Payments"
              description="Ledger-derived payment and refund history for this tenant only. Read-only: no refund/adjustment action is reachable from here."
              showTenantContext
              tenantContext={{
                id: tenant.id,
                name: tenant.name,
                statusBadge: <StatusBadge status={tenant.status} />,
              }}
            />

            <QueryStateBoundary
              query={ledgerQuery}
              loginPath="/platform-admin/login"
              permissionDenied={{ dashboardHref: "/platform-admin/dashboard" }}
              genericErrorMessage="Something went wrong loading this tenant's payments. Please try again."
              isEmpty={(data) => data.content.length === 0 && page === 0}
              emptyState={{
                title: "No payments recorded for this tenant yet",
                description: `${tenant.name} has no confirmed payments or refunds yet.`,
              }}
            >
              {(data) =>
                data.content.length === 0 ? (
                  <EmptyState
                    title="No more results"
                    description="There are no payments on this page. Go back to an earlier page."
                    action={{
                      label: "Go to previous page",
                      onClick: () => setPage((p) => Math.max(0, p - 1)),
                    }}
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
                        caption={`Payments for ${tenant.name}`}
                        cardHeading={(row) =>
                          row.entryType === "PAYMENT_CONFIRMED" ? "Payment confirmed" : "Refund"
                        }
                        cardHeadingAdornment={(row) => (
                          <span className="text-xs text-muted-foreground">
                            {formatDateTime(row.createdAt)}
                          </span>
                        )}
                      />
                    </div>

                    <div className="flex items-center justify-between">
                      <Button
                        type="button"
                        variant="outline"
                        size="sm"
                        onClick={() => setPage((p) => Math.max(0, p - 1))}
                        disabled={page === 0 || ledgerQuery.isFetching}
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
                        disabled={data.page + 1 >= data.totalPages || ledgerQuery.isFetching}
                      >
                        Next
                      </Button>
                    </div>
                  </div>
                )
              }
            </QueryStateBoundary>
          </>
        )}
      </QueryStateBoundary>
    </div>
  );
}
