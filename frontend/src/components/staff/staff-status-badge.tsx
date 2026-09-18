import { Ban, CheckCircle2, HelpCircle } from "lucide-react";
import { Badge, type badgeVariants } from "@/components/ui/badge";
import type { VariantProps } from "class-variance-authority";

/**
 * Renders `StaffResponse.status` (`ACTIVE | SUSPENDED`, mirroring
 * `tenant_user.status`) as an accessible status chip: color is always paired
 * with an icon and a text label, never color alone (`.claude/rules/ui-ux.md`
 * §4). Deliberately its own small component under `components/staff/` rather
 * than reusing `components/students/student-status-badge.tsx` — same shape,
 * different domain; this codebase's own convention is one status-badge
 * component per domain noun (see that file's own doc comment for why it's
 * scoped to `components/students/`), not a cross-domain import.
 */
const STATUS_META: Record<
  string,
  { label: string; icon: typeof CheckCircle2; variant: VariantProps<typeof badgeVariants>["variant"] }
> = {
  ACTIVE: { label: "Active", icon: CheckCircle2, variant: "default" },
  SUSPENDED: { label: "Suspended", icon: Ban, variant: "destructive" },
};

export function StaffStatusBadge({ status }: { status: string }) {
  const meta = STATUS_META[status] ?? { label: status, icon: HelpCircle, variant: "outline" as const };
  const Icon = meta.icon;
  return (
    <Badge variant={meta.variant}>
      <Icon className="size-3.5" aria-hidden="true" />
      {meta.label}
    </Badge>
  );
}
