"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { Controller, useForm, useWatch } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { PackageOpen, Search, X } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { LiveRegion } from "@/components/ui/live-region";
import { MultiSelect } from "@/components/ui/multi-select";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { EmptyState } from "@/components/states/empty-state";
import { LoadingState } from "@/components/states/loading-state";
import { MonthPicker } from "@/components/reports/month-picker";
import { ReportRunError, reportRunErrorMessage } from "@/components/reports/report-run-error";
import { isApiClientError } from "@/lib/api/error";
import type { FieldError } from "@/lib/api/types";
import { cn } from "@/lib/utils";
import {
  reportDownloadErrorMessage,
  useDownloadReportPdf,
  type ReportPaymentMethod,
} from "@/lib/api/reports";
import {
  STUDENT_PAYMENT_METHOD_FILTER_LABELS,
  studentPaymentPdfPath,
  toGroupBy,
  useCreateStudentPaymentRun,
  useStudentPaymentRows,
  type StudentPaymentOptions,
  type StudentPaymentReport,
  type StudentPaymentRowsPage,
} from "@/lib/api/student-payment-report";
import { ALL_METHODS } from "@/lib/validation/reports";
import {
  DEFAULT_STUDENT_PAYMENT_FILTER,
  studentPaymentFieldFor,
  studentPaymentFilterSchema,
  studentPaymentRequestKey,
  toStudentPaymentRunRequest,
  type StudentPaymentFilterValues,
} from "@/lib/validation/student-payment-report";
import { StudentPaymentResults } from "./student-payment-results";

const FIELD_LABELS: Record<string, string> = {
  courseIds: "Course(s)",
  paymentMethod: "Payment method",
  periodMode: "Period selection",
  months: "Billing month",
  startDate: "Date",
  endDate: "To date",
  period: "Period",
  runId: "Report",
  body: "Filter",
};

const NARROW_HINT = "Narrow the period or select fewer courses";
const PDF_FILENAME = "student-payment-report.pdf";

/**
 * Left: filter form (React Hook Form + Zod). Right: result pane. A run is
 * tied to the exact request that produced it; once the filters differ, the
 * result is marked stale and export is disabled until the search is re-run.
 * Grouping is presentation only: it re-pages the SAME run (no new run) and
 * never marks the result stale; the PDF export uses the current grouping.
 */
