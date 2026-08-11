import { Ban, CheckCircle2, Clock, FlaskConical, XCircle, XOctagon } from "lucide-react";
import { cn } from "@/lib/utils";

/**
 * Mirrors the backend's `tenant.status` CHECK-constrained enum
 * (`pending_approval | trial | active | suspended | cancelled | rejected`), see
 * `backend/src/main/resources/db/migration/V2__create_tenant_table.sql`.
 */
export type TenantStatus =
  | "pending_approval"
  | "trial"
  | "active"
  | "suspended"
  | "cancelled"
  | "rejected";

export const TENANT_STATUS_LABELS: Record<TenantStatus, string> = {
  pending_approval: "Pending approval",
  trial: "Trial",
  active: "Active",
  suspended: "Suspended",
  cancelled: "Cancelled",
  rejected: "Rejected",
};

/**
 * Status colour/icon pairing uses only existing design tokens (`text-foreground`,
 * `text-muted-foreground`, `border-border`, `bg-muted`, `text-destructive`) — never
 * color alone, always paired with an icon + text label per `.claude/rules/ui-ux.md`
 * §4. A proper Status Chip color/variant spec — including a dedicated `Trial`
 * variant — is an open design-system question (module plan §21 item 12); do not
 * invent new tokens/colors here to fill that gap, `trial` intentionally reuses the
 * neutral treatment with a distinct icon until that's resolved upstream.
 */
const STATUS_STYLES: Record<TenantStatus, { icon: typeof Clock; className: string }> = {
  pending_approval: { icon: Clock, className: "text-muted-foreground" },
  trial: { icon: FlaskConical, className: "text-muted-foreground" },
  active: { icon: CheckCircle2, className: "text-foreground" },
  suspended: { icon: Ban, className: "text-destructive" },
  cancelled: { icon: XCircle, className: "text-destructive" },
  rejected: { icon: XOctagon, className: "text-destructive" },
};

export function StatusBadge({ status }: { status: TenantStatus }) {
  const { icon: Icon, className } = STATUS_STYLES[status];
  return (
    <span
      className={cn(
        "inline-flex w-fit items-center gap-1.5 rounded-md border border-border bg-muted px-2 py-0.5 text-xs font-medium",
        className
      )}
    >
      <Icon className="size-3.5" aria-hidden="true" />
      {TENANT_STATUS_LABELS[status]}
    </span>
  );
}
