"use client";

import { useEffect, useId, useRef, useState, type KeyboardEvent } from "react";
import { BarChart3, Table2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

export interface DistributionDatum {
  sequence: number;
  correct: number;
  incorrect: number;
  unanswered: number;
}

type SeriesKey = "correct" | "incorrect" | "unanswered";

/**
 * Series styling. Each series has its own fill *and* its own texture
 * (solid / diagonal hatch / dots) so the chart and legend never rely on
 * colour alone; the legend repeats the swatches with text labels.
 */
const SERIES: { key: SeriesKey; label: string; fill: string; pattern: "solid" | "hatch" | "dots" }[] = [
  { key: "correct", label: "Correct", fill: "fill-green-600 dark:fill-green-500", pattern: "solid" },
  { key: "incorrect", label: "Incorrect", fill: "fill-red-600 dark:fill-red-500", pattern: "hatch" },
  { key: "unanswered", label: "Unanswered", fill: "fill-slate-400 dark:fill-slate-500", pattern: "dots" },
];

const MARGIN = { top: 12, right: 12, bottom: 44, left: 52 };
const PLOT_HEIGHT = 240;
const MIN_SLOT = 30;

function niceStep(max: number): number {
  if (max <= 5) return 1;
  const raw = max / 5;
  const magnitude = 10 ** Math.floor(Math.log10(raw));
  const normalized = raw / magnitude;
  const nice = normalized <= 1 ? 1 : normalized <= 2 ? 2 : normalized <= 5 ? 5 : 10;
  return Math.max(1, nice * magnitude);
}

function total(datum: DistributionDatum): number {
  return datum.correct + datum.incorrect + datum.unanswered;
}

export function describeDatum(datum: DistributionDatum): string {
  return `Question ${datum.sequence}: ${datum.correct} correct, ${datum.incorrect} incorrect, ${datum.unanswered} unanswered (${total(datum)} responses)`;
}

function useElementWidth<T extends HTMLElement>() {
  const ref = useRef<T>(null);
  const [width, setWidth] = useState(0);
  useEffect(() => {
    const element = ref.current;
    if (!element) return;
    const update = () => setWidth(Math.floor(element.getBoundingClientRect().width));
    update();
    const observer = new ResizeObserver(update);
    observer.observe(element);
    return () => observer.disconnect();
  }, []);
  return [ref, width] as const;
}

/** Pattern definitions shared by the chart and the legend swatches. */
function PatternDefs({ prefix }: { prefix: string }) {
  return (
    <defs>
      <pattern id={`${prefix}-hatch`} width="6" height="6" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
        <rect width="6" height="6" className="fill-red-600 dark:fill-red-500" />
        <line x1="0" y1="0" x2="0" y2="6" className="stroke-white/70" strokeWidth="2" />
      </pattern>
      <pattern id={`${prefix}-dots`} width="6" height="6" patternUnits="userSpaceOnUse">
        <rect width="6" height="6" className="fill-slate-400 dark:fill-slate-500" />
        <circle cx="3" cy="3" r="1.2" className="fill-white/80" />
      </pattern>
    </defs>
  );
}

function seriesFill(prefix: string, series: (typeof SERIES)[number]): { className?: string; fill?: string } {
  if (series.pattern === "solid") return { className: series.fill };
  return { fill: `url(#${prefix}-${series.pattern})` };
}

function Legend({ prefix }: { prefix: string }) {
  return (
    <ul className="flex flex-wrap items-center gap-x-5 gap-y-1 text-sm text-foreground" aria-label="Legend" data-testid="distribution-legend">
      {SERIES.map((series) => (
        <li key={series.key} className="flex items-center gap-1.5">
          <svg width="14" height="14" aria-hidden="true" className="shrink-0 rounded-sm">
            <PatternDefs prefix={`${prefix}-legend-${series.key}`} />
            <rect width="14" height="14" rx="2" {...seriesFill(`${prefix}-legend-${series.key}`, series)} />
          </svg>
          {series.label}
        </li>
      ))}
    </ul>
  );
}

/** The accessible table alternative (also the chart's text equivalent). */
export function DistributionTable({ data }: { data: DistributionDatum[] }) {
  return (
    <div className="max-h-96 overflow-auto rounded-lg border border-border" data-testid="distribution-table">
      <table className="w-full text-left text-sm">
        <caption className="sr-only">Responses per question: correct, incorrect and unanswered</caption>
        <thead className="sticky top-0 border-b border-border bg-muted">
          <tr>
            <th scope="col" className="px-3 py-2 font-medium">Question</th>
            <th scope="col" className="px-3 py-2 text-right font-medium">Correct</th>
            <th scope="col" className="px-3 py-2 text-right font-medium">Incorrect</th>
            <th scope="col" className="px-3 py-2 text-right font-medium">Unanswered</th>
            <th scope="col" className="px-3 py-2 text-right font-medium">Responses</th>
          </tr>
        </thead>
        <tbody>
          {data.map((datum) => (
            <tr key={datum.sequence} className="border-b border-border last:border-0">
              <th scope="row" className="px-3 py-1.5 font-normal">
                {datum.sequence}
              </th>
              <td className="px-3 py-1.5 text-right tabular-nums">{datum.correct}</td>
              <td className="px-3 py-1.5 text-right tabular-nums">{datum.incorrect}</td>
              <td className="px-3 py-1.5 text-right tabular-nums">{datum.unanswered}</td>
              <td className="px-3 py-1.5 text-right tabular-nums">{total(datum)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/**
 * Klass "Question Distribution": one stacked bar per question number
 * (Correct / Incorrect / Unanswered), y = number of responses. Inline SVG
 * (no chart dependency). Every bar is focusable and announces its values;
 * hover/focus shows a tooltip; arrow keys move between bars. A table view
 * gives the same numbers as text. With no responses at all the chart is
 * replaced by an explanation instead of an empty grid.
 */
export function QuestionDistributionChart({ data }: { data: DistributionDatum[] }) {
  const rawId = useId();
  const prefix = `qd${rawId.replace(/[^a-zA-Z0-9_-]/g, "")}`;
  const [view, setView] = useState<"chart" | "table">("chart");
  const [active, setActive] = useState<number | null>(null);
  const [containerRef, containerWidth] = useElementWidth<HTMLDivElement>();
  const barRefs = useRef<(SVGGElement | null)[]>([]);

  const maxTotal = data.reduce((max, datum) => Math.max(max, total(datum)), 0);
  const hasResponses = maxTotal > 0;

  const step = niceStep(maxTotal);
  const yMax = Math.max(step, Math.ceil(maxTotal / step) * step);
  const ticks = Array.from({ length: Math.round(yMax / step) + 1 }, (_, index) => index * step);
  const minWidth = MARGIN.left + MARGIN.right + data.length * MIN_SLOT;
  const width = Math.max(containerWidth, minWidth);
  const plotWidth = width - MARGIN.left - MARGIN.right;
  const slot = data.length > 0 ? plotWidth / data.length : plotWidth;
  const barWidth = Math.min(48, slot * 0.6);
  const height = MARGIN.top + PLOT_HEIGHT + MARGIN.bottom;
  const y = (value: number) => MARGIN.top + PLOT_HEIGHT - (value / yMax) * PLOT_HEIGHT;

  function onBarKey(event: KeyboardEvent<SVGGElement>, index: number) {
    let next: number | null = null;
    if (event.key === "ArrowRight") next = Math.min(data.length - 1, index + 1);
    else if (event.key === "ArrowLeft") next = Math.max(0, index - 1);
    else if (event.key === "Home") next = 0;
    else if (event.key === "End") next = data.length - 1;
    else if (event.key === "Escape") setActive(null);
    if (next !== null) {
      event.preventDefault();
      barRefs.current[next]?.focus();
    }
  }

  const activeDatum = active !== null ? data[active] : null;
  const tooltipLeft = active !== null ? MARGIN.left + slot * active + slot / 2 : 0;

  return (
    <div className="flex min-w-0 flex-col gap-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <Legend prefix={prefix} />
        {hasResponses ? (
          <div className="flex gap-1" role="group" aria-label="Distribution view">
            <Button
              type="button"
              size="sm"
              variant={view === "chart" ? "secondary" : "ghost"}
              aria-pressed={view === "chart"}
              onClick={() => setView("chart")}
              data-testid="distribution-view-chart"
            >
              <BarChart3 aria-hidden="true" />
              Chart
            </Button>
            <Button
              type="button"
              size="sm"
              variant={view === "table" ? "secondary" : "ghost"}
              aria-pressed={view === "table"}
              onClick={() => setView("table")}
              data-testid="distribution-view-table"
            >
              <Table2 aria-hidden="true" />
              Table
            </Button>
          </div>
        ) : null}
      </div>

      {!hasResponses ? (
        <p className="rounded-lg border border-dashed border-border px-4 py-8 text-center text-sm text-muted-foreground" data-testid="distribution-empty">
          No responses yet — the chart appears once students have answered questions in this exam.
        </p>
      ) : view === "table" ? (
        <DistributionTable data={data} />
      ) : (
        <div ref={containerRef} className="relative min-w-0 overflow-x-auto" data-testid="distribution-chart">
          {containerWidth > 0 ? (
            <svg
              width={width}
              height={height}
              role="group"
              aria-label={`Question distribution: responses per question for ${data.length} questions. Use the Table view for all values.`}
              className="block"
              onMouseLeave={() => setActive(null)}
            >
              <PatternDefs prefix={prefix} />
              {ticks.map((tick) => (
                <g key={tick} aria-hidden="true">
                  <line x1={MARGIN.left} x2={width - MARGIN.right} y1={y(tick)} y2={y(tick)} className="stroke-border" strokeWidth="1" />
                  <text x={MARGIN.left - 8} y={y(tick)} dy="0.32em" textAnchor="end" className="fill-muted-foreground text-[11px] tabular-nums">
                    {tick}
                  </text>
                </g>
              ))}
              <text
                aria-hidden="true"
                transform={`translate(14 ${MARGIN.top + PLOT_HEIGHT / 2}) rotate(-90)`}
                textAnchor="middle"
                className="fill-muted-foreground text-[11px]"
              >
                Number of responses
              </text>
              <text
                aria-hidden="true"
                x={MARGIN.left + plotWidth / 2}
                y={height - 6}
                textAnchor="middle"
                className="fill-muted-foreground text-[11px]"
              >
                Question number
              </text>
              {data.map((datum, index) => {
                const x = MARGIN.left + slot * index + (slot - barWidth) / 2;
                let base = 0;
                const segments = SERIES.map((series) => {
                  const value = datum[series.key];
                  const top = y(base + value);
                  const segment = { series, value, yTop: top, h: y(base) - top };
                  base += value;
                  return segment;
                });
                return (
                  <g
                    key={datum.sequence}
                    ref={(element) => {
                      barRefs.current[index] = element;
                    }}
                    tabIndex={0}
                    role="img"
                    aria-label={describeDatum(datum)}
                    data-testid="distribution-bar"
                    data-sequence={datum.sequence}
                    onMouseEnter={() => setActive(index)}
                    onFocus={() => setActive(index)}
                    onBlur={() => setActive((current) => (current === index ? null : current))}
                    onKeyDown={(event) => onBarKey(event, index)}
                    className="cursor-default outline-none [&:focus-visible>rect.focus-ring]:stroke-ring"
                  >
                    <rect
                      className="focus-ring fill-transparent stroke-transparent"
                      x={MARGIN.left + slot * index + 1}
                      y={MARGIN.top}
                      width={Math.max(0, slot - 2)}
                      height={PLOT_HEIGHT}
                      strokeWidth="2"
                      rx="4"
                    />
                    {segments.map((segment) =>
                      segment.value > 0 ? (
                        <rect
                          key={segment.series.key}
                          x={x}
                          y={segment.yTop}
                          width={barWidth}
                          height={Math.max(0, segment.h)}
                          {...seriesFill(prefix, segment.series)}
                        />
                      ) : null
                    )}
                    <text
                      x={MARGIN.left + slot * index + slot / 2}
                      y={MARGIN.top + PLOT_HEIGHT + 16}
                      textAnchor="middle"
                      className={cn("fill-muted-foreground text-[11px] tabular-nums", active === index && "fill-foreground font-semibold")}
                      aria-hidden="true"
                    >
                      {datum.sequence}
                    </text>
                  </g>
                );
              })}
            </svg>
          ) : (
            <div style={{ height }} aria-hidden="true" />
          )}
          {activeDatum ? (
            <div
              role="presentation"
              aria-hidden="true"
              data-testid="distribution-tooltip"
              className="pointer-events-none absolute top-2 z-10 w-40 -translate-x-1/2 rounded-md bg-foreground px-2.5 py-2 text-xs text-background shadow-md"
              style={{ left: Math.min(Math.max(tooltipLeft, 84), Math.max(84, width - 84)) }}
            >
              <p className="font-semibold">Question {activeDatum.sequence}</p>
              <p>Correct: {activeDatum.correct}</p>
              <p>Incorrect: {activeDatum.incorrect}</p>
              <p>Unanswered: {activeDatum.unanswered}</p>
            </div>
          ) : null}
        </div>
      )}
    </div>
  );
}
