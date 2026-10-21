"use client";

import { useState, type ReactNode } from "react";
import Link from "next/link";
import { CalendarDays, ChevronDown, Plus } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { formatCurrency, monthLong, toAmount } from "@/lib/api/klass-student";
import {
  paymentMethodLabel,
  useStaffPaymentHistory,
  type HistoryEntry,
  type ReceiptLine,
} from "@/lib/api/staff-payments";
import { formatDateTime } from "@/lib/format";
import { cn } from "@/lib/utils";
import { HistoryStatusBadge } from "./staff-status-badges";

function lineText(line: ReceiptLine): string {
  if (line.scope === "MONTHLY" && line.year && line.month) return `${line.year} ${monthLong(line.month)}`;
  return line.label;
}

const SOURCE_LABEL: Record<string, string> = {
  MANUAL: "Recorded by staff",
  SLIP: "Bank slip",
  GATEWAY: "Online payment",
  OTHER: "Other",
};

/**
 * "<First name>'s Payments" (Klass S1): one row per order with a line for
 * the selected course, server-derived status (RECEIVED / PENDING_SLIP /
 * PENDING_ONLINE / REJECTED - never a generic "Paid"), the received amount
 * and expandable receipt details. Reversal = the existing refund flow.
 */
export function PaymentHistoryPanel({
  studentId,
  studentName,
  courseId,
  courseTitle,
  canRecord,
  canRefund,
  onAddPayment,
}: {
  studentId: string;
  studentName: string;
  courseId: string;
  courseTitle: string;
  canRecord: boolean;
  canRefund: boolean;
  onAddPayment: () => void;
}) {
  const query = useStaffPaymentHistory(studentId, courseId);
  const firstName = studentName.trim().split(/\s+/)[0] || studentName;

  return (
    <section className="flex flex-col rounded-lg border border-border bg-card" aria-labelledby="payment-history-heading">
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-border px-4 py-3">
        <h2 id="payment-history-heading" className="text-base font-semibold text-foreground">
          {firstName}&apos;s Payments
        </h2>
        <Badge variant="secondary" data-testid="history-course-chip">
          {courseTitle}
        </Badge>
      </div>
      <div className="flex flex-col gap-3 p-4">
        {canRecord ? (
          <Button type="button" className="w-full" onClick={onAddPayment} data-testid="add-payment-open">
            <Plus aria-hidden="true" />
            Add Payment
          </Button>
        ) : null}
        <QueryStateBoundary
          query={query}
          loadingLabel="Loading payment history…"
          loginPath="/login"
          permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
          isEmpty={(data) => data.length === 0}
          emptyState={{
            title: "No payments for this course yet",
            description: canRecord
              ? `Nothing has been paid or submitted for ${courseTitle} yet. Use Add Payment to record money received.`
              : `Nothing has been paid or submitted for ${courseTitle} yet.`,
          }}
        >
          {(entries) => (
            <ul className="flex flex-col divide-y divide-border" aria-label="Payment history" data-testid="history-list">
              {entries.map((entry) => (
                <HistoryRow key={entry.orderId} entry={entry} courseId={courseId} canRefund={canRefund} />
              ))}
            </ul>
          )}
        </QueryStateBoundary>
      </div>
    </section>
  );
}

