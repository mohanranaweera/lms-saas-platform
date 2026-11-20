import { keepPreviousData, useMutation, useQuery } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";
import { reportKeys } from "./reports";

const BASE = "/v1/reports/registrations";
export const REGISTRATION_PAGE_SIZE = 50;
export interface RegistrationCourseRef {
  id: string;
  name: string;
}
export interface RegistrationOperatorRef {
  id: string;
  name: string;
}
export interface RegistrationOptions {
  courses: RegistrationCourseRef[];
  operators: RegistrationOperatorRef[];
  registrationMethods: string[];
}
export interface RegistrationRunRequest {
  courseIds?: string[];
  operatorId?: string;
  registrationMethod?: string;
  startDate: string;
  endDate: string;
}
export interface RegistrationReport {
  runId: string;
  expiresAt: string;
  generatedAt: string;
  timezone: string;
  institute: { name: string };
  filters: {
    courses: RegistrationCourseRef[];
    operator: RegistrationOperatorRef | null;
    registrationMethod: string | null;
    startDate: string;
    endDate: string;
    selectedDayCount: number;
  };
  currentFacts: {
    courseAssociationBasis: string;
    lastPaymentDefinition: string;
    asOf: string;
  };
  totalRegistrations: number;
}
export interface RegistrationRow {
  studentProfileId: string;
  studentId: string;
  indexNumber: string;
  studentName: string;
  email: string;
  mobile: string;
  registeredById: string | null;
  registeredBy: string | null;
  registrationMethod: string | null;
  courses: RegistrationCourseRef[];
  lastPayment: string | null;
  registrationDate: string;
}
export interface RegistrationRowsPage {
  page: number;
  size: number;
  totalRows: number;
  totalPages: number;
  rows: RegistrationRow[];
}
export const registrationKeys = {
  all: [...reportKeys.all, "registrations"] as const,
  options: () => [...registrationKeys.all, "options"] as const,
  rows: (runId: string, page: number, size: number) =>
    [...registrationKeys.all, "rows", runId, page, size] as const,
};
export function useRegistrationOptions() {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: registrationKeys.options(),
    queryFn: () =>
      authorizedFetch<RegistrationOptions>("tenant", `${BASE}/options`),
    retry: false,
  });
}
export function useCreateRegistrationRun() {
  const { authorizedFetch } = useAuth();
  return useMutation({
    mutationFn: ({
      request,
      signal,
    }: {
      request: RegistrationRunRequest;
      signal?: AbortSignal;
    }) =>
      authorizedFetch<RegistrationReport>("tenant", `${BASE}/runs`, {
        method: "POST",
        body: JSON.stringify(request),
        signal,
      }),
  });
}
export function useRegistrationRows(
  runId: string | null,
  page: number,
  size = REGISTRATION_PAGE_SIZE,
) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: registrationKeys.rows(runId ?? "", page, size),
    queryFn: () =>
      authorizedFetch<RegistrationRowsPage>(
        "tenant",
        `${BASE}/runs/${encodeURIComponent(runId ?? "")}/rows?page=${page}&size=${size}`,
      ),
    enabled: !!runId,
    retry: false,
    staleTime: Infinity,
    placeholderData: keepPreviousData,
  });
}
export function registrationPdfPath(runId: string) {
  return `${BASE}/runs/${encodeURIComponent(runId)}/pdf`;
}
