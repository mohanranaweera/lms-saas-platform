import { CheckCircle2, CircleDashed, Gift, HelpCircle, Ticket } from "lucide-react";
import { Badge, type badgeVariants } from "@/components/ui/badge";
import type { DataTableColumn } from "@/components/ui/data-table";
import { useCapabilities } from "@/lib/api/my-permissions";
import { CAP } from "@/lib/auth/capabilities";
import type { CourseRosterAccessStatus, CourseRosterEntryResponse } from "@/lib/api/courses";
import type { VariantProps } from "class-variance-authority";

/**
 * Course roster columns and copy shared by the Tenant Admin roster, the Teacher
 * roster and the Tenant Admin teacher-detail Roster tab. Who is listed is decided
 * by the backend only: with View Course Payment Status it returns the active
 * students (paid this month, active Free Card, free course) with a status;
 * without it, every enrolled student with no status. The capability here only
 * picks the column and wording. Color is always paired with an icon and a label
 * (`.claude/rules/ui-ux.md` §4).
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

export function RosterAccessBadge({ status }: { status: CourseRosterAccessStatus | null }) {
  if (status === null) {
    return <span className="text-muted-foreground">—</span>;
  }
  const meta = ACCESS_META[status] ?? { label: status, icon: HelpCircle, variant: "outline" as const };
  const Icon = meta.icon;
  return (
    <Badge variant={meta.variant}>
      <Icon className="size-3.5" aria-hidden="true" />
      {meta.label}
    </Badge>
  );
}

const baseColumns: DataTableColumn<CourseRosterEntryResponse>[] = [
  { key: "name", header: "Name", cell: (row) => row.name, hideOnCard: true },
  { key: "email", header: "Email", cell: (row) => row.email },
];

const statusColumn: DataTableColumn<CourseRosterEntryResponse> = {
  key: "accessStatus",
  header: "Paid access",
  cell: (row) => <RosterAccessBadge status={row.accessStatus} />,
};

/** Columns, heading and empty-state copy for the caller's roster view. */
export function useCourseRosterView() {
  const { has } = useCapabilities({ fetchForAllRoles: true });
  const paymentView = has(CAP.COURSES_VIEW_PAYMENT_STATUS);
  return {
    columns: paymentView ? [...baseColumns, statusColumn] : baseColumns,
    description: paymentView
      ? "Students paid for this month or holding an active Free Card."
      : "Students currently enrolled in this course.",
    emptyState: paymentView
      ? {
          title: "No active students",
          description: "No enrolled student has paid for this month or holds an active Free Card.",
        }
      : { title: "No enrolled students", description: "No student is currently enrolled in this course." },
  };
}
