"use client";

import Link from "next/link";
import { AlertCircle } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from "@/components/ui/sheet";
import { LoadingState } from "@/components/states/loading-state";
import {
  PAYMENT_METHOD_LABEL,
  PaymentOperationalStateBadge,
  PaymentStatusBadge,
  SlipStatusBadge,
} from "@/components/payments/status-badges";
import { formatDateTime, formatMoney } from "@/lib/format";
import { isApiClientError } from "@/lib/api/error";
import { useAdminOrderDetail, type AdminOrderDetail } from "@/lib/api/admin-orders";
import type { PaymentStatus } from "@/lib/api/payments";

/**
 * PAY-HUB-01 shared "complete order" drawer (All Orders and Refunds tabs):
 * every product, the fee / postage / discount breakdown, the delivery address
 * when postage was chosen, and every payment attempt and slip. Read-only and
 * straight from `GET /orders/{id}/admin-detail` - the backend decides access
 * (403/404 are shown here, never worked around).
 */
export function OrderDetailsDrawer({ orderId, onClose }: { orderId: string | null; onClose: () => void }) {
  const query = useAdminOrderDetail(orderId);
  const data = query.data;

  return (
    <Sheet open={!!orderId} onOpenChange={(next) => (next ? undefined : onClose())}>
      <SheetContent side="right" className="w-full overflow-y-auto sm:max-w-xl" data-testid="order-details-drawer">
        <SheetHeader className="border-b border-border">
          <SheetDescription>Order details</SheetDescription>
          <SheetTitle>{data?.displayOrderNumber ?? "Order"}</SheetTitle>
        </SheetHeader>
        <div className="flex flex-col gap-4 px-4 pb-6">
          {query.isPending ? (
            <LoadingState label="Loading order…" />
          ) : query.isError ? (
            <Alert variant="destructive">
              <AlertCircle aria-hidden="true" />
              <AlertDescription>
                {isApiClientError(query.error) && query.error.status === 404
                  ? "This order was not found in your institute."
                  : isApiClientError(query.error) && query.error.status === 403
                    ? "You don't have permission to view order details."
                    : "Order details could not be loaded."}
              </AlertDescription>
            </Alert>
          ) : data ? (
            <OrderDetailsBody order={data} />
          ) : null}
        </div>
      </SheetContent>
    </Sheet>
  );
}

