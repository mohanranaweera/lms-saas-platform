import { Ban, CheckCircle2, HelpCircle, Mail } from "lucide-react";
import { Badge, type badgeVariants } from "@/components/ui/badge";
import type { VariantProps } from "class-variance-authority";

/**
 * Renders `StaffResponse.status` (`INVITED | ACTIVE | SUSPENDED`) as an
 * accessible status chip: color is always paired with an icon and a text
 * label, never color alone (`.claude/rules/ui-ux.md` §4).
 */
const STATUS_META: Record<
  string,
  { label: string; icon: typeof CheckCircle2; variant: VariantProps<typeof badgeVariants>["variant"] }
> = {
  ACTIVE: { label: "Active", icon: CheckCircle2, variant: "default" },
  INVITED: { label: "Invited", icon: Mail, variant: "secondary" },
  SUSPENDED: { label: "Suspended", icon: Ban, variant: "destructive" },
};

export function StaffStatusBadge({ status }: { status: string }) {
  const meta = STATUS_META[status] ?? { label: status, icon: HelpCircle, variant: "outline" as const };
  const Icon = meta.icon;
  return (
    <Badge variant={meta.variant} data-testid="staff-status-badge">
      <Icon className="size-3.5" aria-hidden="true" />
      {meta.label}
    </Badge>
  );
}
