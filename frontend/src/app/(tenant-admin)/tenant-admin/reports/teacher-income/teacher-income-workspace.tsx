"use client";

import { useMemo, useRef, useState } from "react";
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
import { ReportRunError, reportRunErrorMessage } from "@/components/reports/report-run-error";
import { isApiClientError } from "@/lib/api/error";
import type { FieldError } from "@/lib/api/types";
import { cn } from "@/lib/utils";
import {
  payslipPdfPath,
  REPORT_PAYMENT_METHOD_LABELS,
  reportDownloadErrorMessage,
  summaryPdfPath,
  useCreateTeacherIncomeRun,
  useDownloadReportPdf,
  type ReportPaymentMethod,
  type ReportTeacherBlock,
  type TeacherIncomeOptions,
  type TeacherIncomeReport,
} from "@/lib/api/reports";
import {
  ALL_METHODS,
  DEFAULT_TEACHER_INCOME_FILTER,
  filterFieldFor,
  runRequestKey,
  teacherIncomeFilterSchema,
  toTeacherIncomeRunRequest,
  type TeacherIncomeFilterValues,
} from "@/lib/validation/reports";
import { MonthPicker } from "@/components/reports/month-picker";
import { TeacherIncomeResults } from "./teacher-income-results";

const FIELD_LABELS: Record<string, string> = {
  teacherIds: "Teacher(s)",
  paymentMethod: "Payment method",
  periodMode: "Period selection",
  months: "Billing month",
  startDate: "Start date",
  endDate: "End date",
  runId: "Report",
  body: "Filter",
};

const NARROW_HINT = "Narrow the period or select fewer teachers";

function slug(value: string): string {
  return value.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "") || "teacher";
}

/**
 * Left: filter form (React Hook Form + Zod). Right: result pane. A run is
 * tied to the exact request that produced it; once the filters differ, the
 * result is marked stale and every export is disabled until the search is
 * re-run - an old run is never exported for new filters.
 */
