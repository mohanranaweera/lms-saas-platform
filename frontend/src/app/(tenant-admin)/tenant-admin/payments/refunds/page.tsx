"use client";

import { useState } from "react";
import { Search, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { EmptyState } from "@/components/states/empty-state";
import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import { formatDateTime } from "@/lib/format";
import { useAuth } from "@/lib/auth/auth-context";
import { canProcessRefunds } from "@/lib/auth/permissions";
import { useLedgerDashboard, type LedgerHistoryEntryResponse } from "@/lib/api/ledger";
import { useRefundVerification } from "@/lib/api/refund-verification";
import { RefundDialog } from "./refund-dialog";
import { OrderDetailsPanel } from "./order-details-panel";

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
  const [search, setSearch] = useState("");
  const [method, setMethod] = useState<"GATEWAY" | "MANUAL_SLIP" | "FREE" | "STAFF_GRANTED" | "">("");
  const [detailsPaymentId, setDetailsPaymentId] = useState("");
  const details = useRefundVerification(detailsPaymentId, !!detailsPaymentId);
  const query = useLedgerDashboard({ page, size: PAGE_SIZE, method: method || undefined, search });

  const baseColumns: DataTableColumn<LedgerHistoryEntryResponse>[] = [
    { key: "orderId", header: "Order", cell: (row) => <Button type="button" variant="link" className="h-auto p-0" onClick={() => setDetailsPaymentId(row.paymentId ?? "")}>{row.paymentId ? "View order details" : `Order · ${shortId(row.orderId)}`}</Button> },
    { key: "paymentId", header: "Payment", cell: (row) => <span title={row.paymentId ?? undefined}>Payment · {row.paymentId ? shortId(row.paymentId) : "—"}</span> },
    { key: "course", header: "Course", cell: (row) => row.courseTitle ?? "Unknown course" },
    { key: "method", header: "Payment method", cell: (row) => methodLabel(row.method, row.reference) },
    { key: "amount", header: "Amount", cell: (row) => `${row.amount.toFixed(2)} ${row.reference ? "" : ""}` },
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

      <div className="grid gap-3 rounded-xl border bg-card p-4 md:grid-cols-[1fr_14rem_auto]">
        <label className="flex flex-col gap-1 text-sm font-medium" htmlFor="refund-search">Search order, payment, reference, or course
          <span className="relative"><Search className="pointer-events-none absolute left-3 top-2.5 size-4 text-muted-foreground" aria-hidden="true" /><input id="refund-search" value={search} onChange={(event) => { setSearch(event.target.value); setPage(0); }} placeholder="Search identifiers or course" className="h-10 w-full rounded-md border bg-background pl-9 pr-3 font-normal" /></span>
        </label>
        <label className="flex flex-col gap-1 text-sm font-medium" htmlFor="refund-method">Payment method
          <select id="refund-method" value={method} onChange={(event) => { setMethod(event.target.value as typeof method); setPage(0); }} className="h-10 rounded-md border bg-background px-3 font-normal"><option value="">All methods</option><option value="GATEWAY">Online payment</option><option value="MANUAL_SLIP">Bank transfer slip</option><option value="FREE">Free</option><option value="STAFF_GRANTED">Staff granted</option></select>
        </label>
        <Button type="button" variant="outline" className="self-end" onClick={() => { setSearch(""); setMethod(""); }} disabled={!search && !method}><X aria-hidden="true" />Clear</Button>
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
                  cardHeading={(row) => row.courseTitle ?? `Order · ${shortId(row.orderId)}`}
                  cardFooter={(row) => <div className="flex gap-2">{row.paymentId ? <Button type="button" size="sm" variant="outline" onClick={() => setDetailsPaymentId(row.paymentId ?? "")}>View order details</Button> : null}{canRefund ? <RefundDialog entry={row} /> : null}</div>}
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
      {detailsPaymentId ? <OrderDetailsPanel data={details.data} loading={details.isLoading} error={details.isError} onClose={() => setDetailsPaymentId("")} /> : null}
    </div>
  );
}
function shortId(value: string) { return value.slice(0, 8); }
function methodLabel(method: LedgerHistoryEntryResponse["method"], reference: string | null) { if (method === "GATEWAY") return `Online payment${reference ? ` · ${reference}` : ""}`; if (method === "MANUAL_SLIP") return `Bank transfer slip${reference ? ` · ${reference}` : ""}`; if (method === "FREE") return "Free course"; if (method === "STAFF_GRANTED") return "Staff granted"; return "Unknown method"; }
