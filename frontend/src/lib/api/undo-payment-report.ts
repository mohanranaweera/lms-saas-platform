import { keepPreviousData, useMutation, useQuery } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";
import { reportKeys, useDownloadReportPdf } from "./reports";

const BASE = "/v1/reports/undo-payments";
export const UNDO_PAYMENT_PAGE_SIZE = 50;
export interface UndoPaymentCourseRef { id: string; name: string; }
export interface UndoPaymentOptions { courses: UndoPaymentCourseRef[]; }
export interface UndoPaymentRunRequest { courseIds: string[]; startDate: string; endDate: string; }
export interface UndoPaymentReport { runId: string; expiresAt: string; generatedAt: string; timezone: string; institute: { name: string }; filters: { courses: UndoPaymentCourseRef[]; startDate: string; endDate: string; selectedDayCount: number }; rowCount: number; distinctUndoPayments: number; totalAmount: string; }
export interface UndoPaymentRow { refundId: string; studentId: string; studentName: string; indexNumber: string; courseId: string; courseName: string; undoBy: string; undoDate: string; amount: string; paymentDate: string; reason: string; }
export interface UndoPaymentRowsPage { page: number; size: number; totalRows: number; totalPages: number; rows: UndoPaymentRow[]; }
export const undoPaymentKeys = { all: [...reportKeys.all, "undo-payments"] as const, options: () => [...undoPaymentKeys.all, "options"] as const, rows: (runId: string, page: number, size: number) => [...undoPaymentKeys.all, "rows", runId, page, size] as const };
export function useUndoPaymentOptions() { const { authorizedFetch } = useAuth(); return useQuery({ queryKey: undoPaymentKeys.options(), queryFn: () => authorizedFetch<UndoPaymentOptions>("tenant", `${BASE}/options`), retry: false }); }
export function useCreateUndoPaymentRun() { const { authorizedFetch } = useAuth(); return useMutation({ mutationFn: ({ request, signal }: { request: UndoPaymentRunRequest; signal?: AbortSignal }) => authorizedFetch<UndoPaymentReport>("tenant", `${BASE}/runs`, { method: "POST", body: JSON.stringify(request), signal }) }); }
export function useUndoPaymentRows(runId: string | null, page: number, size = UNDO_PAYMENT_PAGE_SIZE) { const { authorizedFetch } = useAuth(); return useQuery({ queryKey: undoPaymentKeys.rows(runId ?? "", page, size), queryFn: () => authorizedFetch<UndoPaymentRowsPage>("tenant", `${BASE}/runs/${encodeURIComponent(runId ?? "")}/rows?page=${page}&size=${size}`), enabled: !!runId, retry: false, staleTime: Infinity, placeholderData: keepPreviousData }); }
export function undoPaymentPdfPath(runId: string) { return `${BASE}/runs/${encodeURIComponent(runId)}/pdf`; }
export { useDownloadReportPdf };
