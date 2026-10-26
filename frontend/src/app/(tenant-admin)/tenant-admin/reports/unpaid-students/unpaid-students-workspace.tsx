"use client";

import { useMemo, useRef, useState } from "react";
import { Controller, useForm, useWatch } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Search } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { LiveRegion } from "@/components/ui/live-region";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Switch } from "@/components/ui/switch";
import { EmptyState } from "@/components/states/empty-state";
import { LoadingState } from "@/components/states/loading-state";
import { ReportRunError, reportRunErrorMessage } from "@/components/reports/report-run-error";
import { isApiClientError } from "@/lib/api/error";
import { reportDownloadErrorMessage, useDownloadReportPdf } from "@/lib/api/reports";
import {
  unpaidStudentsPdfPath,
  useCreateUnpaidStudentsRun,
  useUnpaidStudentsRows,
  type UnpaidStudentsOptions,
  type UnpaidStudentsReport,
  type UnpaidStudentsRowsPage,
} from "@/lib/api/unpaid-students-report";
import {
  DEFAULT_UNPAID_STUDENTS_FILTER,
  unpaidStudentsFieldFor,
  unpaidStudentsFilterSchema,
  unpaidStudentsRequestKey,
  toUnpaidStudentsRunRequest,
  type UnpaidStudentsFilterValues,
} from "@/lib/validation/unpaid-students-report";
import { MONTH_LONG_NAMES, monthKey } from "@/lib/validation/reports";
import { cn } from "@/lib/utils";
import { UnpaidStudentsResults } from "./unpaid-students-results";

const PDF_FILENAME = "unpaid-students-report.pdf";
const FIELD_LABELS: Record<string, string> = { courseId: "Course", billingMonth: "Billing month", body: "Filter", runId: "Report" };

