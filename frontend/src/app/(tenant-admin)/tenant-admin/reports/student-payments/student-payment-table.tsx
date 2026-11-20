"use client";

import { ChevronLeft, ChevronRight, Undo2 } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { ErrorState } from "@/components/states/error-state";
import { LoadingState } from "@/components/states/loading-state";
import { isApiClientError } from "@/lib/api/error";
import { formatReportDecimal } from "@/lib/api/reports";
import {
  groupIdentity,
  rowGroupIdentities,
  studentPaymentDisplayMethodLabel,
  type StudentPaymentGroup,
  type StudentPaymentRow,
  type StudentPaymentRowsPage,
} from "@/lib/api/student-payment-report";
import { cn } from "@/lib/utils";
import { formatCodeAmount, formatInstant, formatInstantParts, formatLocalDate, isZeroAmount } from "./format";

/**
 * Paged RPT-02 detail rows. Rows and group subtotals are rendered exactly as
 * the server returned them (subtotals cover the whole run, not just this
 * page); nothing is summed client-side. Table on `md`+, cards below.
 */

type Item =
  | { kind: "group"; id: string; group: StudentPaymentGroup | undefined; depth: number }
  | { kind: "row"; id: string; row: StudentPaymentRow; depth: number };

function buildItems(page: StudentPaymentRowsPage): Item[] {
  const groups = new Map(
    page.groups.map((group) => [groupIdentity(group.currency, group.level, group.parentKey, group.key), group])
  );
  const items: Item[] = [];
  let previous: string[] = [];
  for (const row of page.rows) {
    const ids = rowGroupIdentities(row, page.groupBy);
    let changed = false;
    ids.forEach((id, depth) => {
      if (changed || previous[depth] !== id) {
        changed = true;
        items.push({ kind: "group", id: `g-${row.rowNo}-${id}`, group: groups.get(id), depth });
      }
    });
    previous = ids;
    items.push({ kind: "row", id: `r-${row.rowNo}`, row, depth: ids.length });
  }
  return items;
}

const COLUMN_COUNT = 8;

