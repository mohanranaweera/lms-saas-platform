import { Ban, CheckCircle2, type LucideIcon } from "lucide-react";
import { cn } from "@/lib/utils";

/**
 * Mirrors `identityaccessservice.domain.AccountStatus` (`ACTIVE | SUSPENDED`,
 * see `StaffService`/`TenantUserSummary`, both use Java enum `.name()`, i.e.
 * upper-case).
 */
type KnownStaffStatus = "ACTIVE" | "SUSPENDED";

const STATUS_LABELS: Record<KnownStaffStatus, string> = {
  ACTIVE: "Active",
  SUSPENDED: "Suspended",
};

const STATUS_STYLES: Record<KnownStaffStatus, { icon: LucideIcon; className: string }> = {
  ACTIVE: { icon: CheckCircle2, className: "text-foreground" },
  SUSPENDED: { icon: Ban, className: "text-destructive" },
};

function isKnownStatus(value: string): value is KnownStaffStatus {
  return value === "ACTIVE" || value === "SUSPENDED";
}

/** Never color alone (`.claude/rules/ui-ux.md` §4) — status always pairs an
 * icon with its text label. An unrecognized status still renders the raw
 * value rather than throwing. */
export function StaffStatusBadge({ status }: { status: string }) {
  const known = isKnownStatus(status);
  const { icon: Icon, className } = known
    ? STATUS_STYLES[status]
    : { icon: CheckCircle2, className: "text-muted-foreground" };
  const label = known ? STATUS_LABELS[status] : status;

  return (
    <span
      className={cn(
        "inline-flex w-fit items-center gap-1.5 rounded-md border border-border bg-muted px-2 py-0.5 text-xs font-medium",
        className
      )}
    >
      <Icon className="size-3.5" aria-hidden="true" />
      {label}
    </span>
  );
}
