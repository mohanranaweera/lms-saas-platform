import { Badge } from "@/components/ui/badge";
import type { CustomDomainStatus } from "@/lib/api/custom-domains";

/**
 * TEN-DOM-01 custom domain status. Text always carries the meaning (never
 * color alone), per the UI rules for status indicators.
 */
const STATUS: Record<CustomDomainStatus, { label: string; variant: "default" | "outline" | "warning" | "destructive" }> = {
  PENDING_VERIFICATION: { label: "Waiting for DNS", variant: "outline" },
  ACTIVE: { label: "Active", variant: "default" },
  DNS_BROKEN: { label: "DNS records missing", variant: "warning" },
  SUSPENDED: { label: "Suspended", variant: "destructive" },
};

export function DomainStatusBadge({ status }: { status: CustomDomainStatus }) {
  const { label, variant } = STATUS[status];
  return <Badge variant={variant}>{label}</Badge>;
}