export function StudentPaymentTable({
  rowsPage,
  currencies,
  timezone,
  loading,
  error,
  onRetry,
  onPageChange,
}: {
  rowsPage: StudentPaymentRowsPage | null;
  currencies: string[];
  timezone: string;
  loading: boolean;
  error: unknown;
  onRetry: () => void;
  onPageChange: (page: number) => void;
}) {
  const rateLimited = isApiClientError(error) && error.status === 429;
  const errorMessage = rateLimited
    ? "Too many requests — wait a moment, then try again."
    : isApiClientError(error)
      ? error.status === 403
        ? "You don't have permission to view these rows."
        : error.status === 503
          ? "Reports are temporarily unavailable. Please try again shortly."
          : error.message
      : "The rows could not be loaded. Please try again.";

  if (!rowsPage) {
    if (error) return <ErrorState message={errorMessage} onRetry={onRetry} />;
    return <LoadingState label="Loading payments…" />;
  }

  // One currency: amounts are plain numbers under "Amount (LKR)", as in the reference.
  const singleCurrency = currencies.length === 1 ? currencies[0] : null;
  const amountText = (amount: string, currency: string) =>
    singleCurrency ? formatReportDecimal(amount) : formatCodeAmount(amount, currency);
  const items = buildItems(rowsPage);
  const first = rowsPage.rows[0]?.rowNo ?? 0;
  const last = rowsPage.rows[rowsPage.rows.length - 1]?.rowNo ?? 0;
  const totalPages = Math.max(rowsPage.totalPages, 1);

  return (
    <section aria-labelledby="payments-heading" aria-busy={loading} className="flex flex-col gap-3" data-testid="payments-table">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h3 id="payments-heading" className="text-sm font-semibold">
          Payments
        </h3>
        <p className="text-xs text-muted-foreground" data-testid="rows-range">
          {loading ? "Loading… " : ""}
          Showing {first}–{last} of {rowsPage.totalRows}
        </p>
      </div>

      {error ? (
        <div
          role="alert"
          className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-destructive/30 bg-destructive/5 px-3 py-2 text-sm text-destructive"
          data-testid="rows-error"
        >
          <span>{errorMessage}</span>
          <Button type="button" size="sm" variant="outline" onClick={onRetry}>
            Try again
          </Button>
        </div>
      ) : null}

      <div className={cn("hidden overflow-x-auto rounded-lg border border-border md:block", loading && "opacity-60")}>
        <table className="w-full min-w-[65rem] text-sm">
          <caption className="sr-only">
            Student payments, page {rowsPage.page + 1} of {totalPages}
          </caption>
          <thead className="border-b border-border bg-muted/50 text-left text-xs text-muted-foreground">
            <tr>
              <th scope="col" className="px-3 py-2 font-medium">#</th>
              <th scope="col" className="px-3 py-2 font-medium whitespace-nowrap">Index no.</th>
              <th scope="col" className="px-3 py-2 font-medium">Student</th>
              <th scope="col" className="px-3 py-2 font-medium">Course</th>
              <th scope="col" className="px-3 py-2 font-medium">Reason</th>
              <th scope="col" className="px-3 py-2 font-medium whitespace-nowrap">Payment method</th>
              <th scope="col" className="px-3 py-2 font-medium">Date</th>
              <th scope="col" className="px-3 py-2 text-right font-medium whitespace-nowrap">
                {singleCurrency ? `Amount (${singleCurrency})` : "Amount"}
              </th>
            </tr>
          </thead>
          <tbody>
            {items.map((item) =>
              item.kind === "group" ? (
                <tr key={item.id} className="border-b border-border bg-muted/30" data-testid="group-row">
                  <th scope="colgroup" colSpan={COLUMN_COUNT} className={cn("px-3 py-2 text-left font-normal", item.depth > 0 && "pl-8")}>
                    <GroupHeader group={item.group} singleCurrency={singleCurrency} />
                  </th>
                </tr>
              ) : (
                <tr key={item.id} className="border-b border-border last:border-0 align-top" data-testid="payment-row">
                  <td className={cn("px-3 py-2 tabular-nums text-muted-foreground", item.depth === 1 && "pl-6", item.depth === 2 && "pl-10")}>
                    {item.row.rowNo}
                  </td>
                  <td className="px-3 py-2 tabular-nums whitespace-nowrap" data-testid="index-number">{item.row.indexNumber ?? "—"}</td>
                  <td className="w-1/4 px-3 py-2">{item.row.studentName}</td>
                  <td className="w-1/4 px-3 py-2">{item.row.courseName}</td>
                  <td className={cn("px-3 py-2", /^\d{4}-\d{2}$/.test(item.row.reason) && "whitespace-nowrap")}>{item.row.reason}</td>
                  <td className="px-3 py-2 whitespace-nowrap">{studentPaymentDisplayMethodLabel(item.row.method, item.row.manual)}</td>
                  <td className="px-3 py-2">
                    <DateCell row={item.row} timezone={timezone} />
                  </td>
                  <td className="px-3 py-2 text-right whitespace-nowrap">
                    <AmountCell row={item.row} amountText={amountText} />
                  </td>
                </tr>
              )
            )}
          </tbody>
        </table>
      </div>

      <ul className={cn("flex flex-col gap-2 md:hidden", loading && "opacity-60")} aria-label="Student payments">
        {items.map((item) =>
          item.kind === "group" ? (
            <li
              key={item.id}
              className={cn("rounded-md bg-muted/50 px-3 py-2 text-sm", item.depth > 0 && "ml-3")}
              data-testid="group-card"
            >
              <GroupHeader group={item.group} singleCurrency={singleCurrency} />
            </li>
          ) : (
            <li
              key={item.id}
              className={cn("rounded-lg border border-border p-3 text-sm", item.depth > 0 && "ml-3")}
              data-testid="payment-card"
            >
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0">
                  <p className="font-medium">
                    <span className="text-muted-foreground">#{item.row.rowNo}</span> {item.row.studentName}
                  </p>
                  <p className="text-xs text-muted-foreground">Index no. {item.row.indexNumber ?? "—"}</p>
                </div>
                <AmountCell row={item.row} amountText={amountText} />
              </div>
              <dl className="mt-2 grid grid-cols-[auto_1fr] gap-x-3 gap-y-0.5 text-xs">
                <dt className="text-muted-foreground">Course</dt>
                <dd>{item.row.courseName}</dd>
                <dt className="text-muted-foreground">Reason</dt>
                <dd>{item.row.reason}</dd>
                <dt className="text-muted-foreground">Method</dt>
                <dd>{studentPaymentDisplayMethodLabel(item.row.method, item.row.manual)}</dd>
                <dt className="text-muted-foreground">Date</dt>
                <dd>
                  <DateCell row={item.row} timezone={timezone} />
                </dd>
              </dl>
            </li>
          )
        )}
      </ul>

      <nav aria-label="Payments pages" className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-sm text-muted-foreground" data-testid="page-indicator">
          Page {rowsPage.page + 1} of {totalPages}
        </p>
        <div className="flex gap-2">
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={rowsPage.page <= 0 || loading}
            onClick={() => onPageChange(rowsPage.page - 1)}
          >
            <ChevronLeft aria-hidden="true" />
            Previous
          </Button>
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={rowsPage.page + 1 >= totalPages || loading}
            onClick={() => onPageChange(rowsPage.page + 1)}
          >
            Next
            <ChevronRight aria-hidden="true" />
          </Button>
        </div>
      </nav>
    </section>
  );
}

