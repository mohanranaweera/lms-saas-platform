"use client";

import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useCallback } from "react";
import { useAuth } from "@/lib/auth/auth-context";
import { sendWithAuth, toApiError } from "@/lib/api/student-management";

/**
 * TUTE-01 Tute Management & Tute Dispatch client
 * (`docs/specs/tute-management-dispatch.md` §9; backend `tutemanagement.web`).
 * Every endpoint is capability-gated and course-scoped server-side; nothing
 * here decides access. Tutes never create orders, payments or entitlements.
 */

export type TutePaymentType = "COVERED_BY_CLASS_FEES" | "PAID" | "FREE";
export type TuteStatus = "ACTIVE" | "ARCHIVED";
export type FulfillmentStatus = "PENDING" | "DISPATCHED" | "CANCELLED";
export type FulfillmentMethod = "POSTAL" | "COLLECTION";
export type FulfillmentSource = "MANUAL" | "PAYMENT";
type Money = number | string;

export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/** Mirrors `TuteViews.TuteView`. */
export interface TuteView {
  id: string;
  courseId: string;
  name: string;
  description: string | null;
  quantity: number;
  reserved: number;
  dispatched: number;
  available: number;
  paymentType: TutePaymentType;
  price: Money | null;
  currency: string | null;
  paidLessonId: string | null;
  paidLessonTitle: string | null;
  status: TuteStatus;
  archivedAt: string | null;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface PaidSessionView {
  lessonId: string;
  title: string;
  lessonLabel: string | null;
  moduleTitle: string | null;
  price: Money | null;
  currency: string | null;
  startsAt: string | null;
}

/** Mirrors `TuteRequest` (create / edit body). */
export interface TuteRequest {
  name: string;
  quantity: number;
  paidLessonId: string | null;
  paymentType: TutePaymentType;
  price: number | null;
  description: string | null;
  version?: number;
}

export interface FulfillmentView {
  id: string;
  tuteId: string;
  courseId: string;
  studentId: string;
  studentProfileId: string | null;
  indexNumber: string | null;
  studentName: string;
  city: string | null;
  addressMissing: boolean;
  status: FulfillmentStatus;
  method: FulfillmentMethod;
  source: FulfillmentSource;
  tuteName: string;
  paymentType: TutePaymentType;
  price: Money | null;
  currency: string | null;
  held: boolean;
  paymentReversedAt: string | null;
  holdReleasedAt: string | null;
  createdAt: string;
  dispatchedAt: string | null;
  cancelledAt: string | null;
  cancelReason: string | null;
}

export interface EligibleStudentView {
  studentId: string;
  studentProfileId: string | null;
  indexNumber: string | null;
  name: string;
  alreadyAssigned: boolean;
}

export interface SyncResultView {
  added: number;
  alreadyAssigned: number;
  skippedOutOfStock: number;
  held: number;
}

export interface AssignResultView {
  added: number;
  alreadyAssigned: number;
  available: number;
}

export interface DeleteResultView {
  result: "DELETED" | "ARCHIVED";
}

export interface DispatchItemView {
  fulfillmentId: string;
  tuteId: string;
  tuteName: string;
  courseId: string;
  courseName: string;
  label: string;
}

export interface DispatchRowView {
  groupKey: string;
  studentId: string;
  studentProfileId: string | null;
  indexNumber: string | null;
  studentName: string;
  city: string | null;
  addressMissing: boolean;
  tuteCount: number;
  items: DispatchItemView[];
}

export interface DispatchQueueView extends PageResponse<DispatchRowView> {
  missingAddressCount: number;
  itemCount: number;
}

export interface DispatchFilterOptions {
  courses: { id: string; name: string }[];
  tutes: { id: string; name: string; courseId: string }[];
}

export interface ConfirmDispatchView {
  dispatched: number;
  alreadyDispatched: number;
  dispatchedIds: string[];
  alreadyDispatchedIds: string[];
}

export type DispatchSort = "indexNumber" | "studentName" | "tuteCount" | "city";
export type SortDirection = "asc" | "desc";

export interface DispatchFilters {
  search: string;
  courseId: string | null;
  tuteId: string | null;
  twoPlus: boolean;
}

export interface DispatchQueueParams extends DispatchFilters {
  sort: DispatchSort;
  direction: SortDirection;
  page: number;
  size: number;
}

export type LabelsRequest =
  | { fulfillmentIds: string[] }
  | {
      allFiltered: true;
      filters: { search: string | null; courseId: string | null; tuteId: string | null; twoPlus: boolean };
      expectedLabelCount: number;
    };

export type FulfillmentStatusAction =
  | { action: "MARK_HANDED_OVER" }
  | { action: "CHANGE_METHOD"; method: FulfillmentMethod }
  | { action: "RELEASE_HOLD"; reason: string };

// ---------------------------------------------------------------------------
// Query keys
// ---------------------------------------------------------------------------

export const tuteKeys = {
  all: ["tutes"] as const,
  course: (courseId: string) => ["tutes", courseId] as const,
  list: (courseId: string, params: { search: string; includeArchived: boolean; page: number; size: number }) =>
    ["tutes", courseId, "list", params] as const,
  paidSessions: (courseId: string, search: string) => ["tutes", courseId, "paid-sessions", search] as const,
  detail: (courseId: string, tuteId: string) => ["tutes", courseId, "detail", tuteId] as const,
  fulfillments: (courseId: string, tuteId: string, params: { status: string; search: string; page: number; size: number }) =>
    ["tutes", courseId, "fulfillments", tuteId, params] as const,
  eligible: (courseId: string, tuteId: string, search: string) => ["tutes", courseId, "eligible", tuteId, search] as const,
  dispatch: ["tute-dispatch"] as const,
  queue: (params: DispatchQueueParams) => ["tute-dispatch", "queue", params] as const,
  filterOptions: ["tute-dispatch", "filter-options"] as const,
};

function qs(params: Record<string, string | number | boolean | null | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === null || value === undefined || value === "") continue;
    search.set(key, String(value));
  }
  const text = search.toString();
  return text ? `?${text}` : "";
}

