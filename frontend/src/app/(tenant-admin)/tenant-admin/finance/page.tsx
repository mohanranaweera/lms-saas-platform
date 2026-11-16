"use client";

import { useState, type ReactNode } from "react";
import Link from "next/link";
import { ArrowRight, ChevronLeft, ChevronRight, Coins, Percent, Wallet } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import { Label } from "@/components/ui/label";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { FinanceHubFrame } from "@/components/finance/finance-tabs";
import { GroupedBarChart, type ChartDatum } from "@/components/finance/book/charts";
import { InfoNote, SELECT_CLASS, formatDate, useFinanceGates } from "@/components/finance/book/shared";
import { formatMoney } from "@/lib/format";
import { cn } from "@/lib/utils";
import {
  useStudentIncome,
  useStudentIncomeMonth,
  type IncomeWallet,
  type MethodBreakdown,
  type StudentIncome,
  type TeacherMonthRow,
} from "@/lib/api/finance-book";

const MONTHS = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"];
const TEACHER_PAGE = 10;

function currentYear(): number {
  return new Date().getFullYear();
}

function defaultMonth(year: number): string {
  const now = new Date();
  const month = year === now.getFullYear() ? now.getMonth() + 1 : 12;
  return `${year}-${String(month).padStart(2, "0")}`;
}

/**
 * FIN-01 Student Income tab (Klass parity, the Finance landing page).
 * Figures come from the payment ledger (confirmed payments and refunds),
 * never from the account book - the comparison with what the book has
 * posted is shown, not reconciled, here. The Wallet panel appears only when
 * the server returns one, i.e. the signed-in user owns a teacher wallet.
 */
export default function StudentIncomePage() {
  const gates = useFinanceGates();
  const [year, setYear] = useState(currentYear);
  const [month, setMonth] = useState(() => defaultMonth(currentYear()));
  const query = useStudentIncome(year);
  const years = Array.from({ length: 6 }, (_, i) => currentYear() - i);

  return (
    <FinanceHubFrame
      title="Student income"
      description="Confirmed student payments less refunds, from the payment ledger."
      actions={
        <div className="flex items-center gap-2">
          <Label htmlFor="income-year">Year</Label>
          <select
            id="income-year"
            className={`${SELECT_CLASS} w-28`}
            value={year}
            onChange={(e) => {
              const next = Number(e.target.value);
              setYear(next);
              setMonth(defaultMonth(next));
            }}
          >
            {years.map((y) => (
              <option key={y} value={y}>
                {y}
              </option>
            ))}
          </select>
        </div>
      }
    >
      <QueryStateBoundary query={query} loadingLabel="Loading student income…" loginPath="/login" permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}>
        {(data) => (
          <div className="flex flex-col gap-5">
            {data.wallet ? <WalletPanel wallet={data.wallet} /> : null}
            <Hero data={data} />
            <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
              <Card className="lg:col-span-2">
                <CardHeader>
                  <CardTitle className="text-base" role="heading" aria-level={3}>Income and expenses</CardTitle>
                </CardHeader>
                <CardContent>
                  <GroupedBarChart
                    data={data.months.map<ChartDatum>((m) => ({
                      key: m.month,
                      label: m.label,
                      values: { income: m.netIncome, expenses: m.expenses, commission: m.commission },
                    }))}
                    series={["income", "expenses", "commission"]}
                    currency={data.currency}
                    title={`Income, expenses and commission by month, ${data.year}`}
                    selectedKey={month}
                    onSelect={setMonth}
                  />
                </CardContent>
              </Card>
              <MethodsCard methods={data.methods} currency={data.currency} paymentCount={data.totals.paymentCount} average={data.totals.averagePayment} />
            </div>
            <MonthlySummary year={data.year} month={month} onMonth={setMonth} />
            <TeacherTable rows={data.teacherRows} currency={data.currency} />
            <BookComparison data={data} />
            <QuickLinks gates={gates} />
            {data.notes.length ? (
              <InfoNote testId="income-notes">
                <ul className="flex flex-col gap-1">
                  {data.notes.map((n) => (
                    <li key={n}>{n}</li>
                  ))}
                </ul>
              </InfoNote>
            ) : null}
          </div>
        )}
      </QueryStateBoundary>
    </FinanceHubFrame>
  );
}

