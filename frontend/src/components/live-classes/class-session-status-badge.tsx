import { Ban, CalendarClock, CheckCircle2, Radio } from "lucide-react";
import { Badge, type badgeVariants } from "@/components/ui/badge";
import type { VariantProps } from "class-variance-authority";
import type { ClassSessionStatus } from "@/lib/api/class-sessions";

/**
 * Live class session status chip — icon + text, color never the only signal
 * (`.claude/rules/ui-ux.md` §4). Mirrors `components/payments/status-badges.tsx`'s
 * established `Record<Status, meta>` + `Badge` composition.
 */

type BadgeVariant = VariantProps<typeof badgeVariants>["variant"];

export const CLASS_SESSION_STATUS_LABELS: Record<ClassSessionStatus, string> = {
  SCHEDULED: "Scheduled",
  LIVE: "Live",
  COMPLETED: "Completed",
  CANCELLED: "Cancelled",
};

const STATUS_META: Record<ClassSessionStatus, { icon: typeof CalendarClock; variant: BadgeVariant }> = {
  SCHEDULED: { icon: CalendarClock, variant: "outline" },
  LIVE: { icon: Radio, variant: "destructive" },
  COMPLETED: { icon: CheckCircle2, variant: "secondary" },
  CANCELLED: { icon: Ban, variant: "outline" },
};

export function ClassSessionStatusBadge({ status }: { status: ClassSessionStatus }) {
  const { icon: Icon, variant } = STATUS_META[status];
  return (
    <Badge variant={variant}>
      <Icon className="size-3.5" aria-hidden="true" />
      {CLASS_SESSION_STATUS_LABELS[status]}
    </Badge>
  );
}