export function UnpaidStudentsWorkspace({ options }: { options: UnpaidStudentsOptions }) {
  const runMutation = useCreateUnpaidStudentsRun();
  const downloadMutation = useDownloadReportPdf();
  const [report, setReport] = useState<UnpaidStudentsReport | null>(null);
  const [lastKey, setLastKey] = useState<string | null>(null);
  const [page, setPage] = useState(0);
  const [shownRows, setShownRows] = useState<UnpaidStudentsRowsPage | null>(null);
  const [exportExpired, setExportExpired] = useState(false);
  const [downloading, setDownloading] = useState(false);
  const [downloadError, setDownloadError] = useState<string | null>(null);
  const [announcement, setAnnouncement] = useState("");
  const [year, setYear] = useState(() => new Date().getFullYear());
  const tokenRef = useRef(0);
  const abortRef = useRef<AbortController | null>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);
  const paneRef = useRef<HTMLDivElement>(null);
  const {
    control, handleSubmit, reset, setError, clearErrors,
    formState: { errors },
  } = useForm<UnpaidStudentsFilterValues>({
    resolver: zodResolver(unpaidStudentsFilterSchema),
    defaultValues: DEFAULT_UNPAID_STUDENTS_FILTER,
    mode: "onSubmit",
  });
  const values = useWatch({ control }) as UnpaidStudentsFilterValues;
  const currentRequest = toUnpaidStudentsRunRequest({ ...DEFAULT_UNPAID_STUDENTS_FILTER, ...values });
  const currentKey = unpaidStudentsRequestKey(currentRequest);
  const stale = report !== null && lastKey !== null && currentKey !== lastKey;
  const rowsQuery = useUnpaidStudentsRows(report ? report.runId : null, page);
  const freshRows = rowsQuery.isSuccess && !rowsQuery.isPlaceholderData ? rowsQuery.data : null;
  const displayedRows = freshRows ?? shownRows;
  const rowsExpired = isApiClientError(rowsQuery.error) && rowsQuery.error.status === 404;
  const expired = exportExpired || rowsExpired;
  const courses = useMemo(() => options.courses, [options.courses]);

  const run = async (formValues: UnpaidStudentsFilterValues) => {
    const request = toUnpaidStudentsRunRequest(formValues);
    const token = ++tokenRef.current;
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    setDownloadError(null);
    setAnnouncement("Generating report…");
    try {
      const next = await runMutation.mutateAsync({ request, signal: controller.signal });
      if (token !== tokenRef.current) return;
      setReport(next); setLastKey(unpaidStudentsRequestKey(request)); setPage(0); setShownRows(null); setExportExpired(false);
      setAnnouncement(next.unpaidCount === 0 ? "No unpaid students match these filters." : "Report ready.");
      requestAnimationFrame(() => (next.unpaidCount === 0 ? paneRef.current : headingRef.current)?.focus());
    } catch (error) {
      if (token !== tokenRef.current) return;
      if (isApiClientError(error) && error.status === 422 && error.code === "REPORT_VALIDATION") {
        error.fieldErrors.forEach((item) => {
          const field = unpaidStudentsFieldFor(item.field);
          if (field) setError(field, { type: "server", message: item.message });
        });
      } else {
        setAnnouncement(`The report could not be generated. ${reportRunErrorMessage(error, "Try another course or month")}`);
      }
    }
  };

  const cancel = () => {
    if (runMutation.isPending) { tokenRef.current++; abortRef.current?.abort(); runMutation.reset(); setAnnouncement("Search cancelled."); return; }
    reset(DEFAULT_UNPAID_STUDENTS_FILTER); clearErrors(); setReport(null); setLastKey(null); setShownRows(null); setPage(0); setExportExpired(false); setDownloadError(null); setAnnouncement("Filters cleared.");
  };
  const exportPdf = async () => {
    if (!report || stale || expired || downloading) return;
    setDownloading(true); setDownloadError(null);
    try { await downloadMutation.mutateAsync({ path: unpaidStudentsPdfPath(report.runId), filename: PDF_FILENAME }); setAnnouncement(`Downloaded ${PDF_FILENAME}.`); }
    catch (error) { if (isApiClientError(error) && error.status === 404) setExportExpired(true); setDownloadError(reportDownloadErrorMessage(error, "Run the report again.")); }
    finally { setDownloading(false); }
  };

  return <div className="grid grid-cols-1 gap-6 lg:grid-cols-[minmax(18rem,22rem)_1fr]">
    <LiveRegion message={announcement} />
    <form noValidate onSubmit={(event) => void handleSubmit(run)(event)} aria-label="Unpaid students report filters" className="flex flex-col gap-5 self-start rounded-lg border border-border p-4">
      <div className="flex flex-col gap-1.5">
        <Label htmlFor="unpaid-course">Course <span aria-hidden="true" className="text-destructive">*</span></Label>
        <Controller control={control} name="courseId" render={({ field }) => (
          <Select value={field.value} onValueChange={(value) => field.onChange(value ?? "")}>
            <SelectTrigger id="unpaid-course" className="w-full" aria-invalid={!!errors.courseId}>
              <SelectValue>{(value: string) => courses.find((course) => course.id === value)?.name ?? "Select course"}</SelectValue>
            </SelectTrigger>
            <SelectContent>{courses.map((course) => <SelectItem key={course.id} value={course.id}>{course.name}</SelectItem>)}</SelectContent>
          </Select>
        )} />
        {errors.courseId ? <p role="alert" className="text-xs text-destructive">{errors.courseId.message}</p> : null}
      </div>
      <Controller control={control} name="atLeastOneAttended" render={({ field }) => <div className="flex items-center justify-between rounded-lg border p-3"><Label htmlFor="unpaid-attended">At least one attended</Label><Switch id="unpaid-attended" checked={field.value} onCheckedChange={field.onChange} /></div>} />
      <Controller control={control} name="billingMonth" render={({ field }) => (
        <fieldset className="flex flex-col gap-3 border-0 p-0">
          <legend className="text-sm font-medium">Select month <span aria-hidden="true" className="text-destructive">*</span></legend>
          <div className="rounded-lg border p-3" data-testid="unpaid-month-picker">
            <div className="mb-3 flex items-center justify-between">
              <Button type="button" variant="ghost" size="sm" aria-label={`Previous year, ${year - 1}`} onClick={() => setYear((value) => value - 1)}>Previous</Button>
              <span className="font-semibold tabular-nums" aria-live="polite">{year}</span>
              <Button type="button" variant="ghost" size="sm" aria-label={`Next year, ${year + 1}`} onClick={() => setYear((value) => value + 1)}>Next</Button>
            </div>
            <div className="grid grid-cols-3 gap-2">
              {MONTH_LONG_NAMES.map((name, index) => {
                const key = monthKey(year, index);
                const selected = field.value === key;
                return <button key={key} type="button" aria-pressed={selected} onClick={() => field.onChange(key)} className={cn("rounded-full border px-2 py-2 text-xs font-medium focus-visible:ring-2 focus-visible:ring-ring", selected ? "border-primary bg-primary text-primary-foreground" : "border-border hover:bg-muted")}>{name.slice(0, 3)}</button>;
              })}
            </div>
          </div>
          {errors.billingMonth ? <p role="alert" className="text-xs text-destructive">{errors.billingMonth.message}</p> : null}
        </fieldset>
      )} />
      <div className="flex justify-end gap-2 border-t pt-4"><Button type="button" variant="outline" onClick={cancel}>{runMutation.isPending ? "Cancel search" : "Cancel"}</Button><Button type="submit" disabled={runMutation.isPending}><Search aria-hidden="true" />{runMutation.isPending ? "Searching…" : "Search"}</Button></div>
    </form>
    <section aria-label="Report results" aria-busy={runMutation.isPending}><div ref={paneRef} tabIndex={-1} className="outline-none">{runMutation.isPending ? <LoadingState label="Generating report…" /> : runMutation.isError && !report ? <ReportRunError error={runMutation.error} narrowHint="Try another course or month" fieldLabels={FIELD_LABELS} onRetry={() => void handleSubmit(run)()} /> : !report ? <EmptyState title="Choose a course and billing month" description="Select one course, choose the purchased billing month, then choose Search." /> : report.unpaidCount === 0 ? <EmptyState title="No unpaid students match these filters" description="All current course participants have full cash payment coverage for this billing month, or adjust the filters." action={{ label: "Change filters", onClick: () => document.getElementById("unpaid-course")?.focus() }} /> : <UnpaidStudentsResults ref={headingRef} report={report} rows={displayedRows} rowsLoading={rowsQuery.isFetching} rowsError={!rowsExpired && rowsQuery.isError} stale={stale} expired={expired} downloading={downloading} downloadError={downloadError} onExport={() => void exportPdf()} onRunAgain={() => void handleSubmit(run)()} onRetryRows={() => void rowsQuery.refetch()} onPageChange={setPage} />}</div></section>
  </div>;
}