function Hero({ data }: { data: StudentIncome }) {
  const t = data.totals;
  const c = data.currency;
  return (
    <div className="flex flex-col gap-4">
      <section aria-labelledby="income-hero" className="flex flex-col gap-2 rounded-xl bg-primary/5 p-5 ring-1 ring-primary/15">
        <h3 id="income-hero" className="text-sm text-muted-foreground">
          Student income
        </h3>
        <p className="text-3xl font-semibold tabular-nums" data-testid="income-net">
          {formatMoney(t.netIncome, c)}
        </p>
        <p className="text-xs text-muted-foreground">
          collected in {data.year} · {data.timezone}
        </p>
        <div className="flex flex-wrap gap-2 text-xs">
          <Badge variant="outline" className={t.netAfterExpenses < 0 ? "border-red-500 text-red-700 dark:text-red-300" : ""}>
            {formatMoney(t.netAfterExpenses, c)} after expenses
          </Badge>
          <Badge variant="outline">
            <Percent aria-hidden="true" />
            {formatMoney(t.instituteCommission, c)} institute commission
          </Badge>
          <span className="text-muted-foreground">Updated {new Date(data.generatedAt).toLocaleTimeString()}</span>
        </div>
      </section>
      <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
        <MiniStat label="Income" value={formatMoney(t.grossIncome, c)} hint={t.refunds > 0 ? `Student payments; ${formatMoney(t.refunds, c)} refunded` : `Student payments in ${data.year}`} icon={<Coins className="size-4" aria-hidden="true" />} />
        <MiniStat label="Teacher share" value={formatMoney(t.teacherShare, c)} hint="Income less institute commission" icon={<Wallet className="size-4" aria-hidden="true" />} />
        <MiniStat
          label="Institute commission"
          value={formatMoney(t.instituteCommission, c)}
          hint={t.unratedRevenue > 0 ? `${formatMoney(t.unratedRevenue, c)} has no teacher rate yet` : "The institute's share of income"}
          icon={<Percent className="size-4" aria-hidden="true" />}
        />
      </div>
    </div>
  );
}

function MiniStat({ label, value, hint, icon }: { label: string; value: string; hint: string; icon: ReactNode }) {
  return (
    <Card size="sm">
      <CardContent className="flex flex-col gap-1">
        <div className="flex items-center justify-between text-xs text-muted-foreground">
          <span>{label}</span>
          {icon}
        </div>
        <p className="text-2xl font-semibold tabular-nums">{value}</p>
        <p className="text-xs text-muted-foreground">{hint}</p>
      </CardContent>
    </Card>
  );
}

function MethodsCard({ methods, currency, paymentCount, average }: { methods: MethodBreakdown; currency: string; paymentCount: number; average: number }) {
  const online = Math.max(0, Math.min(100, methods.onlinePercent));
  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base" role="heading" aria-level={3}>How it was paid</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-3 text-sm">
        <div className="h-2 overflow-hidden rounded-full bg-primary/20" role="img" aria-label={`${online.toFixed(0)}% of takings came in online`}>
          <div className="h-full bg-primary" style={{ width: `${online}%` }} />
        </div>
        <dl className="grid grid-cols-[1fr_auto] gap-x-3 gap-y-1">
          <dt>Online ({methods.onlineCount})</dt>
          <dd className="text-right font-semibold tabular-nums">{formatMoney(methods.onlineTotal, currency)}</dd>
          <dt>Manual ({methods.manualCount})</dt>
          <dd className="text-right font-semibold tabular-nums">{formatMoney(methods.manualTotal, currency)}</dd>
        </dl>
        <p className="text-xs text-muted-foreground">{online.toFixed(0)}% of takings came in online.</p>
        {methods.byCode.length ? (
          <ul className="flex flex-col gap-1 border-t border-border pt-2 text-xs" aria-label="By payment method">
            {methods.byCode.map((m) => (
              <li key={m.code} className="flex justify-between gap-2">
                <span>
                  {m.label} ({m.count})
                </span>
                <span className="tabular-nums">{formatMoney(m.total, currency)}</span>
              </li>
            ))}
          </ul>
        ) : null}
        <dl className="grid grid-cols-[1fr_auto] gap-x-3 gap-y-1 border-t border-border pt-2">
          <dt className="text-muted-foreground">Payments</dt>
          <dd className="text-right font-semibold tabular-nums">{paymentCount}</dd>
          <dt className="text-muted-foreground">Average payment</dt>
          <dd className="text-right font-semibold tabular-nums">{formatMoney(average, currency)}</dd>
        </dl>
      </CardContent>
    </Card>
  );
}

