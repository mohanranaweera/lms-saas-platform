"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { EmptyState } from "@/components/states/empty-state";
import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import { formatDateTime } from "@/lib/format";
import { useAuth } from "@/lib/auth/auth-context";
import { canProcessRefunds } from "@/lib/auth/permissions";
import { useLedgerDashboard, type LedgerHistoryEntryResponse } from "@/lib/api/ledger";
import { RefundDialog } from "./refund-dialog";

const PAGE_SIZE = 20;

/**
 * Tenant Admin Refunds (PAY-4). There is no dedicated "refundable payments"
 * endpoint on this backend — this screen reuses the same `GET
 * /api/v1/ledger/dashboard` data the Payment Dashboard uses and filters
 * client-side to `entryType === "PAYMENT_CONFIRMED"` rows, which is the
 * correct design given the real API surface (see MVP-010 plan/prompt notes).
 *
 * The "Refund" action is only rendered for `canProcessRefunds(role)` — pure
 * UX convenience; `POST /api/v1/payments/{id}/refunds` independently
 * re-enforces the literal Tenant Admin/Finance Staff requirement server-side
 * regardless of what renders here.
 */
export default function TenantAdminRefundsPage() {
  const { session } = useAuth();
  const canRefund = canProcessRefunds(session?.role ?? null);
  const [page, setPage] = useState(0);
  const query = useLedgerDashboard({ page, size: PAGE_SIZE });

  const baseColumns: DataTableColumn<LedgerHistoryEntryResponse>[] = [
    { key: "orderId", header: "Order", cell: (row) => row.orderId },
    { key: "paymentId", header: "Payment", cell: (row) => row.paymentId ?? "—" },
    { key: "amount", header: "Amount", cell: (row) => row.amount.toFixed(2) },
    { key: "createdAt", header: "Confirmed", cell: (row) => formatDateTime(row.createdAt) },
  ];
  const columns: DataTableColumn<LedgerHistoryEntryResponse>[] = canRefund
    ? [
        ...baseColumns,
        {
          key: "actions",
          header: "Actions",
          cell: (row) => <RefundDialog entry={row} />,
          hideOnCard: true,
        },
      ]
    : baseColumns;

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">Refunds</h1>
        <p className="text-sm text-muted-foreground">
          Confirmed payments eligible for a refund. A refund creates a new record — the original
          payment is never modified.
        </p>
      </div>

      <QueryStateBoundary
        query={query}
        loadingLabel="Loading confirmed payments…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
        isEmpty={(data) => data.content.length === 0}
        emptyState={{
          title: "No confirmed payments yet",
          description:
            "Once a payment is confirmed in your tenant, it will appear here as eligible for a refund.",
        }}
      >
        {(data) => {
          const confirmed = data.content.filter((entry) => entry.entryType === "PAYMENT_CONFIRMED");
          return (
            <div className="flex flex-col gap-4">
              {confirmed.length === 0 ? (
                <EmptyState
                  title="No confirmed payments on this page"
                  description="This page of results has no refund-eligible payments. Try an earlier page."
                />
              ) : (
                <DataTable
                  columns={columns}
                  rows={confirmed}
                  rowKey={(row) => row.id}
                  caption="Refund-eligible payments"
                  cardHeading={(row) => row.amount.toFixed(2)}
                  cardFooter={canRefund ? (row) => <RefundDialog entry={row} /> : undefined}
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
          );
        }}
      </QueryStateBoundary>
    </div>
  );
}
