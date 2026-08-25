import { AlertTriangle, CheckCircle2, Clock, RotateCcw, XCircle } from "lucide-react";
import type { VariantProps } from "class-variance-authority";
import { Badge, type badgeVariants } from "@/components/ui/badge";
import type { OrderStatus, PaymentStatus } from "@/lib/api/payments";
import type { LedgerEntryType } from "@/lib/api/ledger";
import type { PaymentSlipStatus, SlipFlagType } from "@/lib/api/payment-slips";

/**
 * Status/entry-type badges for the Order & Payment Foundation screens.
 * Follows `(tenant-admin)/tenant-admin/teachers/status-badge.tsx`'s
 * icon+text+color convention — color is never the only signal, per
 * `.claude/rules/ui-ux.md` §4.
 */

type BadgeVariant = VariantProps<typeof badgeVariants>["variant"];

const ORDER_STATUS_META: Record<
  OrderStatus,
  { label: string; icon: typeof Clock; variant: BadgeVariant }
> = {
  PLACED: { label: "Order placed", icon: Clock, variant: "outline" },
  PENDING: { label: "Awaiting payment", icon: Clock, variant: "outline" },
};

export function OrderStatusBadge({ status }: { status: OrderStatus }) {
  const meta = ORDER_STATUS_META[status];
  const Icon = meta.icon;
  return (
    <Badge variant={meta.variant}>
      <Icon className="size-3.5" aria-hidden="true" />
      {meta.label}
    </Badge>
  );
}

/**
 * `payment.status` never actually reaches `REFUNDED` in this backend — a
 * refund is a new `payment_refund` row + a `REFUND` ledger entry, never a
 * mutation of the original terminal `Payment` row (`.claude/rules/payments.md`
 * §1/§4). The `REFUNDED` entry below exists only as a defensive fallback for
 * the declared response type, not because this UI expects to ever render it.
 */
const PAYMENT_STATUS_META: Record<
  PaymentStatus,
  { label: string; icon: typeof Clock; variant: BadgeVariant }
> = {
  PENDING: { label: "Pending", icon: Clock, variant: "outline" },
  CONFIRMED: { label: "Confirmed", icon: CheckCircle2, variant: "default" },
  REJECTED: { label: "Rejected", icon: XCircle, variant: "destructive" },
  REFUNDED: { label: "Refunded", icon: RotateCcw, variant: "secondary" },
};

export function PaymentStatusBadge({ status }: { status: PaymentStatus }) {
  const meta = PAYMENT_STATUS_META[status];
  const Icon = meta.icon;
  return (
    <Badge variant={meta.variant}>
      <Icon className="size-3.5" aria-hidden="true" />
      {meta.label}
    </Badge>
  );
}

const LEDGER_ENTRY_TYPE_META: Record<
  LedgerEntryType,
  { label: string; icon: typeof Clock; variant: BadgeVariant }
> = {
  PAYMENT_CONFIRMED: { label: "Payment confirmed", icon: CheckCircle2, variant: "default" },
  REFUND: { label: "Refund", icon: RotateCcw, variant: "secondary" },
};

export function LedgerEntryTypeBadge({ entryType }: { entryType: LedgerEntryType }) {
  const meta = LEDGER_ENTRY_TYPE_META[entryType];
  const Icon = meta.icon;
  return (
    <Badge variant={meta.variant}>
      <Icon className="size-3.5" aria-hidden="true" />
      {meta.label}
    </Badge>
  );
}

/**
 * `PaymentSlip.status` is a structurally distinct, one-directional state
 * machine from `PaymentStatus` (`.claude/rules/payments.md` §2) — deliberately
 * NOT merged into `PaymentStatusBadge`'s union, so "Submitted" can never be
 * visually or semantically confused with a gateway payment's "Confirmed".
 */
const SLIP_STATUS_META: Record<
  PaymentSlipStatus,
  { label: string; icon: typeof Clock; variant: BadgeVariant }
> = {
  SUBMITTED: { label: "Submitted — under review", icon: Clock, variant: "outline" },
  UNDER_REVIEW: { label: "Under review", icon: Clock, variant: "secondary" },
  APPROVED: { label: "Approved", icon: CheckCircle2, variant: "default" },
  REJECTED: { label: "Rejected", icon: XCircle, variant: "destructive" },
};

export function SlipStatusBadge({ status }: { status: PaymentSlipStatus }) {
  const meta = SLIP_STATUS_META[status];
  const Icon = meta.icon;
  return (
    <Badge variant={meta.variant}>
      <Icon className="size-3.5" aria-hidden="true" />
      {meta.label}
    </Badge>
  );
}

const SLIP_FLAG_TYPE_LABEL: Record<SlipFlagType, string> = {
  DUPLICATE_REFERENCE: "Duplicate reference",
  DUPLICATE_IMAGE_HASH: "Duplicate image",
};

/**
 * Renders a single backend-computed duplicate flag verbatim — this component
 * (and every caller of it) performs zero duplicate-detection logic itself,
 * per spec 25's explicit UI-state note. Uses the `warning` badge variant
 * (amber-hued, distinct from `destructive`) since a flag is auto-detected,
 * reviewed metadata, not an outright rejection.
 */
export function SlipFlagBadge({ flagType }: { flagType: SlipFlagType }) {
  return (
    <Badge variant="warning">
      <AlertTriangle className="size-3.5" aria-hidden="true" />
      {SLIP_FLAG_TYPE_LABEL[flagType]}
    </Badge>
  );
}