function MonthlySummary({ year, month, onMonth }: { year: number; month: string; onMonth: (m: string) => void }) {
  const query = useStudentIncomeMonth(month);
  const index = Number(month.slice(5, 7)) - 1;
  const go = (i: number) => onMonth(`${year}-${String(i + 1).padStart(2, "0")}`);
  return (
    <Card>
      <CardHeader className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <CardTitle className="text-base" role="heading" aria-level={3}>Monthly summary</CardTitle>
          <p className="text-xs text-muted-foreground">Select a month on the chart above, or pick one here.</p>
        </div>
        <div className="flex items-center gap-1">
          <Button type="button" variant="ghost" size="icon-sm" aria-label="Previous month" disabled={index <= 0} onClick={() => go(index - 1)}>
            <ChevronLeft aria-hidden="true" />
          </Button>
          <label htmlFor="income-month" className="sr-only">
            Month
          </label>
          <select id="income-month" className={`${SELECT_CLASS} w-36`} value={index} onChange={(e) => go(Number(e.target.value))}>
            {MONTHS.map((name, i) => (
              <option key={name} value={i}>
                {name}
              </option>
            ))}
          </select>
          <Button type="button" variant="ghost" size="icon-sm" aria-label="Next month" disabled={index >= 11} onClick={() => go(index + 1)}>
            <ChevronRight aria-hidden="true" />
          </Button>
        </div>
      </CardHeader>
      <CardContent>
        <QueryStateBoundary
          query={query}
          loadingLabel="Loading month…"
          loginPath="/login"
          isEmpty={(d) => d.payments.length === 0}
          emptyState={{ title: `No payments recorded in ${MONTHS[index]} ${year}`, description: "Pick another month to see its payments." }}
        >
          {(d) => (
            <div className="flex flex-col gap-3" data-testid="month-summary">
              <dl className="grid grid-cols-2 gap-3 text-sm sm:grid-cols-4">
                {[
                  ["Income", d.totals.grossIncome],
                  ["Refunds", d.totals.refunds],
                  ["Net income", d.totals.netIncome],
                  ["Expenses", d.totals.expenses],
                ].map(([label, value]) => (
                  <div key={label as string} className="rounded-lg border border-border p-2">
                    <dt className="text-xs text-muted-foreground">{label}</dt>
                    <dd className="font-semibold tabular-nums">{formatMoney(value as number, d.currency)}</dd>
                  </div>
                ))}
              </dl>
              <DataTable
                columns={[
                  { key: "date", header: "Date", cell: (r) => formatDate(r.date) },
                  { key: "method", header: "Method", cell: (r) => r.method },
                  { key: "courses", header: "Courses", cell: (r) => r.courseCount },
                  {
                    key: "amount",
                    header: "Amount",
                    cell: (r) => (
                      <span className={cn("tabular-nums", r.refund && "text-red-700 dark:text-red-400")}>
                        {r.refund ? "Refund −" : ""}
                        {formatMoney(r.amount, r.currency)}
                      </span>
                    ),
                  },
                ]}
                rows={d.payments}
                rowKey={(r) => r.ledgerEntryId}
                caption={`Payments in ${MONTHS[index]} ${year}`}
                cardHeading={(r) => `${formatDate(r.date)} · ${formatMoney(r.amount, r.currency)}`}
              />
            </div>
          )}
        </QueryStateBoundary>
      </CardContent>
    </Card>
  );
}

function TeacherTable({ rows, currency }: { rows: TeacherMonthRow[]; currency: string }) {
  const [page, setPage] = useState(0);
  const pages = Math.max(1, Math.ceil(rows.length / TEACHER_PAGE));
  const visible = rows.slice(page * TEACHER_PAGE, page * TEACHER_PAGE + TEACHER_PAGE);
  const money = (v: number | null, missing: boolean) => (v === null ? (missing ? "No rate" : "—") : formatMoney(v, currency));
  const columns: DataTableColumn<TeacherMonthRow>[] = [
    { key: "teacher", header: "Teacher", cell: (r) => r.teacherName ?? "Unassigned" },
    { key: "month", header: "Month", cell: (r) => r.monthLabel },
    { key: "income", header: `Income (${currency})`, cell: (r) => formatMoney(r.income, currency) },
    { key: "institute", header: `For institute (${currency})`, cell: (r) => money(r.forInstitute, r.rateMissing) },
    { key: "teacherShare", header: `For teacher (${currency})`, cell: (r) => money(r.forTeacher, r.rateMissing) },
  ];
  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base" role="heading" aria-level={3}>Income summary</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        {rows.length === 0 ? (
          <p className="text-sm text-muted-foreground">No teacher income this year.</p>
        ) : (
          <>
            <DataTable
              columns={columns}
              rows={visible}
              rowKey={(r) => `${r.teacherId ?? "none"}-${r.month}`}
              caption="Income by teacher and month"
              cardHeading={(r) => `${r.teacherName ?? "Unassigned"} · ${r.monthLabel}`}
            />
            {pages > 1 ? (
              <div className="flex items-center justify-between">
                <Button type="button" variant="outline" size="sm" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
                  Previous
                </Button>
                <span className="text-xs text-muted-foreground">
                  Page {page + 1} of {pages}
                </span>
                <Button type="button" variant="outline" size="sm" disabled={page + 1 >= pages} onClick={() => setPage((p) => p + 1)}>
                  Next
                </Button>
              </div>
            ) : null}
          </>
        )}
      </CardContent>
    </Card>
  );
}

