"use client";

import { useMemo, useRef, useState } from "react";
import { Controller, useForm, useWatch } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Download, FileText, Search } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { LiveRegion } from "@/components/ui/live-region";
import { EmptyState } from "@/components/states/empty-state";
import { LoadingState } from "@/components/states/loading-state";
import { ReportRunError, reportRunErrorMessage } from "@/components/reports/report-run-error";
import { isApiClientError } from "@/lib/api/error";
import { reportDownloadErrorMessage, useDownloadReportPdf } from "@/lib/api/reports";
import {
  attendanceReportPdfPath,
  useCreateAttendanceReportRun,
  type AttendanceReport,
  type AttendanceReportOptions,
} from "@/lib/api/attendance-report";
import {
  attendanceFieldFor,
  attendanceReportSchema,
  DEFAULT_ATTENDANCE_REPORT,
  toAttendanceRunRequest,
  type AttendanceReportFormValues,
} from "@/lib/validation/attendance-report";

const FIELD_LABELS: Record<string, string> = {
  courseId: "Course",
  startDate: "Start date",
  endDate: "End date",
  reportName: "Report name",
  period: "Date range",
};

export function AttendanceReportWorkspace({ options }: { options: AttendanceReportOptions }) {
  const createRun = useCreateAttendanceReportRun();
  const download = useDownloadReportPdf();
  const [report, setReport] = useState<AttendanceReport | null>(null);
  const [search, setSearch] = useState("");
  const [announcement, setAnnouncement] = useState("");
  const [downloadError, setDownloadError] = useState<string | null>(null);
  const [expired, setExpired] = useState(false);
  const [serverErrors, setServerErrors] = useState<string[]>([]);
  const abortRef = useRef<AbortController | null>(null);
  const requestRef = useRef(0);
  const errorRef = useRef<HTMLDivElement>(null);

  const {
    control,
    handleSubmit,
    reset,
    setError,
    clearErrors,
    formState: { errors },
  } = useForm<AttendanceReportFormValues>({
    resolver: zodResolver(attendanceReportSchema),
    defaultValues: DEFAULT_ATTENDANCE_REPORT,
    mode: "onSubmit",
    reValidateMode: "onChange",
  });
  const values = useWatch({ control }) as AttendanceReportFormValues;
  const filteredCourses = useMemo(
    () => options.courses.filter((course) => course.name.toLowerCase().includes(search.trim().toLowerCase())),
    [options.courses, search]
  );
  const selectedCourse = options.courses.find((course) => course.id === values.courseId) ?? null;

  const submit = async (formValues: AttendanceReportFormValues) => {
    const request = toAttendanceRunRequest(formValues);
    const token = ++requestRef.current;
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    setServerErrors([]);
    setDownloadError(null);
    setAnnouncement("Generating attendance report…");
    try {
      const next = await createRun.mutateAsync({ request, signal: controller.signal });
      if (token !== requestRef.current) return;
      setReport(next);
      setExpired(false);
      setAnnouncement("Report ready. Download started.");
      await download.mutateAsync({ path: attendanceReportPdfPath(next.runId), filename: "attendance-report.pdf" });
      setAnnouncement("Attendance report downloaded.");
    } catch (error) {
      if (token !== requestRef.current) return;
      if (isApiClientError(error) && error.status === 422 && error.code === "REPORT_VALIDATION") {
        const summaryErrors: string[] = [];
        for (const fieldError of error.fieldErrors) {
          const field = attendanceFieldFor(fieldError.field);
          if (field) setError(field, { type: "server", message: fieldError.message });
          summaryErrors.push(`${FIELD_LABELS[field ?? fieldError.field] ?? fieldError.field}: ${fieldError.message}`);
        }
        setServerErrors(summaryErrors);
        requestAnimationFrame(() => errorRef.current?.focus());
        return;
      }
      if (isApiClientError(error) && error.status === 404) setExpired(true);
      setAnnouncement(`The report could not be generated. ${reportRunErrorMessage(error, "Choose a shorter date range")}`);
    }
  };

  const onCancel = () => {
    if (createRun.isPending) {
      requestRef.current++;
      abortRef.current?.abort();
      createRun.reset();
      setAnnouncement("Report generation cancelled.");
      return;
    }
    reset(DEFAULT_ATTENDANCE_REPORT);
    clearErrors();
    setSearch("");
    setReport(null);
    setExpired(false);
    setServerErrors([]);
    setDownloadError(null);
    setAnnouncement("Filters cleared.");
  };

  const onDownloadAgain = async () => {
    if (!report || expired || download.isPending) return;
    setDownloadError(null);
    try {
      await download.mutateAsync({ path: attendanceReportPdfPath(report.runId), filename: "attendance-report.pdf" });
      setAnnouncement("Attendance report downloaded.");
    } catch (error) {
      if (isApiClientError(error) && error.status === 404) setExpired(true);
      setDownloadError(reportDownloadErrorMessage(error, "Run the report again"));
    }
  };

  const hasErrors = Object.keys(errors).length > 0 || serverErrors.length > 0;

  return (
    <div className="grid gap-6 lg:grid-cols-[minmax(18rem,1fr)_minmax(20rem,1fr)]">
      <LiveRegion message={announcement} />
      <form noValidate onSubmit={(event) => void handleSubmit(submit, () => requestAnimationFrame(() => errorRef.current?.focus()))(event)} aria-label="Attendance report parameters" className="rounded-lg border border-border">
        <div className="border-b border-border p-4">
          <h2 className="font-semibold">Select course</h2>
          <Label htmlFor="attendance-course-search" className="sr-only">Search courses</Label>
          <div className="mt-3 flex gap-2"><Search className="mt-2 size-4 shrink-0 text-muted-foreground" aria-hidden="true" /><Input id="attendance-course-search" value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Search courses" /></div>
        </div>
        <Controller control={control} name="courseId" render={({ field }) => (
          <fieldset className="max-h-80 overflow-y-auto p-2" aria-invalid={!!errors.courseId}>
            <legend className="sr-only">Choose one course</legend>
            {filteredCourses.length === 0 ? <EmptyState title="No courses match your search" description="Try a different course name." action={search ? { label: "Clear search", onClick: () => setSearch("") } : undefined} /> : filteredCourses.map((course) => (
              <label key={course.id} className="flex cursor-pointer items-center gap-3 rounded-md px-3 py-3 hover:bg-muted has-[:checked]:bg-primary/10"><input type="radio" name={field.name} value={course.id} checked={field.value === course.id} onChange={() => field.onChange(course.id)} className="size-4 accent-primary" /><span className="min-w-0 truncate font-medium">{course.name}</span></label>
            ))}
            {errors.courseId ? <p role="alert" className="px-3 py-1 text-xs text-destructive">{errors.courseId.message}</p> : null}
          </fieldset>
        )} />
      </form>

      <section aria-busy={createRun.isPending} className="rounded-lg border border-border p-4">
        <h2 className="font-semibold">Report parameters</h2>
        {hasErrors ? <div ref={errorRef} tabIndex={-1} className="mt-3 outline-none" data-testid="attendance-filter-errors"><Alert variant="destructive"><AlertDescription>Check the report parameters.{serverErrors.length > 0 ? <ul className="mt-1 list-disc pl-4">{serverErrors.map((error) => <li key={error}>{error}</li>)}</ul> : null}</AlertDescription></Alert></div> : null}
        <div className="mt-4 rounded-md bg-muted p-3"><p className="text-xs text-muted-foreground">Selected course</p><p className="mt-1 font-medium">{selectedCourse?.name ?? "Choose a course"}</p></div>
        <div className="mt-4 grid gap-4 sm:grid-cols-2">
          <Controller control={control} name="startDate" render={({ field }) => <div className="grid gap-1.5"><Label htmlFor="attendance-start-date">Start date <span className="text-destructive">*</span></Label><Input id="attendance-start-date" type="date" {...field} aria-invalid={!!errors.startDate} />{errors.startDate ? <p role="alert" className="text-xs text-destructive">{errors.startDate.message}</p> : null}</div>} />
          <Controller control={control} name="endDate" render={({ field }) => <div className="grid gap-1.5"><Label htmlFor="attendance-end-date">End date <span className="text-destructive">*</span></Label><Input id="attendance-end-date" type="date" min={values.startDate || undefined} {...field} aria-invalid={!!errors.endDate} />{errors.endDate ? <p role="alert" className="text-xs text-destructive">{errors.endDate.message}</p> : null}</div>} />
        </div>
        <Controller control={control} name="reportName" render={({ field }) => <div className="mt-4 grid gap-1.5"><Label htmlFor="attendance-report-name">Report name</Label><Input id="attendance-report-name" {...field} aria-invalid={!!errors.reportName} />{errors.reportName ? <p role="alert" className="text-xs text-destructive">{errors.reportName.message}</p> : null}</div>} />
        <div className="mt-6 flex flex-wrap justify-end gap-2 border-t border-border pt-4"><Button type="button" variant="outline" onClick={onCancel}>{createRun.isPending ? "Cancel generation" : "Cancel"}</Button><Button type="button" onClick={() => void handleSubmit(submit, () => requestAnimationFrame(() => errorRef.current?.focus()))()} disabled={createRun.isPending || download.isPending}><FileText aria-hidden="true" />{createRun.isPending ? "Generating…" : "Generate report"}</Button></div>
        {createRun.isPending ? <div className="mt-5"><LoadingState label="Generating attendance report…" /></div> : null}
        {createRun.isError ? <div className="mt-5"><ReportRunError error={createRun.error} narrowHint="Choose a shorter date range" fieldLabels={FIELD_LABELS} onRetry={() => void handleSubmit(submit)()} /></div> : null}
        {report && !createRun.isPending ? <div className="mt-5 rounded-md border border-border p-4" data-testid="attendance-run-result"><p className="font-medium">{report.title}</p><p className="mt-1 text-sm text-muted-foreground">{report.totals.totalRecords} students · {report.totals.present} present · {report.totals.absent} absent</p><Button className="mt-3" type="button" variant="outline" onClick={() => void onDownloadAgain()} disabled={expired || download.isPending}><Download aria-hidden="true" />{download.isPending ? "Downloading…" : expired ? "Report expired" : "Download PDF again"}</Button>{downloadError ? <p role="alert" className="mt-2 text-sm text-destructive">{downloadError}</p> : null}</div> : null}
      </section>
    </div>
  );
}
