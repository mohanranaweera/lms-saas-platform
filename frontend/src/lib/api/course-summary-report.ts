import { useMutation, useQuery } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";
import { reportKeys } from "./reports";

const BASE = "/v1/reports/course-summary";
export const COURSE_SUMMARY_DEFAULT_NAME = "Teacher Report";
export const COURSE_SUMMARY_MAX_NAME_LENGTH = 255;

export interface CourseSummaryOption { id: string; name: string }
export interface CourseSummaryOptions { teachers: CourseSummaryOption[] }
export interface CourseSummaryCourseOptions { courses: CourseSummaryOption[] }
export interface CourseSummaryRunRequest { teacherId: string; courseId: string; startDate: string; endDate: string; month: string; reportName?: string }
export interface CourseSummaryReport {
  runId: string; expiresAt: string; generatedAt: string; timezone: string;
  institute: { name: string; currency: string }; teacher: CourseSummaryOption; course: CourseSummaryOption; title: string;
  filters: { teacherId: string; courseId: string; startDate: string; endDate: string; month: string; reportName: string };
  kpis: { totalStudents: number; newRegistrations: number; payments: number; commissionPercent: string; classes: number; attendance: number; revenue: string; commission: string; teacherShare: string };
  weekly: Array<{ week: number; days: string; newRegistrations: number; classes: number; attendance: number; payments: number; revenue: string; commission: string; teacherShare: string }>;
  cumulative: Array<{ category: string; count: number; unitPrice: string; grossListValue: string; commission: string; teacherShare: string; tracked: boolean }>;
  notes: string[];
}

export const courseSummaryKeys = {
  all: [...reportKeys.all, "course-summary"] as const,
  options: () => [...courseSummaryKeys.all, "options"] as const,
  courses: (teacherId: string) => [...courseSummaryKeys.all, "courses", teacherId] as const,
};

export function useCourseSummaryOptions() {
  const { authorizedFetch } = useAuth();
  return useQuery({ queryKey: courseSummaryKeys.options(), queryFn: () => authorizedFetch<CourseSummaryOptions>("tenant", `${BASE}/options`), retry: false });
}

export function useCourseSummaryCourses(teacherId: string) {
  const { authorizedFetch } = useAuth();
  return useQuery({ queryKey: courseSummaryKeys.courses(teacherId), enabled: !!teacherId, retry: false,
    queryFn: () => authorizedFetch<CourseSummaryCourseOptions>("tenant", `${BASE}/options/teachers/${encodeURIComponent(teacherId)}/courses`) });
}

export function useCreateCourseSummaryRun() {
  const { authorizedFetch } = useAuth();
  return useMutation({ mutationFn: ({ request, signal }: { request: CourseSummaryRunRequest; signal?: AbortSignal }) =>
    authorizedFetch<CourseSummaryReport>("tenant", `${BASE}/runs`, { method: "POST", body: JSON.stringify(request), signal }) });
}

export function courseSummaryPdfPath(runId: string): string { return `${BASE}/runs/${encodeURIComponent(runId)}/pdf`; }
