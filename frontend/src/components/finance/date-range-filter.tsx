"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { DateInput } from "@/components/ui/date-input";
import type { DateRangeParams } from "@/lib/api/finance";

/**
 * Wave 7 Finance date-range filter. Both dates empty = the backend's own
 * default (current calendar month in the tenant's timezone) — this client
 * never computes a "current month" itself. Validation here (from <= to) is
 * UX only; the backend re-validates and 400s an invalid range.
 */
export function DateRangeFilter({
  idPrefix,
  value,
  onApply,
}: {
  idPrefix: string;
  value: DateRangeParams;
  onApply: (next: DateRangeParams) => void;
}) {
  const [from, setFrom] = useState(value.from ?? "");
  const [to, setTo] = useState(value.to ?? "");
  const invalid = from !== "" && to !== "" && from > to;
  const errorId = `${idPrefix}-range-error`;

  return (
    <form
      className="flex flex-col gap-2 sm:flex-row sm:items-end sm:gap-3"
      onSubmit={(event) => {
        event.preventDefault();
        if (!invalid) onApply({ from: from || undefined, to: to || undefined });
      }}
    >
      <DateInput
        idPrefix={idPrefix}
        fromValue={from}
        toValue={to}
        onFromChange={setFrom}
        onToChange={setTo}
        invalid={invalid}
        errorId={invalid ? errorId : undefined}
      />
      <div className="flex gap-2">
        <Button type="submit" size="sm" disabled={invalid}>
          Apply
        </Button>
        <Button
          type="button"
          size="sm"
          variant="outline"
          onClick={() => {
            setFrom("");
            setTo("");
            onApply({});
          }}
        >
          This month
        </Button>
      </div>
      {invalid ? (
        <p id={errorId} role="alert" className="text-xs text-destructive">
          The start date must be on or before the end date.
        </p>
      ) : null}
    </form>
  );
}
