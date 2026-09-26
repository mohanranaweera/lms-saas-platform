"use client";

import { useState, type ReactNode } from "react";
import Link from "next/link";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import { DateRangeFilter } from "@/components/finance/date-range-filter";
import { SignedAmount } from "@/components/finance/signed-amount";
import { formatMoney } from "@/lib/format";
import {
  useFinancePeriods,
  useFinanceSummary,
  type DateRangeParams,
  type PeriodRow,
} from "@/lib/api/finance";

/**
 * Tenant Admin Finance Overview (Wave 7, PAR-23-02). Income is ledger-derived
 * server-side (`GET /api/v1/finance/reports/summary`) — this screen offers no
 * way to enter or edit income. Expenses exclude voided records.
 */
export default function FinanceOverviewPage() {
  const [range, setRange] = useState<DateRangeParams>({});
  const summaryQuery = useFinanceSummary(range);
  const periodsQuery = useFinancePeriods(range);

  const periodColumns: DataTableColumn<PeriodRow>[] = [
    { key: "period", header: "Month", cell: (row) => row.period },
    { key: "incomeGross", header: "Income (gross)", cell: (row) => formatMoney(row.incomeGross) },
    { key: "refunds", header: "Refunds", cell: (row) => formatMoney(row.refunds) },
    { key: "incomeNet", header: "Income (net)", cell: (row) => formatMoney(row.incomeNet) },
    { key: "expenses", header: "Expenses", cell: (row) => formatMoney(row.expenses) },
    { key: "netResult", header: "Net result", cell: (row) => <SignedAmount value={row.netResult} /> },
  ];

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">Finance overview</h1>
        <p className="text-sm text-muted-foreground">
          Income comes from confirmed payments and refunds on the payment ledger. Expenses are
          recorded by your finance team.
        </p>
      </div>

      <DateRangeFilter idPrefix="finance-overview" value={range} onApply={setRange} />

      <QueryStateBoundary
        query={summaryQuery}
        loadingLabel="Loading finance summary…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
      >
        {(summary) => (
          <section aria-labelledby="finance-summary-heading" className="flex flex-col gap-3">
            <h2 id="finance-summary-heading" className="text-sm font-medium text-muted-foreground">
              {summary.from} to {summary.to} ({summary.timezone}, {summary.currency})
            </h2>
            <dl className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
              <Stat label="Income (gross)" value={formatMoney(summary.income.gross)}
                hint={`${summary.income.paymentCount} confirmed payment${summary.income.paymentCount === 1 ? "" : "s"}`} />
              <Stat label="Refunds" value={formatMoney(summary.income.refunds)}
                hint={`${summary.income.refundCount} refund${summary.income.refundCount === 1 ? "" : "s"}`} />
              <Stat label="Expenses" value={formatMoney(summary.expenses.total)}
                hint={`${summary.expenses.count} expense${summary.expenses.count === 1 ? "" : "s"}`} />
              <Stat label="Net result" value={<SignedAmount value={summary.netResult} />}
                hint="Net income minus expenses" />
            </dl>
            {summary.expenses.byCategory.length > 0 ? (
              <div className="rounded-lg border border-border p-4">
                <h3 className="mb-2 text-sm font-semibold text-foreground">Expenses by category</h3>
                <ul className="flex flex-col gap-1 text-sm">
                  {summary.expenses.byCategory.map((row) => (
                    <li key={row.categoryId} className="flex justify-between gap-4">
                      <span>{row.categoryName ?? "Unknown category"}</span>
                      <span className="font-medium">{formatMoney(row.total)}</span>
                    </li>
                  ))}
                </ul>
              </div>
            ) : null}
          </section>
        )}
      </QueryStateBoundary>

      <section aria-labelledby="finance-periods-heading" className="flex flex-col gap-3">
        <div className="flex items-center justify-between gap-4">
          <h2 id="finance-periods-heading" className="text-base font-semibold text-foreground">
            Monthly summary
          </h2>
          <Link href="/tenant-admin/finance/reports" className="text-sm underline underline-offset-4">
            Course and teacher revenue
          </Link>
        </div>
        <QueryStateBoundary
          query={periodsQuery}
          loadingLabel="Loading monthly summary…"
          loginPath="/login"
          permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
          isEmpty={(data) => data.rows.length === 0}
          emptyState={{ title: "No periods", description: "Choose a date range to see monthly totals." }}
        >
          {(data) => (
            <DataTable
              columns={periodColumns}
              rows={data.rows}
              rowKey={(row) => row.period}
              caption="Monthly income and expense summary"
              cardHeading={(row) => row.period}
            />
          )}
        </QueryStateBoundary>
      </section>
    </div>
  );
}

function Stat({ label, value, hint }: { label: string; value: ReactNode; hint: string }) {
  return (
    <div className="flex flex-col gap-1 rounded-lg border border-border p-4">
      <dt className="text-sm text-muted-foreground">{label}</dt>
      <dd className="text-lg font-semibold text-foreground">{value}</dd>
      <dd className="text-xs text-muted-foreground">{hint}</dd>
    </div>
  );
}
