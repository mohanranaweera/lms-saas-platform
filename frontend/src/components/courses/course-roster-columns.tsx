"use client";

import { CheckCircle2, CircleDashed, Gift, HelpCircle, Ticket } from "lucide-react";
import { Badge, type badgeVariants } from "@/components/ui/badge";
import type { DataTableColumn } from "@/components/ui/data-table";
import { useCapabilities } from "@/lib/api/my-permissions";
import { CAP } from "@/lib/auth/capabilities";
import type { CourseRosterAccessStatus, CourseRosterEntryResponse } from "@/lib/api/courses";
import type { VariantProps } from "class-variance-authority";

import { useT, type TranslateFn } from "@/i18n/i18n-provider";
/**
 * Course roster columns and copy shared by the Tenant Admin roster, the Teacher
 * roster and the Tenant Admin teacher-detail Roster tab. Who is listed is decided
 * by the backend only: with View Course Payment Status it returns the active
 * students (paid this month, active Free Card, free course) with a status;
 * without it, every enrolled student with no status. The capability here only
 * picks the column and wording. Color is always paired with an icon and a label
 * (`.claude/rules/ui-ux.md` §4).
 */
const ACCESS_META = (t: TranslateFn): Record<
  CourseRosterAccessStatus,
  { label: string; icon: typeof CheckCircle2; variant: VariantProps<typeof badgeVariants>["variant"] }
> => ({
  PAID: { label: t("courses.roster.paid"), icon: CheckCircle2, variant: "default" },
  FREE_CARD: { label: t("courses.roster.freeCard"), icon: Ticket, variant: "secondary" },
  FREE_COURSE: { label: t("courses.roster.freeCourse"), icon: Gift, variant: "secondary" },
  NOT_PAID: { label: t("courses.roster.notPaid"), icon: CircleDashed, variant: "warning" },
});

export function RosterAccessBadge({ status }: { status: CourseRosterAccessStatus | null }) {
  const t = useT();
  if (status === null) {
    return <span className="text-muted-foreground">—</span>;
  }
  const meta = ACCESS_META(t)[status] ?? { label: status, icon: HelpCircle, variant: "outline" as const };
  const Icon = meta.icon;
  return (
    <Badge variant={meta.variant}>
      <Icon className="size-3.5" aria-hidden="true" />
      {meta.label}
    </Badge>
  );
}

const baseColumns = (t: TranslateFn): DataTableColumn<CourseRosterEntryResponse>[] => [
  { key: "name", header: t("courses.roster.colName"), cell: (row) => row.name, hideOnCard: true },
  { key: "email", header: t("courses.roster.colEmail"), cell: (row) => row.email },
];

const statusColumn = (t: TranslateFn): DataTableColumn<CourseRosterEntryResponse> => ({
  key: "accessStatus",
  header: t("courses.roster.colPaidAccess"),
  cell: (row) => <RosterAccessBadge status={row.accessStatus} />,
});

/** Columns, heading and empty-state copy for the caller's roster view. */
export function useCourseRosterView() {
  const { has } = useCapabilities({ fetchForAllRoles: true });
  const paymentView = has(CAP.COURSES_VIEW_PAYMENT_STATUS);
  const t = useT();
  return {
    columns: paymentView ? [...baseColumns(t), statusColumn(t)] : baseColumns(t),
    description: paymentView
      ? t("courses.roster.studentsPaidForThis")
      : t("courses.roster.currentlyEnrolled"),
    emptyState: paymentView
      ? {
          title: t("courses.roster.noActiveStudents"),
          description: t("courses.roster.noEnrolledStudentHas"),
        }
      : { title: t("courses.roster.noEnrolledStudents"), description: t("courses.roster.noStudentIsCurrently") },
  };
}