const base = (courseId: string) => `/v1/courses/${encodeURIComponent(courseId)}/tutes`;
const tuteBase = (courseId: string, tuteId: string) => `${base(courseId)}/${encodeURIComponent(tuteId)}`;

// ---------------------------------------------------------------------------
// Course tutes
// ---------------------------------------------------------------------------

export function useCourseTutes(
  courseId: string,
  params: { search: string; includeArchived: boolean; page: number; size: number },
  options: { enabled?: boolean } = {}
) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: tuteKeys.list(courseId, params),
    queryFn: () => authorizedFetch<PageResponse<TuteView>>("tenant", `${base(courseId)}${qs({ ...params })}`),
    enabled: courseId.length > 0 && (options.enabled ?? true),
    placeholderData: keepPreviousData,
  });
}

export function usePaidSessions(courseId: string, search: string, enabled: boolean) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: tuteKeys.paidSessions(courseId, search),
    queryFn: () => authorizedFetch<PaidSessionView[]>("tenant", `${base(courseId)}/paid-sessions${qs({ search })}`),
    enabled: enabled && courseId.length > 0,
    placeholderData: keepPreviousData,
  });
}

export function useTute(courseId: string, tuteId: string) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: tuteKeys.detail(courseId, tuteId),
    queryFn: () => authorizedFetch<TuteView>("tenant", tuteBase(courseId, tuteId)),
    enabled: courseId.length > 0 && tuteId.length > 0,
  });
}

function useInvalidateTutes(courseId: string) {
  const queryClient = useQueryClient();
  return useCallback(async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: tuteKeys.course(courseId) }),
      queryClient.invalidateQueries({ queryKey: tuteKeys.dispatch }),
    ]);
  }, [queryClient, courseId]);
}

export function useCreateTute(courseId: string) {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateTutes(courseId);
  return useMutation({
    mutationFn: (body: TuteRequest) =>
      authorizedFetch<TuteView>("tenant", base(courseId), { method: "POST", body: JSON.stringify(body) }),
    onSuccess: invalidate,
  });
}

export function useUpdateTute(courseId: string) {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateTutes(courseId);
  return useMutation({
    mutationFn: ({ tuteId, body }: { tuteId: string; body: TuteRequest }) =>
      authorizedFetch<TuteView>("tenant", tuteBase(courseId, tuteId), { method: "PUT", body: JSON.stringify(body) }),
    onSuccess: invalidate,
  });
}

export function useDeleteTute(courseId: string) {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateTutes(courseId);
  return useMutation({
    mutationFn: (tuteId: string) =>
      authorizedFetch<DeleteResultView>("tenant", tuteBase(courseId, tuteId), { method: "DELETE" }),
    onSuccess: invalidate,
  });
}

export function useSyncTutePayments(courseId: string, tuteId: string) {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateTutes(courseId);
  return useMutation({
    mutationFn: () =>
      authorizedFetch<SyncResultView>("tenant", `${tuteBase(courseId, tuteId)}/sync-payments`, { method: "POST" }),
    onSuccess: invalidate,
  });
}

// ---------------------------------------------------------------------------
// Fulfillments (assigned students)
// ---------------------------------------------------------------------------

export function useTuteFulfillments(
  courseId: string,
  tuteId: string,
  params: { status: string; search: string; page: number; size: number }
) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: tuteKeys.fulfillments(courseId, tuteId, params),
    queryFn: () =>
      authorizedFetch<PageResponse<FulfillmentView>>("tenant", `${tuteBase(courseId, tuteId)}/fulfillments${qs({ ...params })}`),
    enabled: courseId.length > 0 && tuteId.length > 0,
    placeholderData: keepPreviousData,
  });
}

