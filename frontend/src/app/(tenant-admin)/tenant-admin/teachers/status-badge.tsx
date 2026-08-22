import { CheckCircle2, Clock, XCircle } from "lucide-react";
import { cn } from "@/lib/utils";
import type { TeacherApprovalStatus } from "@/lib/api/teachers";

/**
 * Mirrors `TeacherResponse.approvalStatus`
 * (`backend/.../usermanagement/teacher/web/dto/TeacherResponse.java`):
 * `PENDING | APPROVED | REJECTED`. Follows the same icon+text pairing
 * convention as `(platform-admin)/platform-admin/tenants/status-badge.tsx` —
 * never color alone, per `.claude/rules/ui-ux.md` §4.
 */
export const TEACHER_APPROVAL_STATUS_LABELS: Record<TeacherApprovalStatus, string> = {
  PENDING: "Pending approval",
  APPROVED: "Approved",
  REJECTED: "Rejected",
};

const STATUS_STYLES: Record<TeacherApprovalStatus, { icon: typeof Clock; className: string }> = {
  PENDING: { icon: Clock, className: "text-muted-foreground" },
  APPROVED: { icon: CheckCircle2, className: "text-foreground" },
  REJECTED: { icon: XCircle, className: "text-destructive" },
};

export function TeacherStatusBadge({ status }: { status: TeacherApprovalStatus }) {
  const { icon: Icon, className } = STATUS_STYLES[status];
  return (
    <span
      className={cn(
        "inline-flex w-fit items-center gap-1.5 rounded-md border border-border bg-muted px-2 py-0.5 text-xs font-medium",
        className
      )}
    >
      <Icon className="size-3.5" aria-hidden="true" />
      {TEACHER_APPROVAL_STATUS_LABELS[status]}
    </span>
  );
}
