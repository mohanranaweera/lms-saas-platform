import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import type { HistoryStatus } from "@/lib/api/staff-payments";
import type { FreeCardStatus } from "@/lib/api/free-cards";

const HISTORY: Record<HistoryStatus, { label: string; className: string }> = {
  RECEIVED: {
    label: "Received",
    className: "bg-green-100 text-green-800 dark:bg-green-950 dark:text-green-200",
  },
  PENDING_SLIP: {
    label: "Pending slip",
    className: "bg-amber-100 text-amber-900 dark:bg-amber-950 dark:text-amber-200",
  },
  PENDING_ONLINE: {
    label: "Pending online",
    className: "bg-sky-100 text-sky-900 dark:bg-sky-950 dark:text-sky-200",
  },
  REJECTED: {
    label: "Rejected",
    className: "bg-destructive/10 text-destructive",
  },
};

/**
 * One distinct badge per server-derived history status - never a generic
 * "Paid". Text always carries the meaning (color is decoration only).
 */
export function HistoryStatusBadge({ status }: { status: HistoryStatus }) {
  const entry = HISTORY[status] ?? { label: status, className: "" };
  return (
    <Badge variant="outline" className={cn("border-transparent", entry.className)} data-testid="history-status">
      {entry.label}
    </Badge>
  );
}

const FREE_CARD: Record<FreeCardStatus, { label: string; className: string }> = {
  SCHEDULED: { label: "Scheduled", className: "bg-sky-100 text-sky-900 dark:bg-sky-950 dark:text-sky-200" },
  ACTIVE: { label: "Active", className: "bg-green-100 text-green-800 dark:bg-green-950 dark:text-green-200" },
  EXPIRED: { label: "Expired", className: "bg-muted text-muted-foreground" },
  REVOKED: { label: "Revoked", className: "bg-destructive/10 text-destructive" },
};

export function FreeCardStatusBadge({ status }: { status: FreeCardStatus }) {
  const entry = FREE_CARD[status] ?? { label: status, className: "" };
  return (
    <Badge variant="outline" className={cn("border-transparent", entry.className)} data-testid="free-card-status">
      {entry.label}
    </Badge>
  );
}