function HistoryRow({ entry, courseId, canRefund }: { entry: HistoryEntry; courseId: string; canRefund: boolean }) {
  const [expanded, setExpanded] = useState(false);
  const detailsId = `history-details-${entry.orderId}`;
  const ownLines = entry.lines.filter((line) => line.courseId === courseId);
  const otherCourses = [
    ...new Set(entry.lines.filter((line) => line.courseId !== courseId).map((line) => line.courseTitle ?? "another course")),
  ];
  const title = (ownLines.length > 0 ? ownLines : entry.lines).map(lineText).join(", ");
  const received = entry.receivedAmount !== null && entry.receivedAmount !== undefined;
  const receipt = entry.receipt;

  return (
    <li className="flex flex-col gap-2 py-3" data-testid="history-row" data-order-id={entry.orderId}>
      <div className="flex flex-wrap items-center gap-2">
        <CalendarDays className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
        <span className="min-w-0 flex-1 font-medium text-foreground" data-testid="history-months">
          {title}
        </span>
        <HistoryStatusBadge status={entry.status} />
        <span className="text-sm font-medium" data-testid="history-amount">
          {entry.status === "RECEIVED" && received
            ? formatCurrency(entry.receivedAmount, entry.currency)
            : `Order total ${formatCurrency(entry.orderTotal, entry.currency)}`}
        </span>
        <Button
          type="button"
          variant="ghost"
          size="icon-sm"
          aria-expanded={expanded}
          aria-controls={detailsId}
          aria-label={`${expanded ? "Hide" : "Show"} details for ${title}`}
          onClick={() => setExpanded((value) => !value)}
          data-testid="history-expand"
        >
          <ChevronDown aria-hidden="true" className={cn("transition-transform", expanded && "rotate-180")} />
        </Button>
      </div>
      {entry.status === "PENDING_SLIP" ? (
        <p className="text-xs text-muted-foreground">
          Pending slip –{" "}
          <Link href="/tenant-admin/payments/slip-review" className="font-medium text-foreground underline">
            review in Payment Slips
          </Link>
        </p>
      ) : null}
      {otherCourses.length > 0 ? (
        <p className="text-xs text-muted-foreground">Same payment also covers: {otherCourses.join(", ")}</p>
      ) : null}
      {expanded ? (
        <div id={detailsId} className="rounded-md bg-muted/40 p-3 text-sm" data-testid="history-details">
          <dl className="grid grid-cols-1 gap-2 sm:grid-cols-2">
            <Detail label="Source" value={SOURCE_LABEL[entry.source] ?? entry.source} />
            <Detail label="Order created" value={formatDateTime(entry.createdAt)} />
            {receipt ? (
              <>
                <Detail label="Method" value={paymentMethodLabel(receipt.method)} />
                <Detail label="Reference" value={receipt.referenceNumber ?? "—"} />
                <Detail label="Received on" value={receipt.receivedOn} />
                <Detail label="Recorded by" value={receipt.recordedByName ?? receipt.recordedBy} />
                <Detail label="Recorded at" value={formatDateTime(receipt.recordedAt)} />
                {receipt.notes ? <Detail label="Notes" value={receipt.notes} /> : null}
              </>
            ) : null}
            {toAmount(entry.staffAdjustmentAmount) > 0 ? (
              <Detail
                label="Discount"
                value={`${formatCurrency(entry.staffAdjustmentAmount, entry.currency)}${
                  receipt?.adjustmentReason ? ` – ${receipt.adjustmentReason}` : ""
                }`}
              />
            ) : null}
            {toAmount(entry.refundedAmount) > 0 ? (
              <Detail label="Refunded" value={formatCurrency(entry.refundedAmount, entry.currency)} />
            ) : null}
            <Detail label="Order total" value={formatCurrency(entry.orderTotal, entry.currency)} />
            {entry.paymentId ? (
              <Detail label="Payment ID" value={<span className="font-mono text-xs">{entry.paymentId}</span>} />
            ) : null}
          </dl>
          <ul className="mt-2 flex flex-col gap-0.5" aria-label="Order lines">
            {entry.lines.map((line) => (
              <li key={line.lineNo} className="flex justify-between gap-2 text-xs">
                <span>
                  {line.courseTitle ? `${line.courseTitle} – ` : ""}
                  {lineText(line)}
                </span>
                <span>{formatCurrency(line.lineTotal, entry.currency)}</span>
              </li>
            ))}
          </ul>
          {entry.status === "RECEIVED" && canRefund && entry.paymentId ? (
            <p className="mt-3 text-xs text-muted-foreground">
              <Link
                href="/tenant-admin/payments/refunds"
                className="font-medium text-foreground underline"
                data-testid="history-reverse"
              >
                Reverse (refund)
              </Link>{" "}
              – find payment <span className="font-mono">{entry.paymentId}</span> on the Refunds page. A refund adds a
              new record; this payment is never edited or deleted.
            </p>
          ) : null}
        </div>
      ) : null}
    </li>
  );
}

function Detail({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div className="flex flex-col">
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="break-words text-foreground">{value}</dd>
    </div>
  );
}
