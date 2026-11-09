import { CheckCircle2, CircleDashed, Gift, HelpCircle, Ticket } from "lucide-react";
import { Badge, type badgeVariants } from "@/components/ui/badge";
import type { DataTableColumn } from "@/components/ui/data-table";
import type { CourseRosterAccessStatus, CourseRosterEntryResponse } from "@/lib/api/courses";
import type { VariantProps } from "class-variance-authority";

/**
 * Course roster columns shared by the Tenant Admin roster, the Teacher roster
 * and the Tenant Admin teacher-detail Roster tab. The backend lists only
 * enrolled students with access right now (paid for the current month, an
 * active Free Card, or a free course); "Paid access" is its display-only
 * `accessStatus`, never computed or filtered here. Color is always paired with an icon and a
 * label (`.claude/rules/ui-ux.md` §4).
 */
const ACCESS_META: Record<
  CourseRosterAccessStatus,
  { label: string; icon: typeof CheckCircle2; variant: VariantProps<typeof badgeVariants>["variant"] }
> = {
  PAID: { label: "Paid", icon: CheckCircle2, variant: "default" },
  FREE_CARD: { label: "Free card", icon: Ticket, variant: "secondary" },
  FREE_COURSE: { label: "Free course", icon: Gift, variant: "secondary" },
  NOT_PAID: { label: "Not paid", icon: CircleDashed, variant: "warning" },
};

export function RosterAccessBadge({ status }: { status: CourseRosterAccessStatus }) {
  const meta = ACCESS_META[status] ?? { label: status, icon: HelpCircle, variant: "outline" as const };
  const Icon = meta.icon;
  return (
    <Badge variant={meta.variant}>
      <Icon className="size-3.5" aria-hidden="true" />
      {meta.label}
    </Badge>
  );
}

export const courseRosterColumns: DataTableColumn<CourseRosterEntryResponse>[] = [
  { key: "name", header: "Name", cell: (row) => row.name, hideOnCard: true },
  { key: "email", header: "Email", cell: (row) => row.email },
  {
    key: "accessStatus",
    header: "Paid access",
    cell: (row) => <RosterAccessBadge status={row.accessStatus} />,
  },
];
