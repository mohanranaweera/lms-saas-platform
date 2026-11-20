"use client";

import { AlertTriangle, Ban, CheckCircle2, Clock, RotateCcw, Search, XCircle } from "lucide-react";
import type { VariantProps } from "class-variance-authority";
import { Badge, type badgeVariants } from "@/components/ui/badge";
import type { OrderStatus, PaymentStatus } from "@/lib/api/payments";
import type { LedgerEntryType, PaymentMethod, PaymentOperationalState } from "@/lib/api/ledger";
import type { PaymentSlipStatus, SlipFlagType } from "@/lib/api/payment-slips";

import { useT, type TranslateFn } from "@/i18n/i18n-provider";
import { translateSource } from "@/i18n/translate-source";
/**
 * Status/entry-type badges for the Order & Payment Foundation screens.
 * Follows `(tenant-admin)/tenant-admin/teachers/status-badge.tsx`'s
 * icon+text+color convention — color is never the only signal, per
 * `.claude/rules/ui-ux.md` §4.
 */

type BadgeVariant = VariantProps<typeof badgeVariants>["variant"];

const ORDER_STATUS_META = (t: TranslateFn): Record<
  OrderStatus,
  { label: string; icon: typeof Clock; variant: BadgeVariant }
> => ({
  PLACED: { label: t("payments.statusBadges.orderPlaced"), icon: Clock, variant: "outline" },
  PENDING: { label: t("payments.statusBadges.awaitingPayment"), icon: Clock, variant: "outline" },
});

export function OrderStatusBadge({ status }: { status: OrderStatus }) {
  const t = useT();
  const meta = ORDER_STATUS_META(t)[status];
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
const PAYMENT_STATUS_META = (t: TranslateFn): Record<
  PaymentStatus,
  { label: string; icon: typeof Clock; variant: BadgeVariant }
> => ({
  PENDING: { label: t("payments.statusBadges.pending"), icon: Clock, variant: "outline" },
  CONFIRMED: { label: t("payments.statusBadges.confirmed"), icon: CheckCircle2, variant: "default" },
  REJECTED: { label: t("payments.statusBadges.rejected"), icon: XCircle, variant: "destructive" },
  REFUNDED: { label: t("payments.statusBadges.refunded"), icon: RotateCcw, variant: "secondary" },
});

export function PaymentStatusBadge({ status }: { status: PaymentStatus }) {
  const t = useT();
  const meta = PAYMENT_STATUS_META(t)[status];
  const Icon = meta.icon;
  return (
    <Badge variant={meta.variant}>
      <Icon className="size-3.5" aria-hidden="true" />
      {meta.label}
    </Badge>
  );
}

const LEDGER_ENTRY_TYPE_META = (t: TranslateFn): Record<
  LedgerEntryType,
  { label: string; icon: typeof Clock; variant: BadgeVariant }
> => ({
  PAYMENT_CONFIRMED: { label: t("payments.statusBadges.paymentConfirmed"), icon: CheckCircle2, variant: "default" },
  REFUND: { label: t("payments.statusBadges.refund"), icon: RotateCcw, variant: "secondary" },
});

export function LedgerEntryTypeBadge({ entryType }: { entryType: LedgerEntryType }) {
  const t = useT();
  const meta = LEDGER_ENTRY_TYPE_META(t)[entryType];
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
const SLIP_STATUS_META = (t: TranslateFn): Record<
  PaymentSlipStatus,
  { label: string; icon: typeof Clock; variant: BadgeVariant }
> => ({
  SUBMITTED: { label: t("payments.statusBadges.submittedUnderReview"), icon: Clock, variant: "outline" },
  UNDER_REVIEW: { label: t("payments.statusBadges.underReview"), icon: Clock, variant: "secondary" },
  APPROVED: { label: t("payments.statusBadges.approved"), icon: CheckCircle2, variant: "default" },
  REJECTED: { label: t("payments.statusBadges.rejected"), icon: XCircle, variant: "destructive" },
});

export function SlipStatusBadge({ status }: { status: PaymentSlipStatus }) {
  const t = useT();
  const meta = SLIP_STATUS_META(t)[status];
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
  const t = useT();
  return (
    <Badge variant="warning">
      <AlertTriangle className="size-3.5" aria-hidden="true" />
      {translateSource(t, SLIP_FLAG_TYPE_LABEL[flagType])}
    </Badge>
  );
}

/**
 * Wave 6 §4/§5 — `PaymentOperationalState` badge, a genuinely distinct state
 * machine from `PaymentStatusBadge`/`SlipStatusBadge` above (it's a computed
 * projection across order/payment/slip/refund, never a raw column value), so
 * it gets its own `Record`/meta table rather than reusing either — matching
 * this file's established "one state machine, one badge component" pattern.
 * Icon + text + color, never color alone, per `.claude/rules/ui-ux.md` §4.
 */
const PAYMENT_OPERATIONAL_STATE_META = (t: TranslateFn): Record<
  PaymentOperationalState,
  { label: string; icon: typeof Clock; variant: BadgeVariant }
> => ({
  UNPAID: { label: t("payments.statusBadges.unpaid"), icon: Ban, variant: "outline" },
  PENDING: { label: t("payments.statusBadges.pending"), icon: Clock, variant: "outline" },
  UNDER_REVIEW: { label: t("payments.statusBadges.underReview"), icon: Search, variant: "secondary" },
  PAID: { label: t("payments.statusBadges.paid"), icon: CheckCircle2, variant: "default" },
  REJECTED: { label: t("payments.statusBadges.rejected"), icon: XCircle, variant: "destructive" },
  REFUNDED: { label: t("payments.statusBadges.refunded"), icon: RotateCcw, variant: "secondary" },
});

export function PaymentOperationalStateBadge({ state }: { state: PaymentOperationalState }) {
  const t = useT();
  const meta = PAYMENT_OPERATIONAL_STATE_META(t)[state];
  const Icon = meta.icon;
  return (
    <Badge variant={meta.variant}>
      <Icon className="size-3.5" aria-hidden="true" />
      {meta.label}
    </Badge>
  );
}

/**
 * Wave 6 §4/§10 judgment call 2 — `PaymentMethod` display label. A plain
 * label, not a colored badge: method is informational (how payment was
 * made), never itself a state that needs color-coded urgency the way
 * `PaymentOperationalStateBadge` does.
 */
export const PAYMENT_METHOD_LABEL: Record<PaymentMethod, string> = {
  GATEWAY: "Gateway",
  MANUAL_SLIP: "Manual bank transfer",
  FREE: "Free enrollment",
  STAFF_GRANTED: "Staff granted",
  MANUAL_RECEIPT: "Recorded by staff",
};
