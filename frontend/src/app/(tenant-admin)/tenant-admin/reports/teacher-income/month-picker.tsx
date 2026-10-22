"use client";

import { useState } from "react";
import { ChevronLeft, ChevronRight } from "lucide-react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import {
  MONTH_LONG_NAMES,
  MONTH_SHORT_NAMES,
  monthKey,
  monthKeyLabel,
} from "@/lib/validation/reports";

/**
 * Klass-style multi-month picker (spec §1.1): year navigation, 12 month
 * toggle buttons (`aria-pressed`), "N months selected" header. The selection
 * is a set of `"YYYY-MM"` keys, so months chosen in other years persist while
 * navigating. Starts on the current calendar year with nothing selected.
 */
export function MonthPicker({
  value,
  onChange,
  invalid,
  describedBy,
  labelId,
}: {
  value: string[];
  onChange: (next: string[]) => void;
  invalid?: boolean;
  describedBy?: string;
  labelId: string;
}) {
  const [year, setYear] = useState(() => new Date().getFullYear());
  const selected = new Set(value);
  const otherYears = [...selected].filter((key) => !key.startsWith(`${year}-`)).sort();

  const toggle = (key: string) => {
    const next = new Set(selected);
    if (next.has(key)) next.delete(key);
    else next.add(key);
    onChange([...next].sort());
  };

  return (
    <div
      role="group"
      aria-labelledby={labelId}
      aria-describedby={describedBy}
      className={cn(
        "flex flex-col gap-3 rounded-lg border border-border p-3",
        invalid && "border-destructive"
      )}
      data-testid="month-picker"
    >
      <div className="flex items-center justify-between gap-2">
        <p className="text-sm font-medium" aria-live="polite" data-testid="months-selected-count">
          {selected.size} {selected.size === 1 ? "month" : "months"} selected
        </p>
        {selected.size > 0 ? (
          <Button type="button" variant="ghost" size="xs" onClick={() => onChange([])}>
            Clear
          </Button>
        ) : null}
      </div>
      <div className="flex items-center justify-between">
        <Button
          type="button"
          variant="ghost"
          size="icon-sm"
          aria-label={`Previous year, ${year - 1}`}
          onClick={() => setYear((current) => current - 1)}
        >
          <ChevronLeft aria-hidden="true" />
        </Button>
        <span className="text-sm font-semibold tabular-nums" aria-live="polite" data-testid="month-picker-year">
          {year}
        </span>
        <Button
          type="button"
          variant="ghost"
          size="icon-sm"
          aria-label={`Next year, ${year + 1}`}
          onClick={() => setYear((current) => current + 1)}
        >
          <ChevronRight aria-hidden="true" />
        </Button>
      </div>
      <div className="grid grid-cols-3 gap-2 sm:grid-cols-4">
        {MONTH_SHORT_NAMES.map((name, index) => {
          const key = monthKey(year, index);
          const pressed = selected.has(key);
          return (
            <button
              key={key}
              type="button"
              aria-pressed={pressed}
              aria-label={`${MONTH_LONG_NAMES[index]} ${year}`}
              onClick={() => toggle(key)}
              className={cn(
                // No colour transition: every state is a solid, AA-contrast pair
                // (never an in-between grey frame). Focus uses an offset ring.
                "rounded-full border px-2 py-1.5 text-xs font-medium outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background",
                pressed
                  ? "border-primary bg-primary text-primary-foreground hover:bg-primary hover:text-primary-foreground hover:underline"
                  : "border-border bg-background text-foreground hover:border-foreground/40 hover:bg-muted hover:text-foreground"
              )}
            >
              {name}
            </button>
          );
        })}
      </div>
      {otherYears.length > 0 ? (
        <p className="text-xs text-muted-foreground" data-testid="months-other-years">
          Also selected: {otherYears.map(monthKeyLabel).join(", ")}
        </p>
      ) : null}
    </div>
  );
}
