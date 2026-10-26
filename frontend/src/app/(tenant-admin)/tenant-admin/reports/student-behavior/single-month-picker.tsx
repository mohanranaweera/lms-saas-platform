"use client";
import { useState } from "react";
import { ChevronLeft, ChevronRight } from "lucide-react";
import { Button } from "@/components/ui/button";
import { MONTH_LONG_NAMES, MONTH_SHORT_NAMES, monthKey } from "@/lib/validation/reports";
import { cn } from "@/lib/utils";
export function SingleMonthPicker({ value, onChange, invalid }: { value: string; onChange: (value: string) => void; invalid?: boolean }) {
  const [displayedYear, setDisplayedYear] = useState(() => value ? Number(value.slice(0, 4)) : new Date().getFullYear());
  const [selectedYear, selectedMonth] = value ? value.split("-").map(Number) : [null, null];
  const selectedLabel = selectedYear && selectedMonth ? `${MONTH_LONG_NAMES[selectedMonth - 1]} ${selectedYear}` : "Choose a month";
  return <div className={cn("overflow-hidden rounded-md border", invalid && "border-destructive")} data-testid="student-behavior-month-picker"><div className="bg-primary px-7 py-5 text-primary-foreground"><p className="text-xs font-bold tracking-[0.18em]">SELECT MONTH</p><p className="mt-4 text-lg font-semibold" aria-live="polite">{selectedLabel}</p></div><div className="px-3 py-2"><div className="flex items-center justify-center gap-8"><Button type="button" variant="ghost" size="icon-sm" aria-label={`Previous year, ${displayedYear - 1}`} onClick={() => setDisplayedYear((year) => year - 1)}><ChevronLeft aria-hidden="true" /></Button><span className="min-w-16 text-center text-sm font-semibold tabular-nums" aria-live="polite">{displayedYear}</span><Button type="button" variant="ghost" size="icon-sm" aria-label={`Next year, ${displayedYear + 1}`} onClick={() => setDisplayedYear((year) => year + 1)}><ChevronRight aria-hidden="true" /></Button></div><div className="grid grid-cols-3 gap-x-5 gap-y-2 py-3">{MONTH_SHORT_NAMES.map((month, index) => { const key = monthKey(displayedYear, index); const selected = value === key; return <button key={key} type="button" aria-pressed={selected} aria-label={`${MONTH_LONG_NAMES[index]} ${displayedYear}${selected ? ", selected" : ""}`} onClick={() => onChange(key)} className={cn("min-h-11 rounded-full px-2 text-sm font-semibold uppercase tracking-wide outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2", selected ? "bg-primary text-primary-foreground" : "text-foreground hover:bg-muted")}>{month}</button>; })}</div></div></div>;
}
