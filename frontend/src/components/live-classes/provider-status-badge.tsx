import { AlertTriangle, CheckCircle2, Clock } from "lucide-react";
import { Badge, type badgeVariants } from "@/components/ui/badge";
import type { VariantProps } from "class-variance-authority";
import type { ClassSessionProviderStatus } from "@/lib/api/class-sessions";

/**
 * Meeting-provider provisioning status chip. `FAILED` deliberately uses the
 * `warning` variant (not `destructive`) — a provisioning failure is a
 * retryable, actionable state, not a terminal error — but is still visually
 * distinct from `PENDING`/`PROVISIONED` so a stuck provisioning is obvious in
 * a list, never buried (Wave 4 plan §5's explicit Tenant Admin requirement).
 */

type BadgeVariant = VariantProps<typeof badgeVariants>["variant"];

export const PROVIDER_STATUS_LABELS: Record<ClassSessionProviderStatus, string> = {
  PENDING: "Provisioning…",
  PROVISIONED: "Meeting ready",
  FAILED: "Provisioning failed",
};

const PROVIDER_STATUS_META: Record<
  ClassSessionProviderStatus,
  { icon: typeof Clock; variant: BadgeVariant }
> = {
  PENDING: { icon: Clock, variant: "outline" },
  PROVISIONED: { icon: CheckCircle2, variant: "secondary" },
  FAILED: { icon: AlertTriangle, variant: "warning" },
};

export function ProviderStatusBadge({ status }: { status: ClassSessionProviderStatus }) {
  const { icon: Icon, variant } = PROVIDER_STATUS_META[status];
  return (
    <Badge variant={variant}>
      <Icon className="size-3.5" aria-hidden="true" />
      {PROVIDER_STATUS_LABELS[status]}
    </Badge>
  );
}
