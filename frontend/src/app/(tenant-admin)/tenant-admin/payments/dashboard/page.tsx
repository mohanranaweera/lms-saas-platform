"use client";

import { useState } from "react";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { EmptyState } from "@/components/states/empty-state";
import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import { Button } from "@/components/ui/button";
import { LedgerEntryTypeBadge } from "@/components/payments/status-badges";
import { formatDateTime } from "@/lib/format";
import { useLedgerDashboard, type LedgerHistoryEntryResponse } from "@/lib/api/ledger";

const PAGE_SIZE = 20;

const columns: DataTableColumn<LedgerHistoryEntryResponse>[] = [
  {
    key: "entryType",
    header: "Type",
    cell: (row) => <LedgerEntryTypeBadge entryType={row.entryType} />,
    hideOnCard: true,
  },
  { key: "amount", header: "Amount", cell: (row) => row.amount.toFixed(2) },
  { key: "orderId", header: "Order", cell: (row) => row.orderId },
  { key: "paymentId", header: "Payment", cell: (row) => row.paymentId ?? "—" },
  { key: "createdAt", header: "Date", cell: (row) => formatDateTime(row.createdAt) },
];

/**
 * Tenant Admin Payment Dashboard (PAY-3). `GET /api/v1/ledger/dashboard` —
 * server-enforced `PAYMENTS_SLIPS`/`VIEW` (Tenant Admin, Finance Staff,
 * Student Support, Read-only Auditor; 403 for every other role). This page
 * always issues the real request and lets `QueryStateBoundary` render
 * `PermissionDeniedState` on an actual 403 — the only server-verified gate
 * (per `.claude/rules/frontend.md`, `PermissionDeniedState` must never be
 * driven by a fabricated/client-guessed reason). `canViewPaymentDashboard`
 * is instead used to gate the "Payments"/"Refunds" nav entries themselves
 * (`components/layout/nav/tenant-admin-nav.tsx`) — pure UX convenience,
 * consistent with `canManageStudents`'s existing button-hiding pattern.
 *
 * Deliberately different from the Tenant Admin Overview's Payments KPI tile
 * (MVP-015, `app/(tenant-admin)/tenant-admin/dashboard/page.tsx`), which
 * instead gates the *request itself* via `useLedgerDashboard`'s `enabled`
 * option — that page is every in-scope role's landing page, so avoiding a
 * guaranteed 403 on every load for the 4 roles without this grant is worth
 * the extra gating; this dedicated page is only ever reached by clicking the
 * already-gated nav entry, so relying on the real backend 403 here is
 * simpler and sufficient. Both conventions are safe (neither weakens backend
 * enforcement); this note exists so the divergence reads as deliberate.
 *
 * Derived entirely from `ledger_entry` rows, never from `order`/`payment`
 * directly, per `.claude/rules/payments.md` §2.
 */
export default function TenantAdminPaymentDashboardPage() {
  const [page, setPage] = useState(0);
  const query = useLedgerDashboard({ page, size: PAGE_SIZE });

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">Payment dashboard</h1>
        <p className="text-sm text-muted-foreground">
          Every confirmed payment and refund recorded in your tenant&apos;s ledger.
        </p>
      </div>

      <QueryStateBoundary
        query={query}
        loadingLabel="Loading payment ledger…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
        // Only the genuine zero-data case (page 0, nothing recorded yet ever)
        // swaps the whole surface for `EmptyState` — a `page > 0` empty
        // result is handled inside `children` below instead, so the
        // pagination controls stay on screen and the user can actually act
        // on "go back to an earlier page" rather than being told to do
        // something with no control left to do it with.
        isEmpty={(data) => data.content.length === 0 && page === 0}
        emptyState={{
          title: "No payments yet",
          description: "No payments have been recorded for your tenant yet.",
        }}
      >
        {(data) => (
          <div className="flex flex-col gap-4">
            {data.content.length === 0 ? (
              <EmptyState
                title="No more results"
                description="There are no ledger entries on this page. Go back to an earlier page."
              />
            ) : (
              <DataTable
                columns={columns}
                rows={data.content}
                rowKey={(row) => row.id}
                caption="Payment ledger"
                cardHeading={(row) => row.amount.toFixed(2)}
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
