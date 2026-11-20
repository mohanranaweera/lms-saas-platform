"use client";

import { formatMoney } from "@/lib/format";
import type { PaymentSlipResponse } from "@/lib/api/payment-slips";

export function money(value: number | string | null | undefined, currency: string): string {
  const amount = typeof value === "number" ? value : Number(value ?? 0);
  return formatMoney(Number.isFinite(amount) ? amount : 0, currency);
}

/**
 * What the approval would cover: the order's items (each with its course -
 * one order may span several courses), the fee/postage breakdown and, when
 * postage was selected, the delivery address snapshot to post to. Display
 * only, straight from the slip response; a legacy line-less order shows
 * nothing here.
 */
export function SlipOrderBreakdown({ slip }: { slip: PaymentSlipResponse }) {
  const lines = slip.orderLines ?? [];
  const postage = slip.orderPostage ?? null;
  const delivery = slip.orderDelivery ?? null;
  if (lines.length === 0 && postage === null && delivery === null) return null;
  const currency = slip.orderCurrency;
  return (
    <div className="flex flex-col gap-3 rounded-lg border border-border p-4" data-testid="slip-order-breakdown">
      <h3 className="text-sm font-medium text-foreground">Order items</h3>
      {lines.length > 0 ? (
        <ul className="flex flex-col divide-y divide-border text-sm" aria-label="Order items">
          {lines.map((line) => (
            <li
              key={line.lineNo}
              className="flex flex-col gap-0.5 py-2 sm:flex-row sm:items-start sm:justify-between sm:gap-4"
              data-testid="slip-order-line"
            >
              <div className="flex min-w-0 flex-col">
                <span className="font-medium text-foreground" data-testid="slip-order-line-course">
                  {line.courseTitle ?? (line.courseId ? `Course #${line.courseId.slice(0, 8)}` : "Course")}
                </span>
                <span className="text-xs text-muted-foreground">{line.label}</span>
              </div>
              <span className="shrink-0 text-foreground">{money(line.lineTotal, currency)}</span>
            </li>
          ))}
        </ul>
      ) : null}
      <dl className="grid grid-cols-[1fr_auto] gap-x-4 gap-y-1 border-t border-border pt-2 text-sm">
        {slip.orderSubtotal !== undefined && slip.orderSubtotal !== null ? (
          <>
            <dt className="text-muted-foreground">Course fees</dt>
            <dd className="text-right text-foreground">{money(slip.orderSubtotal, currency)}</dd>
          </>
        ) : null}
        {slip.orderFacilityFee !== undefined && slip.orderFacilityFee !== null ? (
          <>
            <dt className="text-muted-foreground">Facility fee</dt>
            <dd className="text-right text-foreground">{money(slip.orderFacilityFee, currency)}</dd>
          </>
        ) : null}
        {postage !== null ? (
          <>
            <dt className="text-muted-foreground">Postage</dt>
            <dd className="text-right text-foreground" data-testid="slip-order-postage">
              {money(postage, currency)}
            </dd>
          </>
        ) : null}
        {slip.orderDiscount !== undefined && slip.orderDiscount !== null && Number(slip.orderDiscount) > 0 ? (
          <>
            <dt className="text-muted-foreground">Discounts</dt>
            <dd className="text-right text-foreground">− {money(slip.orderDiscount, currency)}</dd>
          </>
        ) : null}
        <dt className="font-medium text-foreground">Total</dt>
        <dd className="text-right font-medium text-foreground">{money(slip.orderAmount, currency)}</dd>
      </dl>
      {delivery ? (
        <div className="flex flex-col gap-1 border-t border-border pt-2 text-sm" data-testid="slip-order-delivery">
          <h4 className="text-xs font-medium text-muted-foreground">Deliver postage to</h4>
          <address className="not-italic text-foreground">
            {delivery.recipientName ? <span className="block font-medium">{delivery.recipientName}</span> : null}
            {delivery.addressLine ? <span className="block">{delivery.addressLine}</span> : null}
            {delivery.city ? <span className="block">{delivery.city}</span> : null}
            {delivery.phone ? <span className="block text-muted-foreground">{delivery.phone}</span> : null}
          </address>
        </div>
      ) : null}
    </div>
  );
}
