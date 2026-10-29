"use client";

import { ChevronFirst, ChevronLast, ChevronLeft, ChevronRight } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { NATIVE_SELECT_CLASS } from "@/components/payments/staff/staff-dialog";

export const HUB_PAGE_SIZES = [10, 20, 50] as const;

/**
 * PAY-HUB-01 Klass-style table footer: "Items per page" plus "x–y of N" and
 * first/previous/next/last. `page` is 0-based, as in `PageResponse`.
 */
export function HubPagination({
  idPrefix,
  page,
  size,
  totalElements,
  onPageChange,
  onSizeChange,
}: {
  idPrefix: string;
  page: number;
  size: number;
  totalElements: number;
  onPageChange: (page: number) => void;
  onSizeChange: (size: number) => void;
}) {
  const lastPage = Math.max(Math.ceil(totalElements / size) - 1, 0);
  const first = totalElements === 0 ? 0 : page * size + 1;
  const last = Math.min((page + 1) * size, totalElements);

  return (
    <div className="flex flex-col items-end gap-3 sm:flex-row sm:items-center sm:justify-end sm:gap-6">
      <div className="flex items-center gap-2">
        <Label htmlFor={`${idPrefix}-page-size`} className="text-sm font-normal whitespace-nowrap text-muted-foreground">
          Items per page
        </Label>
        <select
          id={`${idPrefix}-page-size`}
          className={`${NATIVE_SELECT_CLASS} w-20`}
          value={size}
          onChange={(event) => onSizeChange(Number(event.target.value))}
        >
          {HUB_PAGE_SIZES.map((option) => (
            <option key={option} value={option}>
              {option}
            </option>
          ))}
        </select>
      </div>
      <div className="flex items-center gap-1">
        <span className="mr-2 text-sm whitespace-nowrap text-muted-foreground" aria-live="polite">
          {first}–{last} of {totalElements}
        </span>
        <Button type="button" variant="ghost" size="icon-sm" aria-label="First page" disabled={page === 0} onClick={() => onPageChange(0)}>
          <ChevronFirst aria-hidden="true" />
        </Button>
        <Button
          type="button"
          variant="ghost"
          size="icon-sm"
          aria-label="Previous page"
          disabled={page === 0}
          onClick={() => onPageChange(Math.max(0, page - 1))}
        >
          <ChevronLeft aria-hidden="true" />
        </Button>
        <Button
          type="button"
          variant="ghost"
          size="icon-sm"
          aria-label="Next page"
          disabled={page >= lastPage}
          onClick={() => onPageChange(page + 1)}
        >
          <ChevronRight aria-hidden="true" />
        </Button>
        <Button
          type="button"
          variant="ghost"
          size="icon-sm"
          aria-label="Last page"
          disabled={page >= lastPage}
          onClick={() => onPageChange(lastPage)}
        >
          <ChevronLast aria-hidden="true" />
        </Button>
      </div>
    </div>
  );
}
