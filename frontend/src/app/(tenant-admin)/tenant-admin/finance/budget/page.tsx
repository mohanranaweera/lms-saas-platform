"use client";

import { useState, type ReactNode } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { DateInput } from "@/components/ui/date-input";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { FinanceHubFrame } from "@/components/finance/finance-tabs";
import { CategoryBars, TrendLineChart, type ChartDatum } from "@/components/finance/book/charts";
import { InfoNote, formatDate } from "@/components/finance/book/shared";
import { formatMoney } from "@/lib/format";
import { cn } from "@/lib/utils";
import { useBudgetDashboard, type BudgetDashboard, type CategoryTotal, type TrendPoint } from "@/lib/api/finance-book";

function trendLabel(point: TrendPoint, granularity: BudgetDashboard["trendGranularity"]): string {
  const [y, m, d] = point.period.split("-").map(Number);
  if (!y || !m) return point.period;
  if (granularity === "DAY" && d) return `${String(m).padStart(2, "0")}/${String(d).padStart(2, "0")}`;
  return new Intl.DateTimeFormat("en-US", { month: "short", year: "numeric", timeZone: "UTC" }).format(new Date(Date.UTC(y, m - 1, 1)));
}

/**
 * FIN-01 Budget Dashboard. Reads the account book only (posted rows):
 * transfers and adjustments are never income or expense. Student Income
 * reads the payment ledger instead, so the two tabs can differ (unposted
 * payments, payments before the book's cutover) - the page says so.
 * Every figure is the server's; the page only formats.
 */
export default function FinanceBudgetPage() {
  const [draft, setDraft] = useState({ from: "", to: "" });
  const [range, setRange] = useState<{ from?: string; to?: string }>({});
  const [rangeError, setRangeError] = useState<string | null>(null);
  const query = useBudgetDashboard(range);

  return (
    <FinanceHubFrame title="Budget dashboard" description="Balances and activity from the account book.">
      <form
        className="flex flex-col gap-2 sm:flex-row sm:items-end sm:gap-3"
        noValidate
        onSubmit={(event) => {
          event.preventDefault();
          if (draft.from && draft.to && draft.from > draft.to) {
            setRangeError("The start date must be on or before the end date.");
            return;
          }
          setRangeError(null);
          setRange({ from: draft.from || undefined, to: draft.to || undefined });
        }}
      >
        <DateInput
          idPrefix="budget"
          fromLabel="Start date"
          toLabel="End date"
          fromValue={draft.from}
          toValue={draft.to}
          onFromChange={(from) => setDraft((d) => ({ ...d, from }))}
          onToChange={(to) => setDraft((d) => ({ ...d, to }))}
          invalid={!!rangeError}
          errorId="budget-range-error"
        />
        <Button type="submit" variant="outline">
          Apply
        </Button>
      </form>
      {rangeError ? (
        <p id="budget-range-error" role="alert" className="text-xs text-destructive">
          {rangeError}
        </p>
      ) : null}

      <QueryStateBoundary query={query} loadingLabel="Loading budget dashboard…" loginPath="/login" permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}>
        {(data) => <BudgetBody data={data} />}
      </QueryStateBoundary>
    </FinanceHubFrame>
  );
}

