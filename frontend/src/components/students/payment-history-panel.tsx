"use client";

import { useT } from "@/i18n/i18n-provider";
import { useLocaleFormat } from "@/i18n/use-locale-format";

import { useState } from "react";
import Link from "next/link";
import { ChevronDown, ChevronRight } from "lucide-react";
import { Button } from "@/components/ui/button";
import { LoadingState } from "@/components/states/loading-state";
import { EmptyState } from "@/components/states/empty-state";
import { ErrorState } from "@/components/states/error-state";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { LedgerEntryTypeBadge, PAYMENT_METHOD_LABEL, PaymentOperationalStateBadge } from "@/components/payments/status-badges";
import { shortId } from "@/lib/format";
import { useLedgerHistory } from "@/lib/api/ledger";
import { useOrder, useOrderLines, type OrderResponse } from "@/lib/api/payments";
import { isApiClientError } from "@/lib/api/error";

function OrderDetail({ orderId }: { orderId: string }) {
  const order = useOrder(orderId);
  const lines = useOrderLines(orderId);
  const t = useT();
  const { formatMoney } = useLocaleFormat();
  if (order.status === "pending" || lines.status === "pending") return <LoadingState label={t("student.paymentHistory.detailLoading")} />;
  if (order.status === "error" || lines.status === "error") return <ErrorState message={t("student.paymentHistory.detailError")} onRetry={() => { void order.refetch(); void lines.refetch(); }} />;
  return <div className="mt-4 flex flex-col gap-4 rounded-lg bg-muted/30 p-4"><OrderSummary order={order.data} /><div><h3 className="text-sm font-semibold text-foreground">{t("student.paymentHistory.products")}</h3><ul className="mt-2 flex flex-col gap-2">{lines.data.map((line) => <li key={line.lineNo} className="flex flex-col gap-1 border-b border-border pb-2 text-sm last:border-0"><div className="flex justify-between gap-3"><span className="font-medium text-foreground">{line.courseTitle ?? line.label}</span><span>{formatMoney(line.lineTotal, line.currency)}</span></div><span className="text-xs text-muted-foreground">{line.scope} · {line.label}</span></li>)}</ul></div></div>;
}

function OrderSummary({ order }: { order: OrderResponse }) {
  const t = useT();
  const { formatMoney, formatDateTime } = useLocaleFormat();
  return <dl className="grid grid-cols-2 gap-x-4 gap-y-2 text-xs text-muted-foreground sm:grid-cols-4"><div><dt className="font-medium text-foreground">{t("student.paymentHistory.orderNumber")}</dt><dd>{order.displayOrderNumber || t("student.paymentHistory.orderHash", { number: order.orderNumber })}</dd></div><div><dt className="font-medium text-foreground">{t("student.paymentHistory.status")}</dt><dd>{order.status}</dd></div><div><dt className="font-medium text-foreground">{t("student.paymentHistory.date")}</dt><dd>{formatDateTime(order.createdAt)}</dd></div><div><dt className="font-medium text-foreground">{t("student.paymentHistory.total")}</dt><dd className="font-semibold text-foreground">{formatMoney(order.amount, order.currency)}</dd></div></dl>;
}

/**
 * One row per ledger entry (payment, each refund), never collapsed per order:
 * payments.md §2 derives payment history from ledger entries, so a refunded
 * order shows its payment and every refund. Method and reference come from
 * the enriched ledger response verbatim; "View more" expands the order's
 * products.
 */
export function PaymentHistoryPanel() {
  const query = useLedgerHistory();
  const [expanded, setExpanded] = useState<string | null>(null);
  const t = useT();
  const { formatMoney, formatDateTime } = useLocaleFormat();
  return (
    <QueryStateBoundary
      query={query}
      loadingLabel={t("student.paymentHistory.loading")}
      loginPath="/login"
      permissionDenied={{ dashboardHref: "/student/dashboard" }}
      isEmpty={(entries) => entries.length === 0}
      emptyState={{ title: t("student.dashboard.noPayments"), description: t("student.paymentHistory.emptyDescription") }}
    >
      {(entries) => (
        <ul className="flex flex-col gap-3">
          {entries.map((entry) => {
            const open = expanded === entry.id;
            const orderLabel = t("student.paymentHistory.order", { id: shortId(entry.orderId, "") });
            return (
              <li key={entry.id} className="rounded-xl border border-border bg-card p-4 shadow-sm">
                <div className="flex flex-wrap items-center justify-between gap-3">
                  <div>
                    <p className="font-medium text-foreground">{entry.courseTitle ?? orderLabel}</p>
                    <p className="mt-1 text-xs text-muted-foreground">
                      {orderLabel} · {formatDateTime(entry.createdAt)}
                    </p>
                  </div>
                  <div className="flex flex-wrap items-center gap-3">
                    <span className="font-semibold text-foreground">{formatMoney(entry.amount)}</span>
                    <LedgerEntryTypeBadge entryType={entry.entryType} />
                    {entry.operationalState ? <PaymentOperationalStateBadge state={entry.operationalState} /> : null}
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      aria-expanded={open}
                      onClick={() => setExpanded(open ? null : entry.id)}
                    >
                      {open ? <ChevronDown aria-hidden="true" /> : <ChevronRight aria-hidden="true" />}{" "}
                      {open ? t("student.paymentHistory.hide") : t("student.paymentHistory.more")}
                    </Button>
                  </div>
                </div>
                <dl className="mt-3 grid grid-cols-2 gap-x-4 gap-y-1 text-xs text-muted-foreground sm:grid-cols-4">
                  <div>
                    <dt className="font-medium text-foreground">{t("student.paymentHistory.method")}</dt>
                    <dd>{entry.method ? PAYMENT_METHOD_LABEL[entry.method] : "—"}</dd>
                  </div>
                  <div>
                    <dt className="font-medium text-foreground">{t("student.paymentHistory.reference")}</dt>
                    <dd className="break-all">{entry.reference ?? "—"}</dd>
                  </div>
                  {entry.billingPeriodId ? (
                    <div>
                      <dt className="font-medium text-foreground">{t("student.paymentHistory.billingPeriod")}</dt>
                      <dd>{shortId(entry.billingPeriodId, "Period")}</dd>
                    </div>
                  ) : null}
                </dl>
                <Link
                  href={`/student/payments/awaiting-confirmation/${entry.orderId}`}
                  className="mt-2 inline-block text-sm font-medium text-foreground hover:underline"
                >
                  {t("student.paymentHistory.viewOrderStatus")}
                </Link>
                {open ? <OrderDetail orderId={entry.orderId} /> : null}
              </li>
            );
          })}
        </ul>
      )}
    </QueryStateBoundary>
  );
}