export function useEligibleStudents(courseId: string, tuteId: string, search: string, enabled: boolean) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: tuteKeys.eligible(courseId, tuteId, search),
    queryFn: () =>
      authorizedFetch<EligibleStudentView[]>("tenant", `${tuteBase(courseId, tuteId)}/eligible-students${qs({ search })}`),
    enabled,
    placeholderData: keepPreviousData,
  });
}

export function useAssignFulfillments(courseId: string, tuteId: string) {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateTutes(courseId);
  return useMutation({
    mutationFn: (body: { studentIds: string[]; method: FulfillmentMethod }) =>
      authorizedFetch<AssignResultView>("tenant", `${tuteBase(courseId, tuteId)}/fulfillments`, {
        method: "POST",
        body: JSON.stringify(body),
      }),
    onSuccess: invalidate,
  });
}

export function useCancelFulfillment(courseId: string, tuteId: string) {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateTutes(courseId);
  return useMutation({
    mutationFn: ({ fulfillmentId, reason }: { fulfillmentId: string; reason: string }) =>
      authorizedFetch<FulfillmentView>(
        "tenant",
        `${tuteBase(courseId, tuteId)}/fulfillments/${encodeURIComponent(fulfillmentId)}/cancel`,
        { method: "POST", body: JSON.stringify({ reason }) }
      ),
    onSuccess: invalidate,
  });
}

export function useFulfillmentStatus(courseId: string, tuteId: string) {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateTutes(courseId);
  return useMutation({
    mutationFn: ({ fulfillmentId, body }: { fulfillmentId: string; body: FulfillmentStatusAction }) =>
      authorizedFetch<FulfillmentView>(
        "tenant",
        `${tuteBase(courseId, tuteId)}/fulfillments/${encodeURIComponent(fulfillmentId)}/status`,
        { method: "POST", body: JSON.stringify(body) }
      ),
    onSuccess: invalidate,
  });
}

// ---------------------------------------------------------------------------
// Tute Dispatch
// ---------------------------------------------------------------------------

export function dispatchQueuePath(params: DispatchQueueParams): string {
  return `/v1/tute-dispatch/queue${qs({
    search: params.search.trim(),
    courseId: params.courseId,
    tuteId: params.tuteId,
    twoPlus: params.twoPlus ? true : null,
    sort: params.sort,
    direction: params.direction,
    page: params.page,
    size: params.size,
  })}`;
}

export function useDispatchQueue(params: DispatchQueueParams, options: { enabled?: boolean } = {}) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: tuteKeys.queue(params),
    queryFn: () => authorizedFetch<DispatchQueueView>("tenant", dispatchQueuePath(params)),
    enabled: options.enabled ?? true,
    placeholderData: keepPreviousData,
  });
}

export function useDispatchFilterOptions(options: { enabled?: boolean } = {}) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: tuteKeys.filterOptions,
    queryFn: () => authorizedFetch<DispatchFilterOptions>("tenant", "/v1/tute-dispatch/filter-options"),
    enabled: options.enabled ?? true,
    staleTime: 60_000,
  });
}

export interface LabelsDownload {
  blob: Blob;
  /** `X-Label-Count` when the browser can read it (it may not be CORS-exposed); `null` otherwise. */
  labelCount: number | null;
}

/**
 * `POST /tute-dispatch/labels` → PDF. Printing never changes status, stock or
 * payments (spec §7); the bytes go straight to a short-lived object URL.
 */
export function usePrintDispatchLabels() {
  const { ensureAccessToken } = useAuth();
  return useMutation({
    mutationFn: async (body: LabelsRequest): Promise<LabelsDownload> => {
      const response = await sendWithAuth(ensureAccessToken, "/v1/tute-dispatch/labels", {
        method: "POST",
        headers: { "Content-Type": "application/json", Accept: "application/pdf, application/json" },
        body: JSON.stringify(body),
      });
      if (!response.ok) throw await toApiError(response);
      const header = response.headers.get("X-Label-Count");
      const parsed = header === null ? NaN : Number(header);
      return { blob: await response.blob(), labelCount: Number.isFinite(parsed) ? parsed : null };
    },
  });
}

export function useConfirmDispatch() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (fulfillmentIds: string[]) =>
      authorizedFetch<ConfirmDispatchView>("tenant", "/v1/tute-dispatch/confirm", {
        method: "POST",
        body: JSON.stringify({ fulfillmentIds }),
      }),
    onSettled: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: tuteKeys.all }),
        queryClient.invalidateQueries({ queryKey: tuteKeys.dispatch }),
      ]),
  });
}
