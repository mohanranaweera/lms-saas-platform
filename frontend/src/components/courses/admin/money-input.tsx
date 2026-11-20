"use client";

import type { Ref } from "react";
import { Input } from "@/components/ui/input";

const MONEY_PATTERN = /^\d+(\.\d{1,2})?$/;

/** Amount input with an "Rs." prefix; formats to 2 decimals on blur. */
export function MoneyInput({
  id,
  value,
  onChange,
  onBlur,
  invalid,
  describedBy: describedById,
  testId,
  required,
  ref,
}: {
  id: string;
  value: string;
  onChange: (value: string) => void;
  onBlur?: () => void;
  invalid?: boolean;
  describedBy?: string;
  testId?: string;
  required?: boolean;
  /** Forwarded to the input so react-hook-form's `setFocus` can reach it. */
  ref?: Ref<HTMLInputElement>;
}) {
  return (
    <div className="relative">
      <span aria-hidden="true" className="pointer-events-none absolute top-1/2 left-2.5 -translate-y-1/2 text-sm text-muted-foreground">
        Rs.
      </span>
      <Input
        ref={ref}
        id={id}
        type="text"
        inputMode="decimal"
        placeholder="0.00"
        className="pl-9"
        value={value}
        aria-required={required || undefined}
        aria-invalid={invalid || undefined}
        aria-describedby={describedById}
        onChange={(event) => onChange(event.target.value.replace(/[^\d.]/g, ""))}
        onBlur={() => {
          if (MONEY_PATTERN.test(value.trim())) onChange(Number(value.trim()).toFixed(2));
          onBlur?.();
        }}
        data-testid={testId}
      />
    </div>
  );
}