export function StudentPaymentWorkspace({ options }: { options: StudentPaymentOptions }) {
  const runMutation = useCreateStudentPaymentRun();
  const downloadMutation = useDownloadReportPdf();
  const [report, setReport] = useState<StudentPaymentReport | null>(null);
  const [lastKey, setLastKey] = useState<string | null>(null);
  const [exportExpired, setExportExpired] = useState(false);
  const [downloading, setDownloading] = useState(false);
  const [downloadError, setDownloadError] = useState<string | null>(null);
  const [unmappedErrors, setUnmappedErrors] = useState<FieldError[]>([]);
  const [announcement, setAnnouncement] = useState("");
  const [byStudent, setByStudent] = useState(false);
  const [byCourse, setByCourse] = useState(false);
  const [page, setPage] = useState(0);
  const [shownRows, setShownRows] = useState<StudentPaymentRowsPage | null>(null);
  const resultsHeadingRef = useRef<HTMLHeadingElement>(null);
  const errorSummaryRef = useRef<HTMLDivElement>(null);
  const paneRef = useRef<HTMLDivElement>(null);
  // Every search gets a token; a resolution whose token is no longer current
  // (cancelled or superseded) is ignored.
  const runTokenRef = useRef(0);
  // Focus the error summary after React has committed it (a rAF can run before
  // the summary exists when it appears in the same update).
  const [summaryFocusRequest, setSummaryFocusRequest] = useState(0);
  useEffect(() => {
    if (summaryFocusRequest > 0) errorSummaryRef.current?.focus();
  }, [summaryFocusRequest]);
  const abortRef = useRef<AbortController | null>(null);

  const groupBy = toGroupBy(byStudent, byCourse);
  const hasRows = report !== null && report.rowCount > 0;
  const rowsQuery = useStudentPaymentRows(hasRows ? report.runId : null, groupBy, page);

  // Keep the last successfully loaded page on screen while another page /
  // grouping loads or fails (e.g. a 429), so the table never blanks out.
  const freshRows = rowsQuery.isSuccess && !rowsQuery.isPlaceholderData ? rowsQuery.data : null;
  useEffect(() => {
    if (!freshRows) return;
    setShownRows((previous) => (previous === freshRows ? previous : freshRows));
    setAnnouncement(`Page ${freshRows.page + 1} loaded.`);
  }, [freshRows]);

  const rowsExpired = isApiClientError(rowsQuery.error) && rowsQuery.error.status === 404;
  const expired = exportExpired || rowsExpired;

  const {
    control,
    handleSubmit,
    reset,
    setError,
    clearErrors,
    formState: { errors },
  } = useForm<StudentPaymentFilterValues>({
    resolver: zodResolver(studentPaymentFilterSchema),
    defaultValues: DEFAULT_STUDENT_PAYMENT_FILTER,
    mode: "onSubmit",
    reValidateMode: "onChange",
  });

  const values = useWatch({ control }) as StudentPaymentFilterValues;
  const currentKey = studentPaymentRequestKey(
    toStudentPaymentRunRequest({ ...DEFAULT_STUDENT_PAYMENT_FILTER, ...values })
  );
  const stale = report !== null && lastKey !== null && currentKey !== lastKey;

  const courseOptions = useMemo(
    () => options.courses.map((course) => ({ value: course.id, label: course.name })),
    [options.courses]
  );
  const courseNames = useMemo(
    () => new Map(options.courses.map((course) => [course.id, course.name])),
    [options.courses]
  );
  const methodOptions = options.paymentMethods.filter(
    (method): method is ReportPaymentMethod => method in STUDENT_PAYMENT_METHOD_FILTER_LABELS
  );

  const onValid = async (formValues: StudentPaymentFilterValues) => {
    const request = toStudentPaymentRunRequest(formValues);
    const token = ++runTokenRef.current;
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    setUnmappedErrors([]);
    setDownloadError(null);
    setAnnouncement("Generating report…");
    try {
      const data = await runMutation.mutateAsync({ request, signal: controller.signal });
      if (token !== runTokenRef.current) return;
      setReport(data);
      setLastKey(studentPaymentRequestKey(request));
      setExportExpired(false);
      setShownRows(null);
      setPage(0);
      if (data.rowCount === 0 || data.sections.length === 0) {
        setAnnouncement("No payments to show.");
        requestAnimationFrame(() => paneRef.current?.focus());
      } else {
        setAnnouncement("Report ready.");
        requestAnimationFrame(() => resultsHeadingRef.current?.focus());
      }
    } catch (error) {
      if (token !== runTokenRef.current) return;
      const isFieldValidation =
        isApiClientError(error) && error.status === 422 && error.code === "REPORT_VALIDATION";
      if (!isFieldValidation) {
        setAnnouncement(`The report could not be generated. ${reportRunErrorMessage(error, NARROW_HINT)}`);
        requestAnimationFrame(() => paneRef.current?.focus());
        return;
      }
      setAnnouncement("");
      if (isApiClientError(error)) {
        const unmapped: FieldError[] = [];
        for (const fieldError of error.fieldErrors) {
          const field = studentPaymentFieldFor(fieldError.field);
          if (field) {
            setError(field, { type: "server", message: fieldError.message });
          } else {
            unmapped.push(fieldError);
          }
        }
        setUnmappedErrors(unmapped);
        setSummaryFocusRequest((count) => count + 1);
      }
    }
  };
  const onInvalid = () => setSummaryFocusRequest((count) => count + 1);
  const submit = (event?: React.BaseSyntheticEvent) => handleSubmit(onValid, onInvalid)(event);

  const onCancel = () => {
    if (runMutation.isPending) {
      runTokenRef.current++;
      abortRef.current?.abort();
      abortRef.current = null;
      runMutation.reset();
      setAnnouncement("Search cancelled.");
      return;
    }
    reset(DEFAULT_STUDENT_PAYMENT_FILTER);
    clearErrors();
    runMutation.reset();
    setReport(null);
    setLastKey(null);
    setExportExpired(false);
    setShownRows(null);
    setPage(0);
    setDownloadError(null);
    setUnmappedErrors([]);
    setAnnouncement("Filters cleared.");
  };

  const onExport = async () => {
    if (!report || stale || expired || downloading) return;
    setDownloading(true);
    setDownloadError(null);
    try {
      await downloadMutation.mutateAsync({ path: studentPaymentPdfPath(report.runId, groupBy), filename: PDF_FILENAME });
      setAnnouncement(`Downloaded ${PDF_FILENAME}.`);
    } catch (error) {
      if (isApiClientError(error) && error.status === 404) setExportExpired(true);
      setDownloadError(reportDownloadErrorMessage(error, `${NARROW_HINT}.`));
    } finally {
      setDownloading(false);
    }
  };

  const onGroupingChange = (nextStudent: boolean, nextCourse: boolean) => {
    setByStudent(nextStudent);
    setByCourse(nextCourse);
    setPage(0);
    const mode = toGroupBy(nextStudent, nextCourse);
    setAnnouncement(
      mode === "NONE"
        ? "Grouping off."
        : mode === "STUDENT"
          ? "Grouped by student."
          : mode === "COURSE"
            ? "Grouped by course."
            : "Grouped by student, then course."
    );
  };

  const onPageChange = (next: number) => {
    setPage(next);
    setAnnouncement(`Loading page ${next + 1}.`);
  };

  const focusFilters = () => document.getElementById("report-courses")?.focus();

  const hasFieldErrors = Object.keys(errors).length > 0 || unmappedErrors.length > 0;
  const periodMode = values.periodMode ?? "MONTHS";

  return (
    <div className="grid grid-cols-1 gap-6 lg:grid-cols-[minmax(18rem,22rem)_1fr]">
      <LiveRegion message={announcement} />
      <form
        noValidate
        onSubmit={(event) => void submit(event)}
        aria-label="Student payment report filters"
        className="flex flex-col gap-5 self-start rounded-lg border border-border p-4"
      >
        {hasFieldErrors ? (
          <div ref={errorSummaryRef} tabIndex={-1} className="outline-none" data-testid="filter-error-summary">
            <Alert variant="destructive">
              <AlertDescription>
                Some filters need attention.
                {unmappedErrors.length > 0 ? (
                  <ul className="mt-1 list-disc pl-4">
                    {unmappedErrors.map((fieldError) => (
                      <li key={`${fieldError.field}-${fieldError.message}`}>
                        {FIELD_LABELS[fieldError.field] ?? fieldError.field}: {fieldError.message}
                      </li>
                    ))}
                  </ul>
                ) : null}
              </AlertDescription>
            </Alert>
          </div>
        ) : null}

        <Controller
          control={control}
          name="courseIds"
          render={({ field }) => (
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="report-courses">Course(s)</Label>
              <MultiSelect
                id="report-courses"
                options={courseOptions}
                value={field.value}
                onChange={field.onChange}
                placeholder="All courses"
                searchPlaceholder="Type to filter courses…"
                emptyText="No courses available."
                invalid={!!errors.courseIds}
                aria-describedby={errors.courseIds ? "report-courses-hint report-courses-error" : "report-courses-hint"}
                testId="report-courses"
                hideSelectedInTrigger
              />
              <p id="report-courses-hint" className="text-xs text-muted-foreground">
                Optional. Leave empty to include all courses.
              </p>
              {field.value.length > 0 ? (
                <ul className="flex flex-wrap gap-1.5" aria-label="Selected courses">
                  {field.value.map((id) => {
                    const name = courseNames.get(id) ?? id;
                    return (
                      <li
                        key={id}
                        className="inline-flex max-w-full min-w-0 items-center gap-1 rounded-full bg-muted py-0.5 pr-0.5 pl-2.5 text-xs"
                      >
                        <span className="min-w-0 truncate" title={name}>
                          {name}
                        </span>
                        <button
                          type="button"
                          aria-label={`Remove ${name}`}
                          onClick={() => field.onChange(field.value.filter((entry) => entry !== id))}
                          className="inline-flex size-6 shrink-0 items-center justify-center rounded-full outline-none hover:bg-foreground/10 focus-visible:ring-2 focus-visible:ring-ring/50"
                        >
                          <X className="size-3" aria-hidden="true" />
                        </button>
                      </li>
                    );
                  })}
                </ul>
              ) : null}
              {errors.courseIds ? (
                <p id="report-courses-error" role="alert" className="text-xs text-destructive">
                  {errors.courseIds.message}
                </p>
              ) : null}
            </div>
          )}
        />

        <Controller
          control={control}
          name="paymentMethod"
          render={({ field }) => (
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="report-payment-method">Payment method</Label>
              <Select value={field.value} onValueChange={(next) => field.onChange(next ?? ALL_METHODS)}>
                <SelectTrigger
                  id="report-payment-method"
                  className="w-full"
                  aria-invalid={!!errors.paymentMethod}
                  aria-describedby={errors.paymentMethod ? "report-payment-method-error" : undefined}
                >
                  <SelectValue>
                    {(selected: string) =>
                      selected === ALL_METHODS || !selected
                        ? "All methods"
                        : STUDENT_PAYMENT_METHOD_FILTER_LABELS[selected as ReportPaymentMethod] ?? selected
                    }
                  </SelectValue>
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value={ALL_METHODS}>All methods</SelectItem>
                  {methodOptions.map((method) => (
                    <SelectItem key={method} value={method}>
                      {STUDENT_PAYMENT_METHOD_FILTER_LABELS[method]}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              {errors.paymentMethod ? (
                <p id="report-payment-method-error" role="alert" className="text-xs text-destructive">
                  {errors.paymentMethod.message}
                </p>
              ) : null}
            </div>
          )}
        />

        <Controller
          control={control}
          name="periodMode"
          render={({ field }) => (
            <fieldset className="m-0 flex flex-col gap-1.5 border-0 p-0">
              <legend className="mb-1.5 text-sm font-medium">Period selection</legend>
              <div className="grid grid-cols-2 rounded-lg border border-border p-0.5" data-testid="period-mode">
                {(["MONTHS", "DATES"] as const).map((mode) => (
                  <label
                    key={mode}
                    className={cn(
                      "cursor-pointer rounded-md px-3 py-1.5 text-center text-xs font-semibold tracking-wide transition-colors has-focus-visible:ring-3 has-focus-visible:ring-ring/50",
                      field.value === mode ? "bg-primary text-primary-foreground" : "text-muted-foreground hover:bg-muted"
                    )}
                  >
                    <input
                      type="radio"
                      name={field.name}
                      value={mode}
                      checked={field.value === mode}
                      onChange={() => field.onChange(mode)}
                      aria-label={mode === "MONTHS" ? "Billing months" : "Payment dates"}
                      aria-describedby="report-period-hint"
                      className="sr-only"
                    />
                    {mode}
                  </label>
                ))}
              </div>
              <p id="report-period-hint" className="text-xs text-muted-foreground" data-testid="period-hint">
                {field.value === "MONTHS"
                  ? "MONTHS: payments for any of the selected billing (purchased) months."
                  : "Payment received date (manual receipts use the date the money was received)."}
              </p>
              {errors.periodMode ? (
                <p role="alert" className="text-xs text-destructive">
                  {errors.periodMode.message}
                </p>
              ) : null}
            </fieldset>
          )}
        />

        {periodMode === "MONTHS" ? (
          <Controller
            control={control}
            name="months"
            render={({ field }) => (
              <div className="flex flex-col gap-1.5">
                <p id="report-months-label" className="text-sm font-medium">
                  Billing month (purchased month)
                </p>
                <MonthPicker
                  labelId="report-months-label"
                  value={field.value}
                  onChange={field.onChange}
                  invalid={!!errors.months}
                  describedBy={errors.months ? "report-months-error" : undefined}
                />
                {errors.months ? (
                  <p id="report-months-error" role="alert" className="text-xs text-destructive">
                    {errors.months.message}
                  </p>
                ) : null}
              </div>
            )}
          />
        ) : (
          <fieldset className="m-0 flex flex-col gap-3 border-0 p-0">
            <legend className="mb-1.5 text-sm font-medium">Payment received date</legend>
            <Controller
              control={control}
              name="startDate"
              rules={{ deps: ["endDate"] }}
              render={({ field }) => (
                <div className="flex flex-col gap-1.5">
                  <Label htmlFor="report-start-date">
                    Date
                    <span aria-hidden="true" className="text-destructive">
                      *
                    </span>
                    <span className="sr-only">(required)</span>
                  </Label>
                  <Input
                    id="report-start-date"
                    type="date"
                    value={field.value}
                    onChange={(event) => field.onChange(event.target.value)}
                    onBlur={field.onBlur}
                    aria-invalid={!!errors.startDate}
                    aria-describedby={errors.startDate ? "report-start-date-error" : undefined}
                  />
                  {errors.startDate ? (
                    <p id="report-start-date-error" role="alert" className="text-xs text-destructive">
                      {errors.startDate.message}
                    </p>
                  ) : null}
                </div>
              )}
            />
            <Controller
              control={control}
              name="endDate"
              rules={{ deps: ["startDate"] }}
              render={({ field }) => (
                <div className="flex flex-col gap-1.5">
                  <Label htmlFor="report-end-date">To date (optional)</Label>
                  <div className="flex items-center gap-2">
                    <Input
                      id="report-end-date"
                      type="date"
                      value={field.value}
                      min={values.startDate || undefined}
                      onChange={(event) => field.onChange(event.target.value)}
                      onBlur={field.onBlur}
                      aria-invalid={!!errors.endDate}
                      aria-describedby={
                        errors.endDate ? "report-end-date-hint report-end-date-error" : "report-end-date-hint"
                      }
                    />
                    {field.value ? (
                      <Button
                        type="button"
                        variant="ghost"
                        size="sm"
                        onClick={() => field.onChange("")}
                        aria-label="Clear the to date (single day)"
                      >
                        Clear
                      </Button>
                    ) : null}
                  </div>
                  <p id="report-end-date-hint" className="text-xs text-muted-foreground">
                    Leave empty for a single day. Both dates are included.
                  </p>
                  {errors.endDate ? (
                    <p id="report-end-date-error" role="alert" className="text-xs text-destructive">
                      {errors.endDate.message}
                    </p>
                  ) : null}
                </div>
              )}
            />
          </fieldset>
        )}

        <div className="flex flex-wrap justify-end gap-2 border-t border-border pt-4">
          <Button type="button" variant="outline" onClick={onCancel}>
            {runMutation.isPending ? "Cancel search" : "Cancel"}
          </Button>
          <Button type="submit" disabled={runMutation.isPending}>
            <Search aria-hidden="true" />
            {runMutation.isPending ? "Searching…" : "Search"}
          </Button>
        </div>
      </form>

      <section aria-label="Report results" aria-busy={runMutation.isPending} className="min-w-0">
        <div ref={paneRef} tabIndex={-1} className="outline-none" data-testid="result-pane">
          {runMutation.isPending ? (
            <LoadingState label="Generating report…" />
          ) : runMutation.isError ? (
            <ReportRunError
              error={runMutation.error}
              narrowHint={NARROW_HINT}
              fieldLabels={FIELD_LABELS}
              onRetry={() => void submit()}
            />
          ) : !report ? (
            <EmptyState
              title="Choose filters to run the report"
              description="Optionally select courses and a payment method, choose the months or dates, then choose Search."
            />
          ) : report.rowCount === 0 || report.sections.length === 0 ? (
            <EmptyState
              icon={<PackageOpen className="size-8" />}
              title="No payments to show"
              description={`No student payments match these filters for ${report.filters.periodLabel}. Try other months, dates, courses or payment method.`}
              action={{ label: "Change filters", onClick: focusFilters }}
            />
          ) : (
            <StudentPaymentResults
              ref={resultsHeadingRef}
              report={report}
              stale={stale}
              expired={expired}
              downloading={downloading}
              downloadError={downloadError}
              byStudent={byStudent}
              byCourse={byCourse}
              onGroupingChange={onGroupingChange}
              rows={shownRows}
              rowsLoading={rowsQuery.isFetching}
              rowsError={rowsExpired ? null : rowsQuery.isError ? rowsQuery.error : null}
              onRetryRows={() => void rowsQuery.refetch()}
              onPageChange={onPageChange}
              onExport={() => void onExport()}
              onRunAgain={() => void submit()}
            />
          )}
        </div>
      </section>
    </div>
  );
}