export function TeacherIncomeWorkspace({ options }: { options: TeacherIncomeOptions }) {
  const runMutation = useCreateTeacherIncomeRun();
  const downloadMutation = useDownloadReportPdf();
  const [report, setReport] = useState<TeacherIncomeReport | null>(null);
  const [lastKey, setLastKey] = useState<string | null>(null);
  const [expired, setExpired] = useState(false);
  const [downloading, setDownloading] = useState<string | null>(null);
  const [downloadError, setDownloadError] = useState<string | null>(null);
  const [unmappedErrors, setUnmappedErrors] = useState<FieldError[]>([]);
  const [announcement, setAnnouncement] = useState("");
  const resultsHeadingRef = useRef<HTMLHeadingElement>(null);
  const errorSummaryRef = useRef<HTMLDivElement>(null);
  const paneRef = useRef<HTMLDivElement>(null);
  // Every search gets a token; a resolution whose token is no longer current
  // (cancelled or superseded) is ignored, so it can never apply results or
  // move focus for filters the user has since changed or cleared.
  const runTokenRef = useRef(0);
  const abortRef = useRef<AbortController | null>(null);

  const {
    control,
    handleSubmit,
    reset,
    setError,
    clearErrors,
    formState: { errors },
  } = useForm<TeacherIncomeFilterValues>({
    resolver: zodResolver(teacherIncomeFilterSchema),
    defaultValues: DEFAULT_TEACHER_INCOME_FILTER,
    mode: "onSubmit",
    reValidateMode: "onChange",
  });

  const values = useWatch({ control }) as TeacherIncomeFilterValues;
  const currentKey = runRequestKey(toTeacherIncomeRunRequest({ ...DEFAULT_TEACHER_INCOME_FILTER, ...values }));
  const stale = report !== null && lastKey !== null && currentKey !== lastKey;

  const teacherOptions = useMemo(
    () => options.teachers.map((teacher) => ({ value: teacher.id, label: teacher.name })),
    [options.teachers]
  );
  const teacherNames = useMemo(
    () => new Map(options.teachers.map((teacher) => [teacher.id, teacher.name])),
    [options.teachers]
  );
  const methodOptions = options.paymentMethods.filter(
    (method): method is ReportPaymentMethod => method in REPORT_PAYMENT_METHOD_LABELS
  );

  const onValid = async (formValues: TeacherIncomeFilterValues) => {
    const request = toTeacherIncomeRunRequest(formValues);
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
      setLastKey(runRequestKey(request));
      setExpired(false);
      if (data.sections.length === 0) {
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
          const field = filterFieldFor(fieldError.field);
          if (field) {
            setError(field, { type: "server", message: fieldError.message });
          } else {
            unmapped.push(fieldError);
          }
        }
        setUnmappedErrors(unmapped);
        requestAnimationFrame(() => errorSummaryRef.current?.focus());
      }
    }
  };
  const onInvalid = () => requestAnimationFrame(() => errorSummaryRef.current?.focus());
  // handleSubmit is invoked inside the event handlers (never during render).
  const submit = (event?: React.BaseSyntheticEvent) => handleSubmit(onValid, onInvalid)(event);

  const onCancel = () => {
    if (runMutation.isPending) {
      // Cancel during a search stops that search only; the filters are kept.
      runTokenRef.current++;
      abortRef.current?.abort();
      abortRef.current = null;
      runMutation.reset();
      setAnnouncement("Search cancelled.");
      return;
    }
    reset(DEFAULT_TEACHER_INCOME_FILTER);
    clearErrors();
    runMutation.reset();
    setReport(null);
    setLastKey(null);
    setExpired(false);
    setDownloadError(null);
    setUnmappedErrors([]);
    setAnnouncement("Filters cleared.");
  };

  const download = async (kind: string, path: string, filename: string) => {
    if (!report || stale || expired) return;
    setDownloading(kind);
    setDownloadError(null);
    try {
      await downloadMutation.mutateAsync({ path, filename });
      setAnnouncement(`Downloaded ${filename}.`);
    } catch (error) {
      if (isApiClientError(error) && error.status === 404) setExpired(true);
      setDownloadError(reportDownloadErrorMessage(error));
    } finally {
      setDownloading(null);
    }
  };

  const onExportSummary = () => {
    if (report) void download("summary", summaryPdfPath(report.runId), "teacher-income-summary.pdf");
  };
  const onDownloadPayslip = (teacher: ReportTeacherBlock) => {
    if (report) {
      void download(
        `payslip:${teacher.teacherId}`,
        payslipPdfPath(report.runId, teacher.teacherId),
        `payslip-${slug(teacher.teacherName)}.pdf`
      );
    }
  };

  const focusFilters = () => document.getElementById("report-teachers")?.focus();

  const hasFieldErrors = Object.keys(errors).length > 0 || unmappedErrors.length > 0;
  const periodMode = values.periodMode ?? "MONTHS";

  return (
    <div className="grid grid-cols-1 gap-6 lg:grid-cols-[minmax(18rem,22rem)_1fr]">
      <LiveRegion message={announcement} />
      <form
        noValidate
        onSubmit={(event) => void submit(event)}
        aria-label="Teacher income report filters"
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
          name="teacherIds"
          render={({ field }) => (
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="report-teachers">
                Teacher(s)
                <span aria-hidden="true" className="text-destructive">
                  *
                </span>
                <span className="sr-only">(required)</span>
              </Label>
              <MultiSelect
                id="report-teachers"
                options={teacherOptions}
                value={field.value}
                onChange={field.onChange}
                placeholder="Select teachers…"
                searchPlaceholder="Type to filter teachers…"
                emptyText="No teachers available."
                invalid={!!errors.teacherIds}
                aria-describedby={errors.teacherIds ? "report-teachers-error" : undefined}
                testId="report-teachers"
                hideSelectedInTrigger
              />
              {field.value.length > 0 ? (
                <ul className="flex flex-wrap gap-1.5" aria-label="Selected teachers">
                  {field.value.map((id) => {
                    const name = teacherNames.get(id) ?? id;
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
              {errors.teacherIds ? (
                <p id="report-teachers-error" role="alert" className="text-xs text-destructive">
                  {errors.teacherIds.message}
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
                        : REPORT_PAYMENT_METHOD_LABELS[selected as ReportPaymentMethod] ?? selected
                    }
                  </SelectValue>
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value={ALL_METHODS}>All methods</SelectItem>
                  {methodOptions.map((method) => (
                    <SelectItem key={method} value={method}>
                      {REPORT_PAYMENT_METHOD_LABELS[method]}
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
                      aria-label={mode === "MONTHS" ? "Billing months" : "Received dates"}
                      aria-describedby="report-period-hint"
                      className="sr-only"
                    />
                    {mode}
                  </label>
                ))}
              </div>
              <p id="report-period-hint" className="text-xs text-muted-foreground" data-testid="period-hint">
                {field.value === "MONTHS"
                  ? "MONTHS: payments for the billing (purchased) month."
                  : "DATES: payments received (confirmed) on these dates, in the institute's timezone."}
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
            <legend className="mb-1.5 text-sm font-medium">Payment received (confirmed) date</legend>
            <Controller
              control={control}
              name="startDate"
              rules={{ deps: ["endDate"] }}
              render={({ field }) => (
                <div className="flex flex-col gap-1.5">
                  <Label htmlFor="report-start-date">Start date</Label>
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
                  <Label htmlFor="report-end-date">End date</Label>
                  <Input
                    id="report-end-date"
                    type="date"
                    value={field.value}
                    onChange={(event) => field.onChange(event.target.value)}
                    onBlur={field.onBlur}
                    aria-invalid={!!errors.endDate}
                    aria-describedby={errors.endDate ? "report-end-date-error" : undefined}
                  />
                  {errors.endDate ? (
                    <p id="report-end-date-error" role="alert" className="text-xs text-destructive">
                      {errors.endDate.message}
                    </p>
                  ) : null}
                </div>
              )}
            />
            <p className="text-xs text-muted-foreground">Both dates are included.</p>
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
          <ResultPane
          pending={runMutation.isPending}
          error={runMutation.isError ? runMutation.error : null}
          report={report}
          stale={stale}
          expired={expired}
          downloading={downloading}
          downloadError={downloadError}
          headingRef={resultsHeadingRef}
          onRetry={() => void submit()}
          onExportSummary={onExportSummary}
          onDownloadPayslip={onDownloadPayslip}
            onRunAgain={() => void submit()}
            onChangeFilters={focusFilters}
          />
        </div>
      </section>
    </div>
  );
}

function ResultPane({
  pending,
  error,
  report,
  stale,
  expired,
  downloading,
  downloadError,
  headingRef,
  onRetry,
  onExportSummary,
  onDownloadPayslip,
  onRunAgain,
  onChangeFilters,
}: {
  pending: boolean;
  error: unknown;
  report: TeacherIncomeReport | null;
  stale: boolean;
  expired: boolean;
  downloading: string | null;
  downloadError: string | null;
  headingRef: React.RefObject<HTMLHeadingElement | null>;
  onRetry: () => void;
  onExportSummary: () => void;
  onDownloadPayslip: (teacher: ReportTeacherBlock) => void;
  onRunAgain: () => void;
  onChangeFilters: () => void;
}) {
  if (pending) {
    return <LoadingState label="Generating report…" />;
  }

  if (error) {
    return <ReportRunError error={error} narrowHint={NARROW_HINT} fieldLabels={FIELD_LABELS} onRetry={onRetry} />;
  }

  if (!report) {
    return (
      <EmptyState
        title="Choose filters to run the report"
        description="Select one or more teachers and the months or dates to include, then choose Search."
      />
    );
  }

  if (report.sections.length === 0) {
    return (
      <EmptyState
        icon={<PackageOpen className="size-8" />}
        title="No payments to show"
        description={`No confirmed payments or payouts for the selected teachers in ${report.filters.periodLabel}. Try other months, dates or payment method.`}
        action={{ label: "Change filters", onClick: onChangeFilters }}
      />
    );
  }

  return (
    <TeacherIncomeResults
      ref={headingRef}
      report={report}
      stale={stale}
      expired={expired}
      downloading={downloading}
      downloadError={downloadError}
      onExportSummary={onExportSummary}
      onDownloadPayslip={onDownloadPayslip}
      onRunAgain={onRunAgain}
    />
  );
}
