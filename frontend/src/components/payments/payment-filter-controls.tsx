"use client";

import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { PAYMENT_METHOD_LABEL } from "@/components/payments/status-badges";
import type { PaymentMethod, PaymentOperationalState } from "@/lib/api/ledger";

/**
 * Shared status/method filter controls for the ledger-derived payment
 * dashboards (Tenant Admin, Platform Admin) — Wave 6 §4/§5. Mirrors
 * `tenant-admin/payments/slip-review/page.tsx`'s established
 * `"all"`-client-sentinel-mapped-to-`undefined` Select pattern (there is no
 * backend "ALL" enum value for either filter).
 */

const STATUS_OPTIONS: Array<{ value: "all" | PaymentOperationalState; label: string }> = [
  { value: "all", label: "All statuses" },
  { value: "UNPAID", label: "Unpaid" },
  { value: "PENDING", label: "Pending" },
  { value: "UNDER_REVIEW", label: "Under review" },
  { value: "PAID", label: "Paid" },
  { value: "REJECTED", label: "Rejected" },
  { value: "REFUNDED", label: "Refunded" },
];

const METHOD_OPTIONS: Array<{ value: "all" | PaymentMethod; label: string }> = [
  { value: "all", label: "All methods" },
  { value: "GATEWAY", label: PAYMENT_METHOD_LABEL.GATEWAY },
  { value: "MANUAL_SLIP", label: PAYMENT_METHOD_LABEL.MANUAL_SLIP },
  { value: "FREE", label: PAYMENT_METHOD_LABEL.FREE },
  { value: "STAFF_GRANTED", label: PAYMENT_METHOD_LABEL.STAFF_GRANTED },
];

export interface PaymentFilterControlsProps {
  idPrefix: string;
  status: PaymentOperationalState | undefined;
  method: PaymentMethod | undefined;
  onStatusChange: (status: PaymentOperationalState | undefined) => void;
  onMethodChange: (method: PaymentMethod | undefined) => void;
  disabled?: boolean;
}

export function PaymentFilterControls({
  idPrefix,
  status,
  method,
  onStatusChange,
  onMethodChange,
  disabled,
}: PaymentFilterControlsProps) {
  return (
    <div className="flex flex-col gap-4 sm:flex-row">
      <div className="flex flex-col gap-1.5 sm:w-56">
        <Label htmlFor={`${idPrefix}-status`}>Status</Label>
        <Select
          value={status ?? "all"}
          onValueChange={(value) =>
            onStatusChange(value === "all" ? undefined : (value as PaymentOperationalState))
          }
          disabled={disabled}
        >
          <SelectTrigger id={`${idPrefix}-status`} className="w-full">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {STATUS_OPTIONS.map((option) => (
              <SelectItem key={option.value} value={option.value}>
                {option.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      <div className="flex flex-col gap-1.5 sm:w-56">
        <Label htmlFor={`${idPrefix}-method`}>Method</Label>
        <Select
          value={method ?? "all"}
          onValueChange={(value) =>
            onMethodChange(value === "all" ? undefined : (value as PaymentMethod))
          }
          disabled={disabled}
        >
          <SelectTrigger id={`${idPrefix}-method`} className="w-full">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {METHOD_OPTIONS.map((option) => (
              <SelectItem key={option.value} value={option.value}>
                {option.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>
    </div>
  );
}