function BudgetBody({ data }: { data: BudgetDashboard }) {
  const c = data.currency;
  const trend: ChartDatum[] = data.cashFlowTrend.map((p) => ({
    key: p.period,
    label: trendLabel(p, data.trendGranularity),
    values: { income: p.income, expenses: p.expenses, net: p.net },
  }));
  const rows = (list: CategoryTotal[]) =>
    list.map((r) => ({ key: r.categoryId ?? `none-${r.label}`, label: r.label, total: r.total, count: r.count }));

  return (
    <div className="flex flex-col gap-5">
      <p className="text-sm text-muted-foreground" data-testid="budget-period">
        {formatDate(data.from)} to {formatDate(data.to)} ({data.timezone}, {c})
      </p>
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <Stat label="Total balance" value={formatMoney(data.totalBalance, c)} hint="Across all accounts, now" tone="primary" testId="stat-total-balance" />
        <Stat
          label="Income for period"
          value={formatMoney(data.incomeForPeriod, c)}
          hint={data.refundsForPeriod > 0 ? `${formatMoney(data.grossIncomeForPeriod, c)} less ${formatMoney(data.refundsForPeriod, c)} refunds` : `${data.incomeCount} posting${data.incomeCount === 1 ? "" : "s"}`}
          tone="positive"
          testId="stat-income"
        />
        <Stat label="Expenses for period" value={formatMoney(data.expensesForPeriod, c)} hint={`${data.expenseCount} posting${data.expenseCount === 1 ? "" : "s"}`} tone="negative" testId="stat-expenses" />
        <Stat
          label="Net activity"
          value={formatMoney(data.netActivity, c)}
          hint="Income − expenses, selected period"
          tone={data.netActivity < 0 ? "negative" : "positive"}
          testId="stat-net"
        />
      </div>
      <p className="text-xs text-muted-foreground">
        Not counted as income or expenses: transfers {formatMoney(data.transfersForPeriod, c)} · adjustments {formatMoney(data.adjustmentsForPeriod, c)}.
      </p>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <Panel title="Income by category">
          {data.incomeByCategory.length ? <CategoryBars rows={rows(data.incomeByCategory)} currency={c} variant="income" label="Income by category" /> : <NotEnough />}
        </Panel>
        <Panel title="Expenses by category">
          {data.expensesByCategory.length ? <CategoryBars rows={rows(data.expensesByCategory)} currency={c} variant="expenses" label="Expenses by category" /> : <NotEnough />}
        </Panel>
      </div>

      <Panel title="Account balances overview">
        {data.accountBalances.length ? (
          <ul className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3" aria-label="Account balances">
            {data.accountBalances.map((a) => (
              <li key={a.accountId} className="rounded-lg border border-border p-3">
                <p className="font-medium">{a.name}</p>
                <p className="text-xs text-muted-foreground">Account balance</p>
                <p className="text-lg font-semibold tabular-nums">{formatMoney(a.balance, c)}</p>
              </li>
            ))}
          </ul>
        ) : (
          <p className="text-sm text-muted-foreground">No accounts yet. Create one on the Accounts tab.</p>
        )}
      </Panel>

      <Panel title="Cash flow trend">
        {trend.length ? <TrendLineChart data={trend} currency={c} title="Cash flow trend" /> : <NotEnough />}
      </Panel>

      {data.notes.length ? (
        <InfoNote testId="budget-notes">
          <ul className="flex flex-col gap-1">
            {data.notes.map((note) => (
              <li key={note}>{note}</li>
            ))}
          </ul>
        </InfoNote>
      ) : null}
    </div>
  );
}

function NotEnough() {
  return <p className="py-8 text-center text-sm text-muted-foreground">Not enough data for this period.</p>;
}

function Panel({ title, children }: { title: string; children: ReactNode }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base" role="heading" aria-level={3}>{title}</CardTitle>
      </CardHeader>
      <CardContent>{children}</CardContent>
    </Card>
  );
}

function Stat({
  label,
  value,
  hint,
  tone,
  testId,
}: {
  label: string;
  value: string;
  hint: string;
  tone: "primary" | "positive" | "negative";
  testId: string;
}) {
  return (
    <Card size="sm" data-testid={testId}>
      <CardContent className="flex flex-col gap-1">
        <p className="text-xs font-medium tracking-wide text-muted-foreground uppercase">{label}</p>
        <p
          className={cn(
            "text-2xl font-semibold tabular-nums",
            tone === "primary" && "text-primary",
            tone === "positive" && "text-green-700 dark:text-green-400",
            tone === "negative" && "text-red-700 dark:text-red-400"
          )}
        >
          {value}
        </p>
        <p className="text-xs text-muted-foreground">{hint}</p>
      </CardContent>
    </Card>
  );
}
