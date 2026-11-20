"use client";

import { useState, type FormEvent } from "react";
import Link from "next/link";
import { Eye, Search, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { DateInput } from "@/components/ui/date-input";
import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import { EmptyState } from "@/components/states/empty-state";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { PaymentFilterControls } from "@/components/payments/payment-filter-controls";
import { PAYMENT_METHOD_LABEL, PaymentOperationalStateBadge } from "@/components/payments/status-badges";
import { formatDateTime, formatMoney } from "@/lib/format";
import { useAdminOrders, type AdminOrderRow } from "@/lib/api/admin-orders";
import type { PaymentMethod, PaymentOperationalState } from "@/lib/api/ledger";
import { OrderDetailsDrawer } from "./order-details-drawer";
import { HubPagination } from "./hub-pagination";

const ORDER_NUMBER_PATTERN = /^\s*(ORD-)?\d{1,12}\s*$/i;

/**
 * PAY-HUB-01 "All Orders" tab: one row per order (`GET /ledger/orders`),
 * newest first, searchable by order number and order date (inclusive days,
 * tenant timezone - resolved by the backend), with the existing status and
 * method filters. "View order" opens the full order drawer.
 */
export function AllOrdersTab() {
  const [orderNumberInput, setOrderNumberInput] = useState("");
  const [orderNumber, setOrderNumber] = useState("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [status, setStatus] = useState<PaymentOperationalState | undefined>(undefined);
  const [method, setMethod] = useState<PaymentMethod | undefined>(undefined);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [orderNumberError, setOrderNumberError] = useState<string | null>(null);
  const [openOrderId, setOpenOrderId] = useState<string | null>(null);

  const rangeInvalid = !!from && !!to && from > to;
  const query = useAdminOrders(
    { orderNumber: orderNumber || undefined, from: from || undefined, to: to || undefined, status, method, page, size },
    { enabled: !rangeInvalid }
  );
  const filtersActive = Boolean(orderNumber || from || to || status || method);

  function submitOrderNumber(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const value = orderNumberInput.trim();
    if (value && !ORDER_NUMBER_PATTERN.test(value)) {
      setOrderNumberError("Enter an order number like ORD-000123 or 123.");
      return;
    }
    setOrderNumberError(null);
    setOrderNumber(value);
    setPage(0);
  }

  function resetFilters() {
    setOrderNumberInput("");
    setOrderNumber("");
    setOrderNumberError(null);
    setFrom("");
    setTo("");
    setStatus(undefined);
    setMethod(undefined);
    setPage(0);
  }

  const columns: DataTableColumn<AdminOrderRow>[] = [
    {
      key: "order",
      header: "Order",
      cell: (row) => <span className="font-medium whitespace-nowrap text-foreground">{row.displayOrderNumber}</span>,
      hideOnCard: true,
    },
    { key: "date", header: "Order date", cell: (row) => formatDateTime(row.createdAt) },
    {
      key: "student",
      header: "Student",
      cell: (row) =>
        row.studentId ? (
          <Link
            href={`/tenant-admin/students/${encodeURIComponent(row.studentId)}`}
            className="font-medium text-foreground hover:underline"
          >
            {row.studentName ?? "Student"}
          </Link>
        ) : (
          (row.studentName ?? "—")
        ),
    },
    {
      key: "items",
      header: "Items",
      cell: (row) =>
        `${row.firstItemTitle ?? "Course"}${row.itemCount > 1 ? ` +${row.itemCount - 1} more` : ""}`,
    },
    { key: "amount", header: "Total", cell: (row) => formatMoney(row.amount, row.currency), className: "whitespace-nowrap" },
    {
      key: "status",
      header: "Status",
      cell: (row) => (row.operationalState ? <PaymentOperationalStateBadge state={row.operationalState} /> : "—"),
      hideOnCard: true,
    },
    {
      key: "method",
      header: "Method",
      cell: (row) => (row.method ? PAYMENT_METHOD_LABEL[row.method] : "—"),
    },
    { key: "reference", header: "Reference", cell: (row) => row.reference ?? "—" },
    {
      key: "view",
      header: "Order",
      cell: (row) => <ViewOrderButton row={row} onOpen={setOpenOrderId} />,
      hideOnCard: true,
    },
  ];

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-col gap-4 rounded-xl border border-border bg-card p-4">
        <div className="flex flex-col gap-4 lg:flex-row lg:items-end">
          <form className="flex flex-col gap-1.5 lg:w-64" onSubmit={submitOrderNumber} role="search" noValidate>
            <Label htmlFor="orders-order-number">Order number</Label>
            <div className="flex gap-2">
              <div className="relative flex-1">
                <Search
                  className="pointer-events-none absolute top-2 left-2.5 size-4 text-muted-foreground"
                  aria-hidden="true"
                />
                <Input
                  id="orders-order-number"
                  value={orderNumberInput}
                  onChange={(event) => setOrderNumberInput(event.target.value)}
                  placeholder="ORD-000123"
                  className="pl-8"
                  aria-invalid={!!orderNumberError}
                  aria-describedby={orderNumberError ? "orders-order-number-error" : undefined}
                  data-testid="orders-order-number"
                />
              </div>
              <Button type="submit" variant="secondary">
                Search
              </Button>
            </div>
            {orderNumberError ? (
              <p id="orders-order-number-error" role="alert" className="text-xs text-destructive">
                {orderNumberError}
              </p>
            ) : null}
          </form>
          <DateInput
            idPrefix="orders-date"
            fromLabel="Order date from"
            toLabel="Order date to"
            fromValue={from}
            toValue={to}
            onFromChange={(value) => {
              setFrom(value);
              setPage(0);
            }}
            onToChange={(value) => {
              setTo(value);
              setPage(0);
            }}
            invalid={rangeInvalid}
            errorId="orders-date-error"
          />
        </div>
        {rangeInvalid ? (
          <p id="orders-date-error" role="alert" className="text-xs text-destructive">
            “From” must be on or before “To”.
          </p>
        ) : null}
        <div className="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
          <PaymentFilterControls
            idPrefix="orders"
            status={status}
            method={method}
            onStatusChange={(next) => {
              setStatus(next);
              setPage(0);
            }}
            onMethodChange={(next) => {
              setMethod(next);
              setPage(0);
            }}
            disabled={query.isFetching}
          />
          <Button type="button" variant="outline" onClick={resetFilters} disabled={!filtersActive && !orderNumberInput}>
            <X aria-hidden="true" />
            Clear
          </Button>
        </div>
      </div>

      {rangeInvalid ? null : (
        <QueryStateBoundary
          query={query}
          loadingLabel="Loading orders…"
          loginPath="/login"
          permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
          isEmpty={(data) => data.content.length === 0 && page === 0 && !filtersActive}
          emptyState={{ title: "No orders yet", description: "Orders placed by your students will appear here." }}
        >
          {(data) => (
            <div className="flex flex-col gap-4">
              {data.content.length === 0 ? (
                <EmptyState
                  title={filtersActive ? "No orders match your search" : "No more orders"}
                  description={
                    filtersActive
                      ? "Check the order number or date range, or clear the filters."
                      : "There are no orders on this page. Go back to an earlier page."
                  }
                  action={filtersActive ? { label: "Reset filters", onClick: resetFilters } : undefined}
                />
              ) : (
                <DataTable
                  columns={columns}
                  rows={data.content}
                  rowKey={(row) => row.orderId}
                  caption="Orders"
                  cardHeading={(row) => row.displayOrderNumber}
                  cardHeadingAdornment={(row) =>
                    row.operationalState ? <PaymentOperationalStateBadge state={row.operationalState} /> : null
                  }
                  cardFooter={(row) => <ViewOrderButton row={row} onOpen={setOpenOrderId} />}
                />
              )}
              <HubPagination
                idPrefix="orders"
                page={data.page}
                size={size}
                totalElements={data.totalElements}
                onPageChange={setPage}
                onSizeChange={(next) => {
                  setSize(next);
                  setPage(0);
                }}
              />
            </div>
          )}
        </QueryStateBoundary>
      )}

      <OrderDetailsDrawer orderId={openOrderId} onClose={() => setOpenOrderId(null)} />
    </div>
  );
}

function ViewOrderButton({ row, onOpen }: { row: AdminOrderRow; onOpen: (orderId: string) => void }) {
  return (
    <Button
      type="button"
      variant="link"
      size="sm"
      className="h-auto p-0"
      onClick={() => onOpen(row.orderId)}
      aria-label={`View order ${row.displayOrderNumber}`}
    >
      <Eye aria-hidden="true" />
      View order
    </Button>
  );
}
