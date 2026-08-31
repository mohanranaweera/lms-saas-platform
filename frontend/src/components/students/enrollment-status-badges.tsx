import { CheckCircle2, Circle, Clock, XCircle } from "lucide-react";
import type { VariantProps } from "class-variance-authority";
import { Badge, type badgeVariants } from "@/components/ui/badge";
import type {
  EnrollmentAccessStateType,
  ReactivationRequestStatus,
} from "@/lib/api/enrollments";

/**
 * Status badges for MVP-012 "Enrollment and Course Access" screens (My
 * Courses, the reactivation-request submit/history screens, and the Tenant
 * Admin Reactivation Approvals queue/detail). Follows
 * `components/payments/status-badges.tsx`'s icon+text+color convention
 * exactly — color is never the only signal, per `.claude/rules/ui-ux.md` §4.
 */

type BadgeVariant = VariantProps<typeof badgeVariants>["variant"];

const ACCESS_STATE_META: Record<
  EnrollmentAccessStateType,
  { label: string; icon: typeof Clock; variant: BadgeVariant }
> = {
  NEVER_ENROLLED: { label: "Never enrolled", icon: Circle, variant: "outline" },
  ACTIVE: { label: "Active", icon: CheckCircle2, variant: "default" },
  EXPIRED: { label: "Expired", icon: XCircle, variant: "destructive" },
};

/** `EnrollmentAccessStateType` badge — `NEVER_ENROLLED` | `ACTIVE` | `EXPIRED`. */
export function AccessStateBadge({ state }: { state: EnrollmentAccessStateType }) {
  const meta = ACCESS_STATE_META[state];
  const Icon = meta.icon;
  return (
    <Badge variant={meta.variant}>
      <Icon className="size-3.5" aria-hidden="true" />
      {meta.label}
    </Badge>
  );
}

/**
 * `ReactivationRequestStatus` is a structurally distinct, one-directional
 * state machine (`SUBMITTED -> APPROVED|REJECTED`, no `UNDER_REVIEW` step) —
 * deliberately NOT merged into `SlipStatusBadge`'s union, so a reactivation
 * request's "Submitted" is never visually or semantically confused with a
 * payment slip's own "Submitted".
 */
const REACTIVATION_STATUS_META: Record<
  ReactivationRequestStatus,
  { label: string; icon: typeof Clock; variant: BadgeVariant }
> = {
  SUBMITTED: { label: "Submitted", icon: Clock, variant: "outline" },
  APPROVED: { label: "Approved", icon: CheckCircle2, variant: "default" },
  REJECTED: { label: "Rejected", icon: XCircle, variant: "destructive" },
};

/** `ReactivationRequestStatus` badge — `SUBMITTED` | `APPROVED` | `REJECTED`. */
export function ReactivationStatusBadge({ status }: { status: ReactivationRequestStatus }) {
  const meta = REACTIVATION_STATUS_META[status];
  const Icon = meta.icon;
  return (
    <Badge variant={meta.variant}>
      <Icon className="size-3.5" aria-hidden="true" />
      {meta.label}
    </Badge>
  );
}
