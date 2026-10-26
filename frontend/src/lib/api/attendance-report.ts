import { useMutation, useQuery } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";
import { reportKeys } from "./reports";

const BASE = "/v1/reports/attendance";

export const ATTENDANCE_REPORT_DEFAULT_NAME = "Attendance Report";
export const ATTENDANCE_REPORT_MAX_NAME_LENGTH = 255;

export interface AttendanceReportCourse {
  id: string;
  name: string;
}

export interface AttendanceReportOptions {
  courses: AttendanceReportCourse[];
}

export interface AttendanceRunRequest {
  courseId: string;
  startDate: string;
  endDate: string;
  reportName?: string;
}

export interface AttendanceReport {
  runId: string;
  expiresAt: string;
  generatedAt: string;
  timezone: string;
  institute: { name: string };
  course: AttendanceReportCourse;
  title: string;
  filters: {
    courseId: string;
    startDate: string;
    endDate: string;
    reportName: string;
  };
  dates: string[];
  rows: Array<{ studentId: string; indexNumber: string; studentName: string; cells: string[] }>;
  totals: { totalRecords: number; present: number; absent: number };
  legend: { present: string; absent: string; unknown: string };
}

export const attendanceReportKeys = {
  all: [...reportKeys.all, "attendance"] as const,
  options: () => [...attendanceReportKeys.all, "options"] as const,
};

/** Server scopes options and runs to authenticated tenant/user; client never sends tenant ID. */
export function useAttendanceReportOptions() {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: attendanceReportKeys.options(),
    queryFn: () => authorizedFetch<AttendanceReportOptions>("tenant", `${BASE}/options`),
    retry: false,
  });
}

export function useCreateAttendanceReportRun() {
  const { authorizedFetch } = useAuth();
  return useMutation({
    mutationFn: ({ request, signal }: { request: AttendanceRunRequest; signal?: AbortSignal }) =>
      authorizedFetch<AttendanceReport>("tenant", `${BASE}/runs`, {
        method: "POST",
        body: JSON.stringify(request),
        signal,
      }),
  });
}

export function attendanceReportPdfPath(runId: string): string {
  return `${BASE}/runs/${encodeURIComponent(runId)}/pdf`;
}
