import { keepPreviousData, useMutation, useQuery } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";
import { reportKeys } from "./reports";

const BASE = "/v1/reports/unpaid-students";
export const UNPAID_STUDENTS_PAGE_SIZE = 50;

export interface UnpaidStudentsCourseRef {
  id: string;
  name: string;
}

export interface UnpaidStudentsOptions {
  courses: UnpaidStudentsCourseRef[];
}

export interface UnpaidStudentsRunRequest {
  courseId: string;
  billingMonth: string;
  atLeastOneAttended: boolean;
}

export interface UnpaidStudentsReport {
  runId: string;
  expiresAt: string;
  generatedAt: string;
  timezone: string;
  institute: { name: string };
  course: UnpaidStudentsCourseRef;
  filters: {
    billingMonth: string;
    billingMonthLabel: string;
    atLeastOneAttended: boolean;
  };
  enrollmentBasis: { type: string; asOf: string };
  unpaidCount: number;
}

export interface UnpaidStudentRow {
  studentProfileId: string | null;
  studentId: string;
  /** Stored as text. Leading zeroes are significant. */
  indexNumber: string;
  studentName: string;
  email: string;
  mobile: string;
  attendanceCount: number;
}

export interface UnpaidStudentsRowsPage {
  page: number;
  size: number;
  totalRows: number;
  totalPages: number;
  rows: UnpaidStudentRow[];
}

export const unpaidStudentsKeys = {
  all: [...reportKeys.all, "unpaid-students"] as const,
  options: () => [...unpaidStudentsKeys.all, "options"] as const,
  rows: (runId: string, page: number, size: number) =>
    [...unpaidStudentsKeys.all, "rows", runId, page, size] as const,
};

export function useUnpaidStudentsOptions() {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: unpaidStudentsKeys.options(),
    queryFn: () => authorizedFetch<UnpaidStudentsOptions>("tenant", `${BASE}/options`),
    retry: false,
  });
}

export function useCreateUnpaidStudentsRun() {
  const { authorizedFetch } = useAuth();
  return useMutation({
    mutationFn: ({ request, signal }: { request: UnpaidStudentsRunRequest; signal?: AbortSignal }) =>
      authorizedFetch<UnpaidStudentsReport>("tenant", `${BASE}/runs`, {
        method: "POST",
        body: JSON.stringify(request),
        signal,
      }),
  });
}

export function useUnpaidStudentsRows(runId: string | null, page: number, size = UNPAID_STUDENTS_PAGE_SIZE) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: unpaidStudentsKeys.rows(runId ?? "", page, size),
    queryFn: () =>
      authorizedFetch<UnpaidStudentsRowsPage>(
        "tenant",
        `${BASE}/runs/${encodeURIComponent(runId ?? "")}/rows?page=${page}&size=${size}`
      ),
    enabled: !!runId,
    retry: false,
    staleTime: Infinity,
    gcTime: 5 * 60 * 1000,
    placeholderData: keepPreviousData,
  });
}

export function unpaidStudentsPdfPath(runId: string): string {
  return `${BASE}/runs/${encodeURIComponent(runId)}/pdf`;
}
