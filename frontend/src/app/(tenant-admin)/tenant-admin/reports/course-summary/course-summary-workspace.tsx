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
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { isApiClientError } from "@/lib/api/error";
import { reportDownloadErrorMessage, useDownloadReportPdf } from "@/lib/api/reports";
import { courseSummaryPdfPath, useCourseSummaryCourses, useCreateCourseSummaryRun, type CourseSummaryOption, type CourseSummaryOptions, type CourseSummaryReport } from "@/lib/api/course-summary-report";
import { courseSummaryFieldFor, courseSummarySchema, DEFAULT_COURSE_SUMMARY, toCourseSummaryRunRequest, type CourseSummaryFormValues } from "@/lib/validation/course-summary-report";

export function CourseSummaryWorkspace({ options }: { options: CourseSummaryOptions }) {
  const [step, setStep] = useState(1); const [teacherSearch, setTeacherSearch] = useState(""); const [courseSearch, setCourseSearch] = useState("");
  const [report, setReport] = useState<CourseSummaryReport | null>(null); const [announcement, setAnnouncement] = useState(""); const [downloadError, setDownloadError] = useState<string | null>(null); const [expired, setExpired] = useState(false);
  const requestRef = useRef(0); const abortRef = useRef<AbortController | null>(null); const errorsRef = useRef<HTMLDivElement>(null);
  const createRun = useCreateCourseSummaryRun(); const download = useDownloadReportPdf();
  const { control, handleSubmit, reset, setValue, setError, trigger, clearErrors, formState: { errors } } = useForm<CourseSummaryFormValues>({ resolver: zodResolver(courseSummarySchema), defaultValues: DEFAULT_COURSE_SUMMARY, mode: "onSubmit", reValidateMode: "onChange" });
  const values = useWatch({ control }) as CourseSummaryFormValues;
  const coursesQuery = useCourseSummaryCourses(values.teacherId);
  const teachers = useMemo(() => options.teachers.filter((item) => item.name.toLowerCase().includes(teacherSearch.toLowerCase().trim())), [options.teachers, teacherSearch]);
  const courses = useMemo(() => (coursesQuery.data?.courses ?? []).filter((item) => item.name.toLowerCase().includes(courseSearch.toLowerCase().trim())), [coursesQuery.data, courseSearch]);
  const selectedTeacher = options.teachers.find((item) => item.id === values.teacherId) ?? null;
  const selectedCourse = coursesQuery.data?.courses.find((item) => item.id === values.courseId) ?? null;
  const currentKey = JSON.stringify(toCourseSummaryRunRequest(values));
  const reportKey = report ? JSON.stringify({ teacherId: report.filters.teacherId, courseId: report.filters.courseId, startDate: report.filters.startDate, endDate: report.filters.endDate, month: report.filters.month, reportName: report.filters.reportName }) : null;
  const stale = !!report && reportKey !== currentKey;

  const chooseTeacher = (id: string) => {
    if (id !== values.teacherId) {
      setValue("teacherId", id, { shouldValidate: true });
      setValue("courseId", "");
      clearErrors("courseId");
      setCourseSearch("");
      setReport(null);
      setStep(2);
    }
  };
  const chooseCourse = (id: string) => { setValue("courseId", id, { shouldValidate: true }); setReport(null); setStep(3); };
  const next = async () => { const fields: Array<keyof CourseSummaryFormValues> = step === 1 ? ["teacherId"] : step === 2 ? ["courseId"] : ["startDate", "endDate", "month", "reportName"]; if (await trigger(fields)) setStep(Math.min(3, step + 1)); else requestAnimationFrame(() => errorsRef.current?.focus()); };

  const submit = async (form: CourseSummaryFormValues) => {
    const token = ++requestRef.current; abortRef.current?.abort(); const controller = new AbortController(); abortRef.current = controller; setDownloadError(null); setAnnouncement("Generating Course Summary report…");
    try { const nextReport = await createRun.mutateAsync({ request: toCourseSummaryRunRequest(form), signal: controller.signal }); if (token !== requestRef.current) return; setReport(nextReport); setExpired(false); await download.mutateAsync({ path: courseSummaryPdfPath(nextReport.runId), filename: "course-summary-report.pdf" }); setAnnouncement("Course Summary report downloaded."); }
    catch (error) { if (token !== requestRef.current) return; if (isApiClientError(error) && error.status === 422) { for (const item of error.fieldErrors) { const field = courseSummaryFieldFor(item.field); if (field) setError(field, { type: "server", message: item.message }); } requestAnimationFrame(() => errorsRef.current?.focus()); } else { setDownloadError(reportDownloadErrorMessage(error, "Choose a shorter date range")); } }
  };
  const cancel = () => { requestRef.current++; abortRef.current?.abort(); createRun.reset(); reset(DEFAULT_COURSE_SUMMARY); clearErrors(); setStep(1); setTeacherSearch(""); setCourseSearch(""); setReport(null); setAnnouncement("Course Summary cancelled."); };
  const downloadAgain = async () => { if (!report || stale || expired) return; try { await download.mutateAsync({ path: courseSummaryPdfPath(report.runId), filename: "course-summary-report.pdf" }); } catch (error) { if (isApiClientError(error) && error.status === 404) setExpired(true); setDownloadError(reportDownloadErrorMessage(error)); } };

  return <div>
    <LiveRegion message={announcement} />
    <ol aria-label="Course Summary progress" className="mb-4 grid grid-cols-1 gap-2 text-sm sm:grid-cols-3">{["Select teacher", "Select course", "Report parameters"].map((name, index) => <li key={name} aria-current={step === index + 1 ? "step" : undefined} className={`rounded-md border p-2 ${step === index + 1 ? "border-primary bg-primary/10 font-semibold" : "border-border"}`}>{index + 1}. {name}</li>)}</ol>
    <form noValidate onSubmit={(event) => void handleSubmit(submit, () => requestAnimationFrame(() => errorsRef.current?.focus()))(event)} aria-label="Course Summary parameters">
      <div className="grid gap-4 lg:grid-cols-3">
        <Picker title="1. SELECT TEACHER" searchLabel="Search teachers" search={teacherSearch} setSearch={setTeacherSearch} options={teachers} selected={values.teacherId} choose={chooseTeacher} error={errors.teacherId?.message} active={step === 1} />
        <section className={`rounded-lg border border-border ${step !== 2 ? "max-lg:hidden" : ""}`} aria-label="2. SELECT COURSE"><div className="border-b p-4"><h2 className="font-semibold">2. SELECT COURSE</h2><Label htmlFor="course-summary-course-search" className="sr-only">Search courses</Label><div className="mt-3 flex gap-2"><Search className="mt-2 size-4" aria-hidden="true"/><Input id="course-summary-course-search" value={courseSearch} onChange={(e) => setCourseSearch(e.target.value)} placeholder="Search courses" disabled={!values.teacherId}/></div></div>{!values.teacherId ? <EmptyState title="Select a teacher first" description="Course choices depend on the authorized teacher."/> : <QueryStateBoundary query={coursesQuery} loadingLabel="Loading courses…" loginPath="/login" permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }} genericErrorMessage="Courses could not be loaded." isEmpty={(data) => data.courses.length === 0} emptyState={{ title: "No courses assigned", description: "This teacher has no available courses." }}>{() => <RadioList options={courses} selected={values.courseId} choose={chooseCourse} error={errors.courseId?.message} empty="No courses match your search." />}</QueryStateBoundary>}</section>
        <section className={`rounded-lg border border-border p-4 ${step !== 3 ? "max-lg:hidden" : ""}`} aria-label="3. REPORT PARAMETERS"><h2 className="font-semibold">3. REPORT PARAMETERS</h2><div className="mt-3 rounded-md bg-muted p-3 text-sm"><p>Teacher: <strong>{selectedTeacher?.name ?? "Not selected"}</strong></p><p>Course: <strong>{selectedCourse?.name ?? "Not selected"}</strong></p></div><div className="mt-4 grid gap-3"><Field control={control} name="startDate" label="Start date" type="date" error={errors.startDate?.message}/><Field control={control} name="endDate" label="End date" type="date" error={errors.endDate?.message}/><Field control={control} name="month" label="Month" type="month" error={errors.month?.message}/><Field control={control} name="reportName" label="Report name" type="text" error={errors.reportName?.message}/></div><div className="mt-4 rounded-md border p-3 text-sm" aria-label="Final parameter summary"><h3 className="font-medium">Final summary</h3><p>{selectedTeacher?.name ?? "Teacher required"} · {selectedCourse?.name ?? "Course required"}</p><p>{values.startDate || "Start date"} to {values.endDate || "End date"} · {values.month || "Month"}</p></div></section>
      </div>
      {Object.keys(errors).length ? <div ref={errorsRef} tabIndex={-1} className="mt-4 outline-none"><Alert variant="destructive"><AlertDescription>Check required Course Summary parameters.</AlertDescription></Alert></div> : null}
      <div className="mt-4 flex flex-wrap justify-end gap-2"><Button type="button" variant="outline" onClick={cancel}>{createRun.isPending ? "Cancel generation" : "Cancel"}</Button>{step > 1 ? <Button type="button" variant="outline" onClick={() => setStep(step - 1)}>Back</Button> : null}{step < 3 ? <Button type="button" onClick={() => void next()}>Next</Button> : <Button type="submit" disabled={createRun.isPending || download.isPending}><FileText aria-hidden="true"/>{createRun.isPending ? "Generating…" : "Generate report"}</Button>}</div>
    </form>
    {createRun.isPending ? <LoadingState label="Generating Course Summary report…"/> : null}
    {report ? <div className="mt-5 rounded-lg border p-4" data-testid="course-summary-result"><p className="font-medium">{report.title}</p><p className="text-sm text-muted-foreground">{report.kpis.totalStudents} students · {report.kpis.payments} payments · {report.institute.currency} {report.kpis.revenue}</p><Button type="button" variant="outline" className="mt-3" disabled={stale || expired || download.isPending} onClick={() => void downloadAgain()}><Download aria-hidden="true"/>{stale ? "Filters changed" : expired ? "Report expired" : "Download PDF again"}</Button></div> : null}
    {downloadError ? <p role="alert" className="mt-3 text-sm text-destructive">{downloadError}</p> : null}
  </div>;
}

