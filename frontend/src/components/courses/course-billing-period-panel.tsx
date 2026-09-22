"use client";

import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { CheckCircle2, Info } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { ErrorState } from "@/components/states/error-state";
import { EmptyState } from "@/components/states/empty-state";
import { LoadingState } from "@/components/states/loading-state";
import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import {
  useAddCourseBillingPeriod,
  useCourseBillingPeriods,
  type CourseBillingPeriodResponse,
} from "@/lib/api/course-billing";
import { isApiClientError } from "@/lib/api/error";
import { formatDateTime, formatMoney } from "@/lib/format";
import {
  courseBillingPeriodSchema,
  toCourseBillingPeriodRequest,
  CURRENCY_HELPER_TEXT,
  PRICE_HELPER_TEXT,
  type CourseBillingPeriodFormValues,
} from "@/lib/validation/course";

const columns: DataTableColumn<CourseBillingPeriodResponse>[] = [
  { key: "amount", header: "Amount", cell: (row) => formatMoney(row.amount, row.currency) },
  { key: "effectiveFrom", header: "Effective from", cell: (row) => formatDateTime(row.effectiveFrom) },
  {
    key: "effectiveTo",
    header: "Effective to",
    cell: (row) => (row.effectiveTo ? formatDateTime(row.effectiveTo) : "Current"),
  },
];

/**
 * Billing-period history (paginated) + "add new period" form
 * (`course_billing_period`, Wave 2). Adding a period closes the current open
 * one and inserts a new one in the same backend transaction — this is
 * explicitly NEVER retroactive: a closed period's own amount/dates are
 * never rewritten (append-only history), so this only affects billing going
 * forward. The form below states this plainly rather than leaving it
 * implicit.
 */
