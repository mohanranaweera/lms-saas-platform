"use client";

import { useRef, type KeyboardEvent, type ReactNode } from "react";
import { cn } from "@/lib/utils";

/**
 * Shared "Tabs" primitive — first consumer: the Wave 3 Student/Teacher Detail
 * pages' Profile/Enrollments/Payments/Attendance/Exams/Activity tab layout.
 * Hand-rolled (not `@base-ui/react`'s `Tabs` primitive) following the same
 * "native semantics first" convention already established by
 * `components/ui/accordion.tsx` in this codebase: a real `role="tablist"` of
 * `role="tab"` buttons with a roving `tabIndex` (arrow-key/Home/End
 * navigation, per WAI-ARIA's Tabs pattern), each wired to its own
 * `role="tabpanel"` via `aria-controls`/`aria-labelledby`, content hidden via
 * the native `hidden` attribute (never CSS-only) so it's removed from the
 * accessibility tree and tab order while inactive — per
 * `.claude/rules/ui-ux.md` §4's keyboard-navigability requirement.
 *
 * Supports both uncontrolled (`defaultValue`) and controlled (`value` +
 * `onValueChange`) usage — the Student/Teacher detail pages use the
 * controlled form so the active tab lives in the URL's `?tab=` query param
 * (deep-linkable, survives a refresh), matching
 * `app/(tenant-admin)/tenant-admin/audit-log/page.tsx`'s existing URL-as-
 * source-of-truth convention for page-level UI state.
 */

export interface TabItem {
  value: string;
  label: string;
  content: ReactNode;
  /** Rendered inline after the label, e.g. a row-count badge. */
  badge?: ReactNode;
}

export interface TabsProps {
  items: TabItem[];
  value?: string;
  defaultValue?: string;
  onValueChange?: (value: string) => void;
  className?: string;
  /** Accessible name for the tablist — required, since no visible heading precedes it on any current call site. */
  "aria-label": string;
}

export function Tabs({
  items,
  value: controlledValue,
  defaultValue,
  onValueChange,
  className,
  ...rest
}: TabsProps) {
  const ariaLabel = rest["aria-label"];
  const isControlled = controlledValue !== undefined;
  const activeValue = isControlled ? controlledValue : (defaultValue ?? items[0]?.value ?? "");
  const tabRefs = useRef<Record<string, HTMLButtonElement | null>>({});

  function select(next: string) {
    onValueChange?.(next);
  }

  function handleKeyDown(event: KeyboardEvent<HTMLButtonElement>, index: number) {
    if (!["ArrowRight", "ArrowLeft", "Home", "End"].includes(event.key)) return;
    event.preventDefault();
    let nextIndex = index;
    if (event.key === "ArrowRight") nextIndex = (index + 1) % items.length;
    if (event.key === "ArrowLeft") nextIndex = (index - 1 + items.length) % items.length;
    if (event.key === "Home") nextIndex = 0;
    if (event.key === "End") nextIndex = items.length - 1;
    const next = items[nextIndex];
    if (!next) return;
    select(next.value);
    tabRefs.current[next.value]?.focus();
  }

  return (
    <div className={cn("flex flex-col gap-4", className)}>
      <div
        role="tablist"
        aria-label={ariaLabel}
        className="flex flex-nowrap gap-1 overflow-x-auto border-b border-border"
      >
        {items.map((item, index) => {
          const selected = item.value === activeValue;
          return (
            <button
              key={item.value}
              ref={(el) => {
                tabRefs.current[item.value] = el;
              }}
              type="button"
              role="tab"
              id={`tab-${item.value}`}
              aria-selected={selected}
              aria-controls={`tabpanel-${item.value}`}
              tabIndex={selected ? 0 : -1}
              onClick={() => select(item.value)}
              onKeyDown={(event) => handleKeyDown(event, index)}
              className={cn(
                "inline-flex shrink-0 items-center gap-1.5 border-b-2 px-3 py-2 text-sm font-medium outline-none transition-colors focus-visible:ring-3 focus-visible:ring-ring/50",
                selected
                  ? "border-foreground text-foreground"
                  : "border-transparent text-muted-foreground hover:text-foreground"
              )}
            >
              {item.label}
              {item.badge}
            </button>
          );
        })}
      </div>
      {items.map((item) => (
        <div
          key={item.value}
          role="tabpanel"
          id={`tabpanel-${item.value}`}
          aria-labelledby={`tab-${item.value}`}
          hidden={item.value !== activeValue}
          tabIndex={0}
        >
          {item.value === activeValue ? item.content : null}
        </div>
      ))}
    </div>
  );
}
