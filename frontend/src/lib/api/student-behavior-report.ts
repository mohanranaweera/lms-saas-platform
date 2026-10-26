import { useMutation, useQuery } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";
import { reportKeys } from "./reports";

const BASE = "/v1/reports/student-behavior";
export interface BehaviorCourseRef { id: string; name: string }
export interface StudentBehaviorOptions { courses: BehaviorCourseRef[] }
export interface StudentBehaviorRunRequest { courseId: string; billingMonth: string }
export interface StudentBehaviorReport {
  runId: string; expiresAt: string; generatedAt: string; timezone: string;
  institute: { name: string }; course: BehaviorCourseRef;
  filters: { billingMonth: string; billingMonthLabel: string };
  classification: { basis: string; comparisonMonth: string; historicalAvailability: string; provisional: boolean; asOf: string };
  counts: { newStudents: number; droppedStudents: number; continuingStudents: number };
}
export interface StudentBehaviorRow { studentProfileId: string | null; studentId: string; indexNumber: string; studentName: string; email: string; mobile: string }
export interface StudentBehaviorRows { newStudents: StudentBehaviorRow[]; droppedStudents: StudentBehaviorRow[]; continuingStudents: StudentBehaviorRow[] }
export const behaviorKeys = { all: [...reportKeys.all, "student-behavior"] as const, options: () => [...behaviorKeys.all, "options"] as const, rows: (runId: string) => [...behaviorKeys.all, "rows", runId] as const };
export function useStudentBehaviorOptions() { const { authorizedFetch } = useAuth(); return useQuery({ queryKey: behaviorKeys.options(), queryFn: () => authorizedFetch<StudentBehaviorOptions>("tenant", `${BASE}/options`), retry: false }); }
export function useCreateStudentBehaviorRun() { const { authorizedFetch } = useAuth(); return useMutation({ mutationFn: ({ request, signal }: { request: StudentBehaviorRunRequest; signal?: AbortSignal }) => authorizedFetch<StudentBehaviorReport>("tenant", `${BASE}/runs`, { method: "POST", body: JSON.stringify(request), signal }) }); }
export function useStudentBehaviorRows(runId: string | null) { const { authorizedFetch } = useAuth(); return useQuery({ queryKey: behaviorKeys.rows(runId ?? ""), queryFn: () => authorizedFetch<StudentBehaviorRows>("tenant", `${BASE}/runs/${encodeURIComponent(runId ?? "")}/rows`), enabled: !!runId, retry: false, staleTime: Infinity }); }
export function studentBehaviorPdfPath(runId: string) { return `${BASE}/runs/${encodeURIComponent(runId)}/pdf`; }
