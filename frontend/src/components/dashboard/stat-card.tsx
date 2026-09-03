import type { ReactNode } from "react";
import { useId } from "react";
import Link from "next/link";
import { cn } from "@/lib/utils";

/**
 * Generic statistic tile, originally built for the Student Overview (MVP-013
 * SDASH-1) under `components/students/`. Relocated here (MVP-014 TDASH-1) now
 * that the Teacher Overview is a second role-group consumer, per
 * `.claude/rules/frontend.md`'s "extract to shared only when >= 2 role
 * groups actually need it" guidance. Kept under `components/dashboard/`
 * (dashboard-scoped, not a general-purpose `components/ui/` primitive) — see
 * `docs/plans/MVP-014 Teacher Dashboard.md` §21 item 3 for the open question
 * on whether this is the final long-term location.
 *
 * Pure presentation — `value`/`hint` are computed client-side by the caller
 * from already-fetched, already-scoped query data (display arithmetic only,
 * never a business rule). `tone` communicates alert/attention state visually
 * AND is always paired with text content (label/value/hint), never color
 * alone, per `.claude/rules/ui-ux.md` §4.
 */

export type StatCardTone = "default" | "warning" | "destructive";

interface StatCardAction {
  label: string;
  href: string;
}

interface StatCardProps {
  label: string;
  value: string | number;
  /** Short supporting text under the value, e.g. "as of your last payment". */
  hint?: string;
  icon?: ReactNode;
  tone?: StatCardTone;
  className?: string;
  /** Optional next-action link rendered under `hint`, e.g. a zero-state CTA ("Add a student"). */
  action?: StatCardAction;
}

const CONTAINER_TONE_STYLES: Record<StatCardTone, string> = {
  default: "border-border bg-card",
  warning: "border-warning/40 bg-warning/5",
  destructive: "border-destructive/30 bg-destructive/5",
};

const VALUE_TONE_STYLES: Record<StatCardTone, string> = {
  default: "text-foreground",
  warning: "text-warning",
  destructive: "text-destructive",
};

export function StatCard({ label, value, hint, icon, tone = "default", className, action }: StatCardProps) {
  const labelId = useId();
  return (
    <div
      role="group"
      aria-labelledby={labelId}
      className={cn(
        "flex flex-col gap-1 rounded-xl border p-4",
        CONTAINER_TONE_STYLES[tone],
        className
      )}
    >
      <div className="flex items-center justify-between gap-2">
        <p id={labelId} className="text-sm font-medium text-muted-foreground">{label}</p>
        {icon ? (
          <span className={cn("text-muted-foreground", tone !== "default" && VALUE_TONE_STYLES[tone])} aria-hidden="true">
            {icon}
          </span>
        ) : null}
      </div>
      <p className={cn("text-2xl font-semibold", VALUE_TONE_STYLES[tone])}>{value}</p>
      {hint ? <p className="text-xs text-muted-foreground">{hint}</p> : null}
      {action ? (
        <Link
          href={action.href}
          className="w-fit text-xs font-medium text-foreground hover:underline"
        >
          {action.label}
        </Link>
      ) : null}
    </div>
  );
}
