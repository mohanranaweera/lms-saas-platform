import { CheckCircle2, Lock, PencilLine } from "lucide-react";
import { cn } from "@/lib/utils";
import type { CourseStatus } from "@/lib/api/courses";

/**
 * Course status chip — never color alone (`.claude/rules/ui-ux.md` §4):
 * every status pairs an icon with a text label. Token usage mirrors
 * `app/(platform-admin)/platform-admin/tenants/status-badge.tsx`'s existing
 * precedent (no new design tokens invented here).
 */
export const COURSE_STATUS_LABELS: Record<CourseStatus, string> = {
  DRAFT: "Draft",
  PRIVATE: "Private",
  PUBLIC: "Public",
};

const STATUS_STYLES: Record<CourseStatus, { icon: typeof CheckCircle2; className: string }> = {
  DRAFT: { icon: PencilLine, className: "text-muted-foreground" },
  PRIVATE: { icon: Lock, className: "text-muted-foreground" },
  PUBLIC: { icon: CheckCircle2, className: "text-foreground" },
};

export function CourseStatusBadge({ status }: { status: CourseStatus }) {
  const { icon: Icon, className } = STATUS_STYLES[status];
  return (
    <span
      className={cn(
        "inline-flex w-fit items-center gap-1.5 rounded-md border border-border bg-muted px-2 py-0.5 text-xs font-medium",
        className
      )}
    >
      <Icon className="size-3.5" aria-hidden="true" />
      {COURSE_STATUS_LABELS[status]}
    </span>
  );
}
