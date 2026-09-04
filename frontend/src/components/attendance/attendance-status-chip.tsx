"use client";

import { useRef, type KeyboardEvent } from "react";
import { CheckCircle2, Circle, Clock, XCircle } from "lucide-react";
import { cn } from "@/lib/utils";
import type { AttendanceStatus } from "@/lib/api/attendance";

/**
 * Attendance status chip/control pair — never color alone
 * (`.claude/rules/ui-ux.md` §4): every status pairs an icon with a text
 * label, following `components/courses/course-status-badge.tsx`'s exact
 * chip-styling precedent. `Excused` is deliberately absent from every map
 * below — out of MVP-016's scope, never offered as an option.
 */

export const ATTENDANCE_STATUS_LABELS: Record<AttendanceStatus, string> = {
  PRESENT: "Present",
  ABSENT: "Absent",
  LATE: "Late",
};

const STATUS_STYLES: Record<AttendanceStatus, { icon: typeof CheckCircle2; className: string }> = {
  PRESENT: { icon: CheckCircle2, className: "text-foreground" },
  ABSENT: { icon: XCircle, className: "text-destructive" },
  LATE: { icon: Clock, className: "text-muted-foreground" },
};

/** Read-only display chip. `status: null` renders "Not marked" (a roster row that hasn't been recorded yet). */
export function AttendanceStatusChip({ status }: { status: AttendanceStatus | null }) {
  if (status === null) {
    return (
      <span className="inline-flex w-fit items-center gap-1.5 rounded-md border border-dashed border-border bg-muted/40 px-2 py-0.5 text-xs font-medium text-muted-foreground">
        <Circle className="size-3.5" aria-hidden="true" />
        Not marked
      </span>
    );
  }

  const { icon: Icon, className } = STATUS_STYLES[status];
  return (
    <span
      className={cn(
        "inline-flex w-fit items-center gap-1.5 rounded-md border border-border bg-muted px-2 py-0.5 text-xs font-medium",
        className
      )}
    >
      <Icon className="size-3.5" aria-hidden="true" />
      {ATTENDANCE_STATUS_LABELS[status]}
    </span>
  );
}

const SEGMENTED_OPTIONS: AttendanceStatus[] = ["PRESENT", "ABSENT", "LATE"];

interface AttendanceSegmentedControlProps {
  /** Identifies which student this control marks attendance for — rendered as the group's accessible name (e.g. `Attendance for Student #a1b2c3d4`), never three unlabeled icon buttons. */
  studentLabel: string;
  value: AttendanceStatus | null;
  onChange: (status: AttendanceStatus) => void;
  disabled?: boolean;
}

/**
 * Keyboard-operable 3-way segmented control for a Mark Attendance roster
 * row. No shadcn `RadioGroup` primitive exists in this codebase and one is
 * deliberately not added (`docs/plans/MVP-016 Attendance.md` frontend brief)
 * — built here from plain elements with real ARIA: `role="radiogroup"` on
 * the group (labeled via `aria-label`), each option `role="radio"` +
 * `aria-checked`, a roving `tabIndex` (arrow keys move focus AND selection,
 * matching a native radio group's behavior), `Home`/`End` jump to the first/
 * last option. `Excused` is never offered.
 */
export function AttendanceSegmentedControl({
  studentLabel,
  value,
  onChange,
  disabled,
}: AttendanceSegmentedControlProps) {
  const optionRefs = useRef<Array<HTMLButtonElement | null>>([]);

  function focusAndSelect(index: number) {
    const wrapped = (index + SEGMENTED_OPTIONS.length) % SEGMENTED_OPTIONS.length;
    optionRefs.current[wrapped]?.focus();
    onChange(SEGMENTED_OPTIONS[wrapped]);
  }

  function handleKeyDown(event: KeyboardEvent<HTMLButtonElement>, index: number) {
    if (disabled) return;
    switch (event.key) {
      case "ArrowRight":
      case "ArrowDown":
        event.preventDefault();
        focusAndSelect(index + 1);
        break;
      case "ArrowLeft":
      case "ArrowUp":
        event.preventDefault();
        focusAndSelect(index - 1);
        break;
      case "Home":
        event.preventDefault();
        focusAndSelect(0);
        break;
      case "End":
        event.preventDefault();
        focusAndSelect(SEGMENTED_OPTIONS.length - 1);
        break;
      default:
        break;
    }
  }

  const selectedIndex = value ? SEGMENTED_OPTIONS.indexOf(value) : -1;

  return (
    <div
      role="radiogroup"
      aria-label={`Attendance for ${studentLabel}`}
      className="inline-flex w-fit overflow-hidden rounded-md border border-border"
    >
      {SEGMENTED_OPTIONS.map((option, index) => {
        const isChecked = value === option;
        const { icon: Icon } = STATUS_STYLES[option];
        return (
          <button
            key={option}
            ref={(el) => {
              optionRefs.current[index] = el;
            }}
            type="button"
            role="radio"
            aria-checked={isChecked}
            aria-label={`${ATTENDANCE_STATUS_LABELS[option]} — ${studentLabel}`}
            tabIndex={index === (selectedIndex === -1 ? 0 : selectedIndex) ? 0 : -1}
            disabled={disabled}
            onClick={() => onChange(option)}
            onKeyDown={(event) => handleKeyDown(event, index)}
            className={cn(
              "flex items-center gap-1.5 border-r border-border px-2.5 py-1.5 text-xs font-medium transition-colors last:border-r-0 focus-visible:z-10 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50 disabled:cursor-not-allowed disabled:opacity-50",
              isChecked
                ? option === "ABSENT"
                  ? "bg-destructive/10 text-destructive"
                  : "bg-foreground text-background"
                : "bg-background text-muted-foreground hover:bg-muted"
            )}
          >
            <Icon className="size-3.5" aria-hidden="true" />
            {ATTENDANCE_STATUS_LABELS[option]}
          </button>
        );
      })}
    </div>
  );
}