function GroupHeader({ group, singleCurrency }: { group: StudentPaymentGroup | undefined; singleCurrency: string | null }) {
  if (!group) return <span className="text-muted-foreground">Group</span>;
  const money = (amount: string) => (singleCurrency ? formatReportDecimal(amount) : formatCodeAmount(amount, group.currency));
  const isStudent = group.level === "STUDENT";
  return (
    <span className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1" data-testid={isStudent ? "student-group" : "course-group"}>
      <span className="font-semibold">
        {isStudent ? "Student: " : "Course: "}
        {group.label}
        {isStudent && group.indexNumber ? (
          <span className="font-normal text-muted-foreground"> · Index no. {group.indexNumber}</span>
        ) : null}
      </span>
      <span className="text-xs text-muted-foreground" data-testid="group-subtotal">
        {group.receiptCount} {group.receiptCount === 1 ? "payment" : "payments"} · Subtotal{" "}
        <span className="font-semibold text-foreground tabular-nums">
          {singleCurrency ? `${group.currency} ` : ""}
          {money(group.coursePayment)}
        </span>
        {!isZeroAmount(group.serviceFee) ? ` + ${money(group.serviceFee)} fee` : ""}
        {!isZeroAmount(group.postage) ? ` + ${money(group.postage)} postage` : ""}
      </span>
    </span>
  );
}

function DateCell({ row, timezone }: { row: StudentPaymentRow; timezone: string }) {
  // Date and time are separate non-breaking lines, so a value never wraps mid-way.
  const parts = row.dateOnly ? { date: formatLocalDate(row.paymentDate), time: null } : formatInstantParts(row.paymentDate, timezone);
  return (
    <span className="flex flex-col">
      <span className="tabular-nums whitespace-nowrap" data-testid="payment-date">{parts.date}</span>
      {parts.time ? (
        <span className="tabular-nums whitespace-nowrap" data-testid="payment-time">{parts.time}</span>
      ) : null}
      {row.manual && row.recordedAt ? (
        <span className="text-xs whitespace-nowrap text-muted-foreground">recorded {formatInstant(row.recordedAt, timezone)}</span>
      ) : null}
    </span>
  );
}

function signedPart(amount: string, amountText: (amount: string) => string, label: string): string {
  const text = amountText(amount);
  return text.startsWith("-") ? `− ${text.slice(1)} ${label}` : `+ ${text} ${label}`;
}

function AmountCell({ row, amountText }: { row: StudentPaymentRow; amountText: (amount: string, currency: string) => string }) {
  const format = (amount: string) => amountText(amount, row.currency);
  return (
    <span className="flex flex-col items-end gap-0.5 text-right" data-testid="amount-cell">
      {row.refund ? (
        <Badge variant="destructive" className="gap-1">
          <Undo2 aria-hidden="true" />
          Refund
        </Badge>
      ) : null}
      <span className={cn("font-medium tabular-nums", row.refund && "text-destructive")}>{format(row.amount)}</span>
      {!isZeroAmount(row.serviceFee) ? (
        <span className="text-xs text-muted-foreground tabular-nums">{signedPart(row.serviceFee, format, "fee")}</span>
      ) : null}
      {!isZeroAmount(row.postage) ? (
        <span className="text-xs text-muted-foreground tabular-nums">{signedPart(row.postage, format, "postage")}</span>
      ) : null}
    </span>
  );
}