export function CourseBillingPeriodPanel({ courseId }: { courseId: string }) {
  const [page, setPage] = useState(0);
  const [saved, setSaved] = useState(false);
  const [pageError, setPageError] = useState<string | null>(null);

  const periodsQuery = useCourseBillingPeriods(courseId, page);
  const addMutation = useAddCourseBillingPeriod(courseId);

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<CourseBillingPeriodFormValues>({
    resolver: zodResolver(courseBillingPeriodSchema),
    defaultValues: { amount: "", currency: "", effectiveFrom: "" },
  });

  const onSubmit = handleSubmit(async (values) => {
    setPageError(null);
    setSaved(false);
    try {
      await addMutation.mutateAsync(toCourseBillingPeriodRequest(values));
      setSaved(true);
      reset({ amount: "", currency: values.currency, effectiveFrom: "" });
      setPage(0);
    } catch (error) {
      setPageError(isApiClientError(error) ? error.message : "An unexpected error occurred. Please try again.");
    }
  });

  // Best-effort hint only, derived from the currently-loaded first page (no
  // extra fetch) — used to steer the caller away from submitting an
  // `effectiveFrom` earlier than the current open period's own
  // `effectiveFrom`, which the backend rejects (a database constraint on the
  // period being closed, surfaced as a `409`). Never authoritative: the
  // backend independently and always re-validates this regardless of what
  // this hint shows or omits.
  const currentOpenPeriod =
    page === 0 ? periodsQuery.data?.content.find((period) => period.effectiveTo === null) : undefined;

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-col gap-3 rounded-lg border border-border p-4">
        <div>
          <h3 className="text-sm font-medium text-foreground">Add a new billing period</h3>
          <div className="mt-1 flex items-start gap-2 rounded-md border border-border bg-muted/40 px-3 py-2 text-xs text-muted-foreground">
            <Info className="mt-0.5 size-3.5 shrink-0" aria-hidden="true" />
            <p>
              Adding a new period closes the current one and starts a new billed amount going
              forward. This does <strong>not</strong> retroactively change any past period or any
              payment already made.
              {currentOpenPeriod ? (
                <>
                  {" "}
                  The current period has been in effect since{" "}
                  {formatDateTime(currentOpenPeriod.effectiveFrom)} — a new period&apos;s effective
                  date must not be earlier than that.
                </>
              ) : null}
            </p>
          </div>
        </div>

        {saved ? (
          <Alert role="status">
            <CheckCircle2 aria-hidden="true" />
            <AlertDescription>Billing period added.</AlertDescription>
          </Alert>
        ) : null}
        {pageError ? <ErrorState message={pageError} onRetry={() => setPageError(null)} /> : null}

        <form
          className="flex flex-col gap-3 sm:flex-row sm:flex-wrap sm:items-end"
          noValidate
          aria-busy={addMutation.isPending}
          onSubmit={onSubmit}
        >
          <span role="status" aria-live="polite" className="sr-only">
            {addMutation.isPending ? "Adding billing period…" : ""}
          </span>
          <div className="flex flex-col gap-1.5 sm:w-40">
            <Label htmlFor={`billing-period-${courseId}-amount`}>Amount</Label>
            <Input
              id={`billing-period-${courseId}-amount`}
              inputMode="decimal"
              autoComplete="off"
              disabled={addMutation.isPending}
              aria-invalid={!!errors.amount}
              aria-describedby={`billing-period-${courseId}-amount-helper`}
              {...register("amount")}
            />
            <p id={`billing-period-${courseId}-amount-helper`} className="text-xs text-muted-foreground">
              {PRICE_HELPER_TEXT}
            </p>
            {errors.amount ? (
              <p role="alert" className="text-xs text-destructive">
                {errors.amount.message}
              </p>
            ) : null}
          </div>
          <div className="flex flex-col gap-1.5 sm:w-32">
            <Label htmlFor={`billing-period-${courseId}-currency`}>Currency</Label>
            <Input
              id={`billing-period-${courseId}-currency`}
              autoComplete="off"
              maxLength={3}
              disabled={addMutation.isPending}
              aria-invalid={!!errors.currency}
              aria-describedby={`billing-period-${courseId}-currency-helper`}
              {...register("currency")}
            />
            <p id={`billing-period-${courseId}-currency-helper`} className="text-xs text-muted-foreground">
              {CURRENCY_HELPER_TEXT}
            </p>
            {errors.currency ? (
              <p role="alert" className="text-xs text-destructive">
                {errors.currency.message}
              </p>
            ) : null}
          </div>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor={`billing-period-${courseId}-effectiveFrom`}>
              Effective from <span className="font-normal text-muted-foreground">(optional — defaults to now)</span>
            </Label>
            <Input
              id={`billing-period-${courseId}-effectiveFrom`}
              type="datetime-local"
              disabled={addMutation.isPending}
              {...register("effectiveFrom")}
            />
          </div>
          <Button type="submit" disabled={addMutation.isPending} aria-busy={addMutation.isPending}>
            {addMutation.isPending ? "Adding…" : "Add period"}
          </Button>
        </form>
      </div>

      <div className="flex flex-col gap-3">
        <h3 className="text-sm font-medium text-foreground">Billing period history</h3>
        {periodsQuery.status === "pending" ? (
          <LoadingState label="Loading billing periods…" />
        ) : periodsQuery.status === "error" ? (
          <ErrorState
            message={isApiClientError(periodsQuery.error) ? periodsQuery.error.message : "Something went wrong. Please try again."}
            onRetry={() => periodsQuery.refetch()}
          />
        ) : (periodsQuery.data?.content.length ?? 0) === 0 ? (
          <EmptyState
            title="No billing periods yet"
            description="Once a billing period is added above, its history will appear here."
          />
        ) : (
          <div className="flex flex-col gap-3">
            <DataTable
              columns={columns}
              rows={periodsQuery.data!.content}
              rowKey={(row) => row.id}
              caption="Billing period history"
              cardHeading={(row) => formatMoney(row.amount, row.currency)}
              cardHeadingAdornment={(row) => (
                <span className="text-xs text-muted-foreground">{row.effectiveTo ? "Closed" : "Current"}</span>
              )}
            />
            <div className="flex items-center justify-between">
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => setPage((current) => Math.max(0, current - 1))}
                disabled={page === 0 || periodsQuery.isFetching}
              >
                Previous
              </Button>
              <span className="text-xs text-muted-foreground">
                Page {periodsQuery.data!.page + 1} of {Math.max(periodsQuery.data!.totalPages, 1)}
              </span>
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => setPage((current) => current + 1)}
                disabled={periodsQuery.data!.page + 1 >= periodsQuery.data!.totalPages || periodsQuery.isFetching}
              >
                Next
              </Button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
