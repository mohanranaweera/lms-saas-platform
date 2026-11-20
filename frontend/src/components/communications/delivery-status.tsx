import { Badge } from "@/components/ui/badge";
import type { DeliveryChannel, DeliveryStatus, DeliveryView } from "@/lib/api/communications";

const STATUS_LABEL: Record<DeliveryStatus, string> = {
  PENDING: "Queued",
  SENDING: "Sending",
  SENT: "Sent",
  FAILED: "Failed",
  SKIPPED: "Skipped",
};

const STATUS_VARIANT: Record<DeliveryStatus, "default" | "secondary" | "destructive" | "warning" | "outline"> = {
  PENDING: "outline",
  SENDING: "outline",
  SENT: "secondary",
  FAILED: "destructive",
  SKIPPED: "warning",
};

/** Delivery status - text label always present (color is never the only signal). */
export function DeliveryStatusBadge({ status }: { status: DeliveryStatus }) {
  return <Badge variant={STATUS_VARIANT[status]}>{STATUS_LABEL[status]}</Badge>;
}

/** Plain-language reasons for the backend's fixed delivery codes. */
const REASONS: Record<string, string> = {
  NO_ADDRESS: "No email address or phone number on record",
  NO_CONSENT: "The recipient has turned this channel off (WhatsApp needs opt-in)",
  USER_INACTIVE: "The account is suspended or not active",
  CHANNEL_NOT_AVAILABLE: "The channel was switched off before the message was sent",
  PROVIDER_TRANSIENT: "The provider was temporarily unavailable",
  PROVIDER_REJECTED: "The provider rejected the message",
  PROVIDER_AUTH_REJECTED: "The provider refused the account's credentials - check Settings > Messaging",
  INVALID_DESTINATION: "The phone number on record is not a valid mobile number",
  EMAIL_TRANSIENT: "The email server was temporarily unavailable",
  STUCK_IN_SENDING: "The send could not be confirmed and was not retried",
  INTERNAL_ERROR: "Something went wrong while sending",
};

export function deliveryReason(code: string | null): string | null {
  if (!code) return null;
  return REASONS[code] ?? code;
}

/**
 * What the SMS/WhatsApp provider reported after accepting the message
 * (MSG-PROV-01). Meta WhatsApp reports delivered/read via its webhook; Text.lk
 * sends no delivery reports, so a sent SMS stays "Accepted by provider".
 */
export function providerStatusText(
  row: Pick<DeliveryView, "channel" | "status" | "providerStatus" | "providerErrorCode">
): string | null {
  const external: DeliveryChannel[] = ["SMS", "WHATSAPP"];
  if (row.status !== "SENT" || !external.includes(row.channel)) return null;
  switch (row.providerStatus) {
    case "DELIVERED":
      return "Delivered to phone";
    case "READ":
      return "Read";
    case "FAILED":
      return `Not delivered (provider code ${row.providerErrorCode ?? "unknown"})`;
    default:
      return "Accepted by provider";
  }
}