function OrderDetailsBody({ order }: { order: AdminOrderDetail }) {
  const money = (value: number | null | undefined) => formatMoney(Number(value ?? 0), order.currency);
  return (
    <>
      <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-2 rounded-lg border border-border p-3 text-sm">
        <dt className="text-muted-foreground">Order date</dt>
        <dd>{formatDateTime(order.createdAt)}</dd>
        <dt className="text-muted-foreground">Student</dt>
        <dd>
          {order.student.studentId ? (
            <Link
              href={`/tenant-admin/students/${encodeURIComponent(order.student.studentId)}`}
              className="font-medium text-foreground hover:underline"
            >
              {order.student.name ?? "Student"}
            </Link>
          ) : (
            (order.student.name ?? "—")
          )}
          {order.student.mobile || order.student.email ? (
            <span className="block text-xs text-muted-foreground">
              {[order.student.mobile, order.student.email].filter(Boolean).join(" · ")}
            </span>
          ) : null}
        </dd>
        <dt className="text-muted-foreground">Payment status</dt>
        <dd>{order.operationalState ? <PaymentOperationalStateBadge state={order.operationalState} /> : "—"}</dd>
      </dl>

      <section aria-labelledby="order-products-heading" className="flex flex-col gap-2">
        <h3 id="order-products-heading" className="text-sm font-semibold text-foreground">
          Products ({order.lines.length})
        </h3>
        {order.lines.length === 0 ? (
          <p className="rounded-lg border border-border p-3 text-sm text-muted-foreground">
            Legacy single-product order; no line snapshot available.
          </p>
        ) : (
          <ul className="flex flex-col divide-y divide-border rounded-lg border border-border" data-testid="order-lines">
            {order.lines.map((line) => (
              <li key={line.lineNo} className="flex flex-col gap-1 p-3 text-sm">
                <div className="flex items-start justify-between gap-3">
                  <span className="font-medium text-foreground">{line.courseTitle ?? "Course"}</span>
                  <span className="shrink-0 font-medium">{money(line.lineTotal)}</span>
                </div>
                <span className="text-xs text-muted-foreground">{line.label}</span>
                <span className="text-xs text-muted-foreground">
                  Unit {money(line.unitAmount)} · Facility fee {money(line.facilityFee)}
                  {Number(line.discountAmount) > 0 ? ` · Discount −${money(line.discountAmount)}` : ""}
                </span>
              </li>
            ))}
          </ul>
        )}
      </section>

      <dl
        className="grid grid-cols-[1fr_auto] gap-x-4 gap-y-1 rounded-lg border border-border p-3 text-sm"
        data-testid="order-totals"
      >
        {order.subtotal !== null ? (
          <>
            <dt className="text-muted-foreground">Course fees</dt>
            <dd className="text-right">{money(order.subtotal)}</dd>
          </>
        ) : null}
        {order.facilityFee !== null ? (
          <>
            <dt className="text-muted-foreground">Facility fee</dt>
            <dd className="text-right">{money(order.facilityFee)}</dd>
          </>
        ) : null}
        {order.postage !== null ? (
          <>
            <dt className="text-muted-foreground">Postage</dt>
            <dd className="text-right" data-testid="order-postage">
              {money(order.postage)}
            </dd>
          </>
        ) : null}
        {order.discount !== null && Number(order.discount) > 0 ? (
          <>
            <dt className="text-muted-foreground">Discounts</dt>
            <dd className="text-right">− {money(order.discount)}</dd>
          </>
        ) : null}
        <dt className="border-t border-border pt-1 font-semibold">Total</dt>
        <dd className="border-t border-border pt-1 text-right font-semibold">{money(order.total)}</dd>
        <dt className="text-muted-foreground">Refunded</dt>
        <dd className="text-right">{money(order.refunded)}</dd>
        <dt className="text-muted-foreground">Remaining</dt>
        <dd className="text-right">{money(order.remaining)}</dd>
      </dl>

      {order.delivery ? (
        <section className="flex flex-col gap-1 rounded-lg border border-border p-3 text-sm" data-testid="order-delivery">
          <h3 className="text-xs font-medium text-muted-foreground">Deliver postage to</h3>
          <address className="not-italic">
            {order.delivery.recipientName ? <span className="block font-medium">{order.delivery.recipientName}</span> : null}
            {order.delivery.addressLine ? <span className="block">{order.delivery.addressLine}</span> : null}
            {order.delivery.city ? <span className="block">{order.delivery.city}</span> : null}
            {order.delivery.phone ? <span className="block text-muted-foreground">{order.delivery.phone}</span> : null}
          </address>
        </section>
      ) : null}

      <section aria-labelledby="order-payments-heading" className="flex flex-col gap-2">
        <h3 id="order-payments-heading" className="text-sm font-semibold text-foreground">
          Payments
        </h3>
        {order.payments.length === 0 ? (
          <p className="text-sm text-muted-foreground">No payment has been recorded for this order.</p>
        ) : (
          <ul className="flex flex-col gap-2">
            {order.payments.map((payment) => (
              <li key={payment.paymentId} className="flex flex-col gap-1 rounded-lg border border-border p-3 text-sm">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <span className="font-medium">{payment.method ? PAYMENT_METHOD_LABEL[payment.method] : "Payment"}</span>
                  <PaymentStatusBadge status={payment.status as PaymentStatus} />
                </div>
                <span className="text-xs text-muted-foreground">
                  {money(payment.amount)} · {formatDateTime(payment.createdAt)}
                  {payment.reference ? ` · Ref ${payment.reference}` : ""}
                </span>
              </li>
            ))}
          </ul>
        )}
        {order.slips.length > 0 ? (
          <ul className="flex flex-col gap-2" aria-label="Payment slips">
            {order.slips.map((slip) => (
              <li
                key={slip.slipId}
                className="flex flex-wrap items-center justify-between gap-2 rounded-lg border border-dashed border-border p-3 text-sm"
              >
                <Link
                  href={`/tenant-admin/payments/slip-review/${encodeURIComponent(slip.slipId)}`}
                  className="font-medium text-foreground hover:underline"
                >
                  Slip {slip.referenceNumber}
                </Link>
                <span className="flex items-center gap-2 text-xs text-muted-foreground">
                  {formatDateTime(slip.submittedAt)}
                  <SlipStatusBadge status={slip.status} />
                </span>
              </li>
            ))}
          </ul>
        ) : null}
      </section>
    </>
  );
}
