"use client";

import { useState } from "react";
import { ChevronDown, ChevronRight } from "lucide-react";
import { Button } from "@/components/ui/button";
import { LoadingState } from "@/components/states/loading-state";
import { EmptyState } from "@/components/states/empty-state";
import { ErrorState } from "@/components/states/error-state";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { LedgerEntryTypeBadge, PaymentOperationalStateBadge } from "@/components/payments/status-badges";
import { formatDateTime, formatMoney, shortId } from "@/lib/format";
import { useLedgerHistory } from "@/lib/api/ledger";
import { useOrder, useOrderLines, type OrderResponse } from "@/lib/api/payments";
import { isApiClientError } from "@/lib/api/error";

function OrderDetail({ orderId }: { orderId: string }) {
  const order = useOrder(orderId);
  const lines = useOrderLines(orderId);
  if (order.status === "pending" || lines.status === "pending") return <LoadingState label="Loading order details…" />;
  if (order.status === "error" || lines.status === "error") return <ErrorState message="Could not load order details." onRetry={() => { void order.refetch(); void lines.refetch(); }} />;
  return <div className="mt-4 flex flex-col gap-4 rounded-lg bg-muted/30 p-4"><OrderSummary order={order.data} /><div><h3 className="text-sm font-semibold text-foreground">Products purchased</h3><ul className="mt-2 flex flex-col gap-2">{lines.data.map((line) => <li key={line.lineNo} className="flex flex-col gap-1 border-b border-border pb-2 text-sm last:border-0"><div className="flex justify-between gap-3"><span className="font-medium text-foreground">{line.courseTitle ?? line.label}</span><span>{formatMoney(line.lineTotal, line.currency)}</span></div><span className="text-xs text-muted-foreground">{line.scope} · {line.label}</span></li>)}</ul></div></div>;
}

function OrderSummary({ order }: { order: OrderResponse }) {
  return <dl className="grid grid-cols-2 gap-x-4 gap-y-2 text-xs text-muted-foreground sm:grid-cols-4"><div><dt className="font-medium text-foreground">Order number</dt><dd>{order.displayOrderNumber || `Order #${order.orderNumber}`}</dd></div><div><dt className="font-medium text-foreground">Status</dt><dd>{order.status}</dd></div><div><dt className="font-medium text-foreground">Date</dt><dd>{formatDateTime(order.createdAt)}</dd></div><div><dt className="font-medium text-foreground">Total</dt><dd className="font-semibold text-foreground">{formatMoney(order.amount, order.currency)}</dd></div></dl>;
}

export function PaymentHistoryPanel() {
  const query = useLedgerHistory();
  const [expanded, setExpanded] = useState<string | null>(null);
  return <QueryStateBoundary query={query} loadingLabel="Loading payment history…" loginPath="/login" permissionDenied={{ dashboardHref: "/student/dashboard" }} isEmpty={(entries) => entries.length === 0} emptyState={{ title: "No payments yet", description: "Completed payments and their purchased products appear here." }}>{(entries) => { const orders = Array.from(new Map(entries.map((entry) => [entry.orderId, entry])).values()); return <ul className="flex flex-col gap-3">{orders.map((entry) => { const open = expanded === entry.orderId; return <li key={entry.orderId} className="rounded-xl border border-border bg-card p-4 shadow-sm"><div className="flex flex-wrap items-center justify-between gap-3"><div><p className="font-medium text-foreground">{entry.courseTitle ?? `Order ${shortId(entry.orderId, "")}`}</p><p className="mt-1 text-xs text-muted-foreground">Order {shortId(entry.orderId, "")} · {formatDateTime(entry.createdAt)}</p></div><div className="flex items-center gap-3"><span className="font-semibold text-foreground">{formatMoney(entry.amount)}</span><LedgerEntryTypeBadge entryType={entry.entryType} />{entry.operationalState ? <PaymentOperationalStateBadge state={entry.operationalState} /> : null}<Button type="button" variant="outline" size="sm" aria-expanded={open} onClick={() => setExpanded(open ? null : entry.orderId)}>{open ? <ChevronDown aria-hidden="true" /> : <ChevronRight aria-hidden="true" />} {open ? "Hide details" : "View more"}</Button></div></div>{open ? <OrderDetail orderId={entry.orderId} /> : null}</li>;})}</ul>;}}</QueryStateBoundary>;
}
