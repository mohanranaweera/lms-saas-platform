"use client";

import { useId, useRef, useState, type KeyboardEvent } from "react";
import { BarChart3, Table2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { formatMoney } from "@/lib/format";
import { cn } from "@/lib/utils";

/**
 * FIN-01 inline-SVG charts (no chart library in this project). Shared rules:
 * one palette (income = primary blue solid, expenses = light blue hatched,
 * commission = amber dotted; net = slate dashed), every series has a texture
 * or dash pattern and a text legend (never colour alone), every value is
 * reachable by keyboard focus / hover and in a table view, and the SVG scales
 * through its viewBox.
 */

export type SeriesKey = "income" | "expenses" | "commission" | "net";

interface SeriesStyle {
  key: SeriesKey;
  label: string;
}

const SERIES_LABEL: Record<SeriesKey, string> = {
  income: "Income",
  expenses: "Expenses",
  commission: "Commission",
  net: "Net",
};

function niceStep(span: number): number {
  if (span <= 0) return 1;
  const raw = span / 4;
  const magnitude = 10 ** Math.floor(Math.log10(raw));
  const normalized = raw / magnitude;
  const nice = normalized <= 1 ? 1 : normalized <= 2 ? 2 : normalized <= 5 ? 5 : 10;
  return nice * magnitude;
}

function scale(values: number[]) {
  const max = Math.max(0, ...values);
  const min = Math.min(0, ...values);
  const step = niceStep(max - min || 1);
  const top = Math.max(step, Math.ceil(max / step) * step);
  const bottom = Math.floor(min / step) * step;
  const ticks: number[] = [];
  for (let t = bottom; t <= top + step / 2; t += step) ticks.push(Math.round(t * 100) / 100);
  return { top, bottom, ticks };
}

function compact(value: number): string {
  const abs = Math.abs(value);
  const sign = value < 0 ? "−" : "";
  if (abs >= 1_000_000) return `${sign}${(abs / 1_000_000).toFixed(1)}M`;
  if (abs >= 10_000) return `${sign}${Math.round(abs / 1000)}k`;
  if (abs >= 1000) return `${sign}${(abs / 1000).toFixed(1)}k`;
  return `${sign}${abs}`;
}

function PatternDefs({ prefix }: { prefix: string }) {
  return (
    <defs>
      <pattern id={`${prefix}-hatch`} width="6" height="6" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
        <rect width="6" height="6" className="fill-sky-300 dark:fill-sky-700" />
        <line x1="0" y1="0" x2="0" y2="6" className="stroke-white/80" strokeWidth="2" />
      </pattern>
      <pattern id={`${prefix}-dots`} width="6" height="6" patternUnits="userSpaceOnUse">
        <rect width="6" height="6" className="fill-amber-400 dark:fill-amber-500" />
        <circle cx="3" cy="3" r="1.2" className="fill-white/80" />
      </pattern>
    </defs>
  );
}

function barFill(prefix: string, key: SeriesKey): { className?: string; fill?: string } {
  if (key === "income") return { className: "fill-blue-600 dark:fill-blue-500" };
  if (key === "expenses") return { fill: `url(#${prefix}-hatch)` };
  if (key === "commission") return { fill: `url(#${prefix}-dots)` };
  return { className: "fill-slate-500" };
}

const LINE_STYLE: Record<SeriesKey, { stroke: string; dash?: string }> = {
  income: { stroke: "stroke-blue-600 dark:stroke-blue-400" },
  expenses: { stroke: "stroke-sky-400 dark:stroke-sky-600", dash: "6 4" },
  commission: { stroke: "stroke-amber-500", dash: "2 3" },
  net: { stroke: "stroke-slate-600 dark:stroke-slate-300", dash: "1 4" },
};

function Legend({ series, prefix, kind }: { series: SeriesStyle[]; prefix: string; kind: "bar" | "line" }) {
  return (
    <ul className="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-foreground" aria-label="Legend">
      {series.map((s) => (
        <li key={s.key} className="flex items-center gap-1.5">
          <svg width="18" height="12" aria-hidden="true" className="shrink-0">
            {kind === "bar" ? (
              <>
                <PatternDefs prefix={`${prefix}-lg-${s.key}`} />
                <rect width="12" height="12" rx="2" {...barFill(`${prefix}-lg-${s.key}`, s.key)} />
              </>
            ) : (
              <line
                x1="0"
                x2="18"
                y1="6"
                y2="6"
                strokeWidth="2.5"
                strokeDasharray={LINE_STYLE[s.key].dash}
                className={LINE_STYLE[s.key].stroke}
              />
            )}
          </svg>
          {s.label}
        </li>
      ))}
    </ul>
  );
}

function ViewToggle({ view, onChange, testPrefix }: { view: "chart" | "table"; onChange: (v: "chart" | "table") => void; testPrefix: string }) {
  return (
    <div className="flex gap-1" role="group" aria-label="Chart view">
      <Button
        type="button"
        size="sm"
        variant={view === "chart" ? "secondary" : "ghost"}
        aria-pressed={view === "chart"}
        onClick={() => onChange("chart")}
        data-testid={`${testPrefix}-view-chart`}
      >
        <BarChart3 aria-hidden="true" />
        Chart
      </Button>
      <Button
        type="button"
        size="sm"
        variant={view === "table" ? "secondary" : "ghost"}
        aria-pressed={view === "table"}
        onClick={() => onChange("table")}
        data-testid={`${testPrefix}-view-table`}
      >
        <Table2 aria-hidden="true" />
        Table
      </Button>
    </div>
  );
}

export interface ChartDatum {
  key: string;
  label: string;
  values: Partial<Record<SeriesKey, number>>;
}

function ValuesTable({
  caption,
  data,
  series,
  currency,
  firstHeader,
  testId,
}: {
  caption: string;
  data: ChartDatum[];
  series: SeriesKey[];
  currency: string;
  firstHeader: string;
  testId: string;
}) {
  return (
    <div className="max-h-96 overflow-auto rounded-lg border border-border" data-testid={testId}>
      <table className="w-full text-left text-sm">
        <caption className="sr-only">{caption}</caption>
        <thead className="sticky top-0 border-b border-border bg-muted">
          <tr>
            <th scope="col" className="px-3 py-2 font-medium">
              {firstHeader}
            </th>
            {series.map((key) => (
              <th key={key} scope="col" className="px-3 py-2 text-right font-medium">
                {SERIES_LABEL[key]}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {data.map((datum) => (
            <tr key={datum.key} className="border-b border-border last:border-0">
              <th scope="row" className="px-3 py-1.5 font-normal">
                {datum.label}
              </th>
              {series.map((key) => (
                <td key={key} className="px-3 py-1.5 text-right tabular-nums">
                  {formatMoney(datum.values[key] ?? 0, currency)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function describe(datum: ChartDatum, series: SeriesKey[], currency: string): string {
  return `${datum.label}: ${series.map((key) => `${SERIES_LABEL[key]} ${formatMoney(datum.values[key] ?? 0, currency)}`).join(", ")}`;
}

const W = 720;
const H = 260;
const M = { top: 12, right: 8, bottom: 28, left: 52 };

/**
 * Grouped bars (Student Income "Income and expenses"). Each period is one
 * focusable button: click / Enter / Space selects it, arrow keys / Home / End
 * move between periods. The selected period is marked with a frame and
 * `aria-pressed`, not colour.
 */
export function GroupedBarChart({
  data,
  series,
  currency,
  title,
  selectedKey,
  onSelect,
}: {
  data: ChartDatum[];
  series: SeriesKey[];
  currency: string;
  title: string;
  selectedKey?: string | null;
  onSelect?: (key: string) => void;
}) {
  const prefix = `gb${useId().replace(/[^a-zA-Z0-9_-]/g, "")}`;
  const [view, setView] = useState<"chart" | "table">("chart");
  const [active, setActive] = useState<number | null>(null);
  const refs = useRef<(SVGGElement | null)[]>([]);
  const all = data.flatMap((d) => series.map((key) => d.values[key] ?? 0));
  const { top, bottom, ticks } = scale(all);
  const plotW = W - M.left - M.right;
  const plotH = H - M.top - M.bottom;
  const slot = data.length ? plotW / data.length : plotW;
  const barW = Math.min(16, (slot * 0.8) / series.length);
  const y = (v: number) => M.top + plotH - ((v - bottom) / (top - bottom || 1)) * plotH;
  const zero = y(0);

  function onKey(event: KeyboardEvent<SVGGElement>, index: number) {
    let next: number | null = null;
    if (event.key === "ArrowRight") next = Math.min(data.length - 1, index + 1);
    else if (event.key === "ArrowLeft") next = Math.max(0, index - 1);
    else if (event.key === "Home") next = 0;
    else if (event.key === "End") next = data.length - 1;
    else if (event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      onSelect?.(data[index].key);
      return;
    }
    if (next !== null) {
      event.preventDefault();
      refs.current[next]?.focus();
    }
  }

  const activeDatum = active !== null ? data[active] : null;

  return (
    <div className="flex min-w-0 flex-col gap-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <Legend series={series.map((key) => ({ key, label: SERIES_LABEL[key] }))} prefix={prefix} kind="bar" />
        <ViewToggle view={view} onChange={setView} testPrefix="income-chart" />
      </div>
      {view === "table" ? (
        <ValuesTable
          caption={title}
          data={data}
          series={series}
          currency={currency}
          firstHeader="Month"
          testId="income-chart-table"
        />
      ) : (
        <div className="relative min-w-0">
          <svg
            viewBox={`0 0 ${W} ${H}`}
            className="block h-auto w-full"
            role="group"
            aria-label={`${title}. ${onSelect ? "Use arrow keys to move between months and Enter to show a month's summary. " : ""}The Table view lists every value.`}
            onMouseLeave={() => setActive(null)}
            data-testid="income-chart"
          >
            <PatternDefs prefix={prefix} />
            {ticks.map((tick) => (
              <g key={tick} aria-hidden="true">
                <line x1={M.left} x2={W - M.right} y1={y(tick)} y2={y(tick)} className="stroke-border" strokeWidth="1" />
                <text x={M.left - 6} y={y(tick)} dy="0.32em" textAnchor="end" className="fill-muted-foreground text-[11px] tabular-nums">
                  {compact(tick)}
                </text>
              </g>
            ))}
            {data.map((datum, index) => {
              const groupX = M.left + slot * index + (slot - barW * series.length) / 2;
              const selected = selectedKey === datum.key;
              return (
                <g
                  key={datum.key}
                  ref={(el) => {
                    refs.current[index] = el;
                  }}
                  tabIndex={0}
                  role={onSelect ? "button" : "img"}
                  aria-pressed={onSelect ? selected : undefined}
                  aria-label={describe(datum, series, currency)}
                  data-testid="income-chart-bar"
                  data-key={datum.key}
                  onClick={() => onSelect?.(datum.key)}
                  onMouseEnter={() => setActive(index)}
                  onFocus={() => setActive(index)}
                  onBlur={() => setActive((c) => (c === index ? null : c))}
                  onKeyDown={(event) => onKey(event, index)}
                  className={cn("outline-none [&:focus-visible>rect.ring]:stroke-ring", onSelect && "cursor-pointer")}
                >
                  <rect
                    className={cn("ring fill-transparent", selected ? "stroke-foreground" : "stroke-transparent")}
                    x={M.left + slot * index + 1}
                    y={M.top}
                    width={Math.max(0, slot - 2)}
                    height={plotH}
                    strokeWidth="1.5"
                    strokeDasharray={selected ? "4 3" : undefined}
                    rx="4"
                  />
                  {series.map((key, s) => {
                    const v = datum.values[key] ?? 0;
                    const yy = y(Math.max(v, 0));
                    const h = Math.abs(y(v) - zero);
                    return v !== 0 ? (
                      <rect key={key} x={groupX + s * barW} y={v >= 0 ? yy : zero} width={barW - 1} height={Math.max(1, h)} {...barFill(prefix, key)} />
                    ) : null;
                  })}
                  <text
                    x={M.left + slot * index + slot / 2}
                    y={H - 8}
                    textAnchor="middle"
                    aria-hidden="true"
                    className={cn("fill-muted-foreground text-[11px]", (selected || active === index) && "fill-foreground font-semibold")}
                  >
                    {datum.label}
                  </text>
                </g>
              );
            })}
          </svg>
          {activeDatum ? (
            <div
              aria-hidden="true"
              data-testid="income-chart-tooltip"
              className="pointer-events-none absolute top-1 right-1 z-10 rounded-md bg-foreground px-2.5 py-2 text-xs text-background shadow-md"
            >
              <p className="font-semibold">{activeDatum.label}</p>
              {series.map((key) => (
                <p key={key}>
                  {SERIES_LABEL[key]}: {formatMoney(activeDatum.values[key] ?? 0, currency)}
                </p>
              ))}
            </div>
          ) : null}
        </div>
      )}
    </div>
  );
}

/** Line chart (Budget cash-flow trend): income, expenses and net, each with its own dash pattern and marker shape. */
export function TrendLineChart({
  data,
  currency,
  title,
}: {
  data: ChartDatum[];
  currency: string;
  title: string;
}) {
  const series: SeriesKey[] = ["income", "expenses", "net"];
  const prefix = `tl${useId().replace(/[^a-zA-Z0-9_-]/g, "")}`;
  const [view, setView] = useState<"chart" | "table">("chart");
  const [active, setActive] = useState<number | null>(null);
  const refs = useRef<(SVGGElement | null)[]>([]);
  const { top, bottom, ticks } = scale(data.flatMap((d) => series.map((k) => d.values[k] ?? 0)));
  const plotW = W - M.left - M.right;
  const plotH = H - M.top - M.bottom;
  const slot = data.length ? plotW / data.length : plotW;
  const x = (i: number) => M.left + slot * i + slot / 2;
  const y = (v: number) => M.top + plotH - ((v - bottom) / (top - bottom || 1)) * plotH;
  const labelEvery = Math.max(1, Math.ceil(data.length / 8));

  function onKey(event: KeyboardEvent<SVGGElement>, index: number) {
    let next: number | null = null;
    if (event.key === "ArrowRight") next = Math.min(data.length - 1, index + 1);
    else if (event.key === "ArrowLeft") next = Math.max(0, index - 1);
    else if (event.key === "Home") next = 0;
    else if (event.key === "End") next = data.length - 1;
    if (next !== null) {
      event.preventDefault();
      refs.current[next]?.focus();
    }
  }

  const activeDatum = active !== null ? data[active] : null;

  return (
    <div className="flex min-w-0 flex-col gap-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <Legend series={series.map((key) => ({ key, label: SERIES_LABEL[key] }))} prefix={prefix} kind="line" />
        <ViewToggle view={view} onChange={setView} testPrefix="trend-chart" />
      </div>
      {view === "table" ? (
        <ValuesTable caption={title} data={data} series={series} currency={currency} firstHeader="Period" testId="trend-chart-table" />
      ) : (
        <div className="relative min-w-0">
          <svg
            viewBox={`0 0 ${W} ${H}`}
            className="block h-auto w-full"
            role="group"
            aria-label={`${title}. Use arrow keys to move between periods; the Table view lists every value.`}
            onMouseLeave={() => setActive(null)}
            data-testid="trend-chart"
          >
            {ticks.map((tick) => (
              <g key={tick} aria-hidden="true">
                <line
                  x1={M.left}
                  x2={W - M.right}
                  y1={y(tick)}
                  y2={y(tick)}
                  className={tick === 0 ? "stroke-muted-foreground" : "stroke-border"}
                  strokeWidth="1"
                />
                <text x={M.left - 6} y={y(tick)} dy="0.32em" textAnchor="end" className="fill-muted-foreground text-[11px] tabular-nums">
                  {compact(tick)}
                </text>
              </g>
            ))}
            {series.map((key) => (
              <polyline
                key={key}
                aria-hidden="true"
                fill="none"
                strokeWidth="2.5"
                strokeLinejoin="round"
                strokeDasharray={LINE_STYLE[key].dash}
                className={LINE_STYLE[key].stroke}
                points={data.map((d, i) => `${x(i)},${y(d.values[key] ?? 0)}`).join(" ")}
              />
            ))}
            {data.map((datum, index) => (
              <g
                key={datum.key}
                ref={(el) => {
                  refs.current[index] = el;
                }}
                tabIndex={0}
                role="img"
                aria-label={describe(datum, series, currency)}
                data-testid="trend-point"
                onMouseEnter={() => setActive(index)}
                onFocus={() => setActive(index)}
                onBlur={() => setActive((c) => (c === index ? null : c))}
                onKeyDown={(event) => onKey(event, index)}
                className="outline-none [&:focus-visible>rect]:stroke-ring"
              >
                <rect x={M.left + slot * index} y={M.top} width={slot} height={plotH} className="fill-transparent stroke-transparent" strokeWidth="1.5" />
                <circle cx={x(index)} cy={y(datum.values.income ?? 0)} r="3" className="fill-blue-600 dark:fill-blue-400" />
                <rect x={x(index) - 3} y={y(datum.values.expenses ?? 0) - 3} width="6" height="6" className="fill-sky-400 dark:fill-sky-600" />
                <path
                  d={`M ${x(index)} ${y(datum.values.net ?? 0) - 4} l 4 4 l -4 4 l -4 -4 z`}
                  className="fill-slate-600 dark:fill-slate-300"
                />
                {index % labelEvery === 0 ? (
                  <text x={x(index)} y={H - 8} textAnchor="middle" aria-hidden="true" className="fill-muted-foreground text-[11px]">
                    {datum.label}
                  </text>
                ) : null}
              </g>
            ))}
          </svg>
          {activeDatum ? (
            <div
              aria-hidden="true"
              data-testid="trend-chart-tooltip"
              className="pointer-events-none absolute top-1 right-1 z-10 rounded-md bg-foreground px-2.5 py-2 text-xs text-background shadow-md"
            >
              <p className="font-semibold">{activeDatum.label}</p>
              {series.map((key) => (
                <p key={key}>
                  {SERIES_LABEL[key]}: {formatMoney(activeDatum.values[key] ?? 0, currency)}
                </p>
              ))}
            </div>
          ) : null}
        </div>
      )}
    </div>
  );
}

/**
 * Horizontal bars with the amount always printed next to each bar (Budget
 * "Income by category" / "Expenses by category"). Widths are relative to the
 * largest row, for shape only; the printed amounts are the server's figures.
 */
export function CategoryBars({
  rows,
  currency,
  variant,
  label,
}: {
  rows: { key: string; label: string; total: number; count: number }[];
  currency: string;
  variant: "income" | "expenses";
  label: string;
}) {
  const max = Math.max(0, ...rows.map((r) => Math.abs(r.total)));
  return (
    <ul className="flex flex-col gap-2" aria-label={label}>
      {rows.map((row) => {
        const pct = max > 0 ? Math.max(2, (Math.abs(row.total) / max) * 100) : 0;
        return (
          <li key={row.key} className="flex flex-col gap-1">
            <div className="flex items-baseline justify-between gap-3 text-sm">
              <span className="min-w-0 truncate">
                {row.label} <span className="text-xs text-muted-foreground">({row.count})</span>
              </span>
              <span className="shrink-0 font-medium tabular-nums">
                {row.total < 0 ? "−" : ""}
                {formatMoney(Math.abs(row.total), currency)}
              </span>
            </div>
            <div className="h-2 w-full overflow-hidden rounded-full bg-muted" aria-hidden="true">
              <div
                className={cn(
                  "h-full rounded-full",
                  variant === "income"
                    ? "bg-blue-600 dark:bg-blue-500"
                    : "bg-[repeating-linear-gradient(45deg,var(--color-sky-300)_0_4px,var(--color-sky-200)_4px_7px)]"
                )}
                style={{ width: `${pct}%` }}
              />
            </div>
          </li>
        );
      })}
    </ul>
  );
}