function BookComparison({ data }: { data: StudentIncome }) {
  const { postedStudentIncome, unpostedCount } = data.accountBook;
  return (
    <InfoNote testId="income-book-comparison">
      Posted to accounts this year: <strong className="tabular-nums">{formatMoney(postedStudentIncome, data.currency)}</strong>. The
      Budget dashboard counts only posted amounts, so it can differ from student income here
      {unpostedCount > 0 ? (
        <>
          {" "}
          — {unpostedCount} payment{unpostedCount === 1 ? " is" : "s are"} waiting under{" "}
          <Link href="/tenant-admin/finance/transactions" className="underline underline-offset-4">
            Transactions → Unposted
          </Link>
        </>
      ) : null}
      .
    </InfoNote>
  );
}

function QuickLinks({ gates }: { gates: ReturnType<typeof useFinanceGates> }) {
  const links = [
    gates.payments && { href: "/tenant-admin/payments", title: "Pending and recent payments", body: "Review bank slips and see every payment." },
    gates.teacherIncomeReport && { href: "/tenant-admin/reports/teacher-income", title: "Teacher income report", body: "Income per teacher for any date range." },
    gates.registrationsReport && { href: "/tenant-admin/reports/registrations", title: "Registration payments", body: "Registrations taken in a date range." },
  ].filter(Boolean) as { href: string; title: string; body: string }[];
  if (!links.length) return null;
  return (
    <ul className="grid grid-cols-1 gap-3 md:grid-cols-3" aria-label="Related pages">
      {links.map((l) => (
        <li key={l.href}>
          <Link href={l.href} className="flex h-full items-center justify-between gap-3 rounded-xl p-4 ring-1 ring-foreground/10 outline-none hover:bg-muted focus-visible:ring-3 focus-visible:ring-ring/50">
            <span>
              <span className="block text-sm font-medium">{l.title}</span>
              <span className="block text-xs text-muted-foreground">{l.body}</span>
            </span>
            <ArrowRight className="size-4 shrink-0" aria-hidden="true" />
          </Link>
        </li>
      ))}
    </ul>
  );
}

function WalletPanel({ wallet }: { wallet: IncomeWallet }) {
  const [tab, setTab] = useState<"in" | "out">("in");
  const list = tab === "in" ? wallet.payIns : wallet.payouts;
  return (
    <Card data-testid="wallet-panel">
      <CardHeader className="flex flex-row items-center justify-between gap-2">
        <CardTitle className="flex items-center gap-2 text-base" role="heading" aria-level={3}>
          <Wallet className="size-4" aria-hidden="true" />
          Your wallet
        </CardTitle>
        <p className="text-sm font-semibold text-primary tabular-nums">Balance: {formatMoney(wallet.balance, wallet.currency)}</p>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <div role="group" aria-label="Wallet movements" className="flex gap-1 border-b border-border">
          {(["in", "out"] as const).map((key) => (
            <button
              key={key}
              type="button"
              aria-pressed={tab === key}
              onClick={() => setTab(key)}
              className={cn(
                "border-b-2 px-3 py-1.5 text-sm font-medium outline-none focus-visible:ring-3 focus-visible:ring-ring/50",
                tab === key ? "border-primary text-foreground" : "border-transparent text-muted-foreground"
              )}
            >
              {key === "in" ? "Pay-ins" : "Payouts"}
            </button>
          ))}
        </div>
        {list.length === 0 ? (
          <p className="text-sm text-muted-foreground">{tab === "in" ? "No pay-ins to display." : "No payouts to display."}</p>
        ) : (
          <ul className="flex flex-col divide-y divide-border text-sm">
            {list.map((m) => (
              <li key={m.entryId} className="flex justify-between gap-3 py-1.5">
                <span>
                  {formatDate(m.recordedAt)}
                  {m.description ? <span className="text-muted-foreground"> · {m.description}</span> : null}
                </span>
                <span className="tabular-nums">{formatMoney(m.amount, wallet.currency)}</span>
              </li>
            ))}
          </ul>
        )}
        {wallet.unpaidSettlements > 0 ? (
          <p className="text-xs text-muted-foreground">
            {wallet.unpaidSettlements} settlement{wallet.unpaidSettlements === 1 ? "" : "s"} not yet paid.
          </p>
        ) : null}
      </CardContent>
    </Card>
  );
}
