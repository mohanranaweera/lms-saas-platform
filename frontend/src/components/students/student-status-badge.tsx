import { Ban, CheckCircle2, HelpCircle } from "lucide-react";
import { Badge, type badgeVariants } from "@/components/ui/badge";
import type { VariantProps } from "class-variance-authority";

/**
 * Renders `StudentResponse.status` (`ACTIVE | SUSPENDED`, mirroring
 * `tenant_user.status` — see `backend StudentResponse`/`StudentController`
 * javadoc) as an accessible status chip: color is always paired with an icon
 * and a text label, never color alone (`.claude/rules/ui-ux.md` §4).
 *
 * Shared under `components/students/` (not co-located in a single route
 * group) because both the Tenant Admin student list/detail screens
 * (`app/(tenant-admin)/tenant-admin/students/**`) and the Student's own
 * profile page (`app/(student)/student/profile`) render this same status —
 * per `.claude/rules/frontend.md`, a component needed by more than one role
 * group is extracted to a shared directory rather than imported across route
 * groups.
 */
const STATUS_META: Record<
  string,
  { label: string; icon: typeof CheckCircle2; variant: VariantProps<typeof badgeVariants>["variant"] }
> = {
  ACTIVE: { label: "Active", icon: CheckCircle2, variant: "default" },
  SUSPENDED: { label: "Suspended", icon: Ban, variant: "destructive" },
};

export function StudentStatusBadge({ status }: { status: string }) {
  const meta = STATUS_META[status] ?? { label: status, icon: HelpCircle, variant: "outline" as const };
  const Icon = meta.icon;
  return (
    <Badge variant={meta.variant}>
      <Icon className="size-3.5" aria-hidden="true" />
      {meta.label}
    </Badge>
  );
}
