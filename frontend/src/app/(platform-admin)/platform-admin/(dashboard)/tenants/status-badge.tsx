import { Ban, CheckCircle2, Clock, FlaskConical, XCircle, XOctagon } from "lucide-react";
import { cn } from "@/lib/utils";
import type { TenantStatus } from "@/lib/api/platform-admin-tenants";

/**
 * Re-exported from `lib/api/platform-admin-tenants.ts` (the canonical
 * definition — see its own doc comment) so every existing `import { ...,
 * type TenantStatus } from "./status-badge"` call site keeps working
 * unchanged; this file must not be the source of truth for a type the `lib/`
 * API layer also needs, since `lib/` must never import from `app/`.
 */
export type { TenantStatus };

export const TENANT_STATUS_LABELS: Record<TenantStatus, string> = {
  PENDING_APPROVAL: "Pending approval",
  TRIAL: "Trial",
  ACTIVE: "Active",
  SUSPENDED: "Suspended",
  CANCELLED: "Cancelled",
  REJECTED: "Rejected",
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
  PENDING_APPROVAL: { icon: Clock, className: "text-muted-foreground" },
  TRIAL: { icon: FlaskConical, className: "text-muted-foreground" },
  ACTIVE: { icon: CheckCircle2, className: "text-foreground" },
  SUSPENDED: { icon: Ban, className: "text-destructive" },
  CANCELLED: { icon: XCircle, className: "text-destructive" },
  REJECTED: { icon: XOctagon, className: "text-destructive" },
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
