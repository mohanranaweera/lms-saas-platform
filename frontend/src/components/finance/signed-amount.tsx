import { formatMoney } from "@/lib/format";

/** Negative figures carry a text "−" prefix and a screen-reader label — never color alone. */
export function SignedAmount({ value, currency }: { value: number; currency?: string | null }) {
  if (value < 0) {
    return (
      <span className="text-destructive">
        −{formatMoney(Math.abs(value), currency)} <span className="sr-only">(negative)</span>
      </span>
    );
  }
  return <span>{formatMoney(value, currency)}</span>;
}
