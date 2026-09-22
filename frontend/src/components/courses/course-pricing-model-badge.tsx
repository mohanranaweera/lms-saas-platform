import { Banknote, CalendarClock, Gift, Hourglass, Wallet } from "lucide-react";
import { cn } from "@/lib/utils";
import type { CoursePricingModel } from "@/lib/api/courses";

/**
 * Pricing-model chip — mirrors `course-status-badge.tsx`'s exact pattern
 * (icon + text label, never color alone, per `.claude/rules/ui-ux.md` §4).
 */
export const COURSE_PRICING_MODEL_LABELS: Record<CoursePricingModel, string> = {
  FREE: "Free",
  ONE_TIME: "One-time",
  MONTHLY: "Monthly",
  SESSION: "Per session",
  CUSTOM: "Custom / manual quote",
};

export const COURSE_PRICING_MODEL_DESCRIPTIONS: Record<CoursePricingModel, string> = {
  FREE: "No payment is ever required — checkout resolves to a $0 amount.",
  ONE_TIME: "A single flat price, changed via the dedicated price-change action.",
  MONTHLY: "Billed on a recurring schedule, using the current open billing period's amount.",
  SESSION: "Billed per session, using the configured session rate and current open billing period.",
  CUSTOM: "No system-resolved amount — an authorized staff member supplies one per order (manual quote).",
};

const PRICING_MODEL_STYLES: Record<CoursePricingModel, { icon: typeof Gift; className: string }> = {
  FREE: { icon: Gift, className: "text-muted-foreground" },
  ONE_TIME: { icon: Wallet, className: "text-foreground" },
  MONTHLY: { icon: CalendarClock, className: "text-foreground" },
  SESSION: { icon: Hourglass, className: "text-foreground" },
  CUSTOM: { icon: Banknote, className: "text-foreground" },
};

export function CoursePricingModelBadge({ pricingModel }: { pricingModel: CoursePricingModel }) {
  // Defensive fallback only — every real `CourseResponse` always carries a
  // `pricingModel` (Wave 2, non-nullable server-side). This guards against a
  // stale/incomplete mock or cache entry rendering this badge rather than
  // crashing the page, never a value this app itself would produce.
  const resolved = PRICING_MODEL_STYLES[pricingModel] ? pricingModel : "ONE_TIME";
  const { icon: Icon, className } = PRICING_MODEL_STYLES[resolved];
  return (
    <span
      className={cn(
        "inline-flex w-fit items-center gap-1.5 rounded-md border border-border bg-muted px-2 py-0.5 text-xs font-medium",
        className
      )}
    >
      <Icon className="size-3.5" aria-hidden="true" />
      {COURSE_PRICING_MODEL_LABELS[resolved]}
    </span>
  );
}