function Picker(props: { title: string; searchLabel: string; search: string; setSearch: (v: string) => void; options: CourseSummaryOption[]; selected: string; choose: (id: string) => void; error?: string; active: boolean }) { return <section className={`rounded-lg border border-border ${!props.active ? "max-lg:hidden" : ""}`} aria-label={props.title}><div className="border-b p-4"><h2 className="font-semibold">{props.title}</h2><Label htmlFor="course-summary-teacher-search" className="sr-only">{props.searchLabel}</Label><div className="mt-3 flex gap-2"><Search className="mt-2 size-4" aria-hidden="true"/><Input id="course-summary-teacher-search" value={props.search} onChange={(e) => props.setSearch(e.target.value)} placeholder={props.searchLabel}/></div></div><RadioList options={props.options} selected={props.selected} choose={props.choose} error={props.error} empty="No teachers match your search."/></section>; }
function RadioList({ options, selected, choose, error, empty }: { options: CourseSummaryOption[]; selected: string; choose: (id: string) => void; error?: string; empty: string }) { const group = `course-summary-options-${options.map((item) => item.id).join("-")}`; return <fieldset className="max-h-96 overflow-y-auto p-2"><legend className="sr-only">Choose one option</legend>{options.length ? options.map((item) => <label key={item.id} className="flex cursor-pointer gap-3 rounded-md p-3 hover:bg-muted has-[:checked]:bg-primary/10"><input type="radio" name={group} checked={selected === item.id} onChange={() => choose(item.id)} aria-label={item.name}/><span className="truncate font-medium">{item.name}</span></label>) : <EmptyState title={empty} description="Try another search."/>}{error ? <p role="alert" className="p-2 text-xs text-destructive">{error}</p> : null}</fieldset>; }
function Field({ control, name, label, type, error }: { control: ReturnType<typeof useForm<CourseSummaryFormValues>>["control"]; name: keyof CourseSummaryFormValues; label: string; type: string; error?: string }) { const id = `course-summary-${name}`; return <Controller control={control} name={name} render={({ field }) => <div className="grid gap-1"><Label htmlFor={id}>{label} <span className="text-destructive">*</span></Label><Input id={id} type={type} {...field} aria-invalid={!!error}/>{error ? <p role="alert" className="text-xs text-destructive">{error}</p> : null}</div>}/>; }
