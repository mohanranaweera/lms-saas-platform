import { useMutation, useQuery, useQueryClient, type QueryKey } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";
import {
  courseKeys,
  type CourseLessonResponse,
  type CourseModuleResponse,
  type CourseResponse,
  type PageResponse,
} from "@/lib/api/courses";
import { useTenantConfigDomain } from "@/lib/api/tenant-config";

/**
 * Typed client + React Query hooks for the Klass-parity admin course
 * management contract (`docs/plans/klass-enrollment-payment-parity.md` §4.1,
 * ADR-028). Additive to `./courses.ts`: it re-uses that module's query keys
 * (so the existing archive/delete/module/lesson hooks invalidate the same
 * cache entries) and its base types, widening them with the new fields.
 *
 * Nothing here decides access or payment. Registration, price, payment type
 * and ordering are all written through the backend, which authorizes every
 * call from the authenticated tenant context. The UI only reflects what the
 * server returns.
 */

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

/** Course billing model (D09). SESSION/CUSTOM are legacy, readable but never offered on create. */
export type KlassPricingModel =
  | "FREE"
  | "ONE_TIME"
  | "MONTHLY"
  | "DAILY"
  | "MODULE"
  | "INSTALLMENT"
  | "SESSION"
  | "CUSTOM";

/** The six models the Add New Course modal offers, in Klass dropdown order. */
export type CreatablePricingModel = Exclude<KlassPricingModel, "SESSION" | "CUSTOM">;

/** Leaf ("Session") payment policy (D03/D05). */
export type LessonPaymentType = "COVERED_BY_CLASS_FEES" | "PAID" | "FREE";

export type LessonResourceSupport = "INTEGRATED" | "LINK_ONLY" | "UNSUPPORTED";

export interface LessonResourceTypeOption {
  type: string;
  label: string;
  support: LessonResourceSupport;
  help: string;
  liveClass?: boolean;
}

/** Money is a decimal string on the wire; tolerate a number too. */
export type MoneyValue = string | number;

export interface PriceDisplay {
  amount: MoneyValue;
  currency: string;
  period: string | null;
}

export interface CourseTeacherRef {
  id: string;
  name: string;
  primary: boolean;
}

export interface CourseTagRef {
  id: string;
  name: string;
}

export interface LinkedCourseRef {
  id: string;
  title: string;
}

/** `CourseResponse` plus the §4.1 additions. */
export interface KlassCourse extends Omit<CourseResponse, "pricingModel"> {
  pricingModel: KlassPricingModel;
  registrationEnabled: boolean;
  enrollmentFee: MoneyValue | null;
  coverImageUrl: string | null;
  whatsappInviteUrl: string | null;
  telegramInviteUrl: string | null;
  paymentValidDays: number | null;
  commissionRate: MoneyValue | null;
  teachers: CourseTeacherRef[];
  tags: CourseTagRef[];
  linkedCourses: LinkedCourseRef[];
  displayOrder: number | null;
  priceDisplay: PriceDisplay | null;
  /** V90: course facility-fee override; `null` = inherit the institute default (0 = no fee). */
  facilityFeeOverride?: MoneyValue | null;
  /** V90: the institute default facility fee (`PAYMENT.facility_fee_amount`). */
  tenantDefaultFacilityFee?: MoneyValue | null;
  /** V90: the institute facility-fee mode (`PAYMENT.facility_fee_mode`). */
  facilityFeeBasis?: "NONE" | "PER_COURSE" | "PER_LINE" | "PER_ORDER" | null;
  /** V90: the fee this course is charged per basis unit (override or institute default). */
  effectiveFacilityFee?: MoneyValue | null;
  /** V90: the course postage fee (0 = no postage charge). */
  postageFee?: MoneyValue | null;
}

export interface KlassCourseCreateRequest {
  name: string;
  teacherIds: string[];
  pricingModel: CreatablePricingModel;
  price?: string;
  enrollmentFee?: string;
  paymentValidDays?: number;
  commissionRate?: string;
  whatsappInviteUrl?: string;
  telegramInviteUrl?: string;
  description?: string;
  tagNames?: string[];
  linkedCourseIds?: string[];
}

/**
 * `PATCH /api/v1/courses/{id}` body for the course edit form (G2/G3/G4).
 *
 * Semantics (backend `CourseUpdateRequest`/`CourseService#updateCourse`):
 * - With `name`, the legacy fields (`subject`, `stream`, `grade`,
 *   `academicYear`, `description`, `enrollmentRule`, and the access duration
 *   via `paymentValidDays`/`accessDurationDays`) are FULL REPLACEMENT: a
 *   missing value clears it. Callers sending `name` must send the current
 *   value of every legacy field they don't edit.
 * - `slug`/`category`: `null`/absent = unchanged. The edit form never sends slug.
 * - Every other field is "absent/`null` = unchanged"; an empty list clears
 *   tags/links; an empty string clears an invite link; `paymentValidDays: 0`
 *   = no limit.
 * - `teacherIds` are user ids, primary first. Changing the primary is Tenant
 *   Admin only and a Teacher cannot change the list (server-enforced, 403).
 */
export interface KlassCourseUpdateRequest {
  name?: string;
  subject?: string | null;
  stream?: string | null;
  grade?: string | null;
  academicYear?: string | null;
  description?: string | null;
  enrollmentRule?: string | null;
  teacherIds?: string[];
  pricingModel?: CreatablePricingModel;
  price?: string;
  enrollmentFee?: string;
  paymentValidDays?: number;
  commissionRate?: string;
  whatsappInviteUrl?: string;
  telegramInviteUrl?: string;
  tagNames?: string[];
  linkedCourseIds?: string[];
  /** V90: `true` clears the facility-fee override (inherit the institute default). */
  facilityFeeInherit?: boolean;
  /** V90: required when `facilityFeeInherit` is `false`; >= 0, 2 decimals (0 = no fee). */
  facilityFeeOverride?: string;
  /** V90: >= 0, 2 decimals (0 = no postage charge). */
  postageFee?: string;
}

export interface KlassModule extends CourseModuleResponse {
  price: MoneyValue | null;
}

export interface KlassModuleRequest {
  title: string;
  sequence: number;
  price: string | null;
}

export interface KlassLesson extends CourseLessonResponse {
  startsAt: string | null;
  endsAt: string | null;
  resourceType: string | null;
  paymentType: LessonPaymentType;
  price: MoneyValue | null;
  description: string | null;
  lessonLabel: string | null;
  billingYear: number | null;
  billingMonth: number | null;
  expired: boolean;
}

export interface KlassLessonRequest {
  title: string;
  sequence: number;
  startsAt: string | null;
  endsAt: string | null;
  resourceType: string | null;
  paymentType: LessonPaymentType;
  price: string | null;
  description: string | null;
  lessonLabel: string | null;
  billingYear: number | null;
  billingMonth: number | null;
}

// ---------------------------------------------------------------------------
// Labels and help (our implemented rules, not Klass copy)
// ---------------------------------------------------------------------------

export const PRICING_MODEL_LABELS: Record<KlassPricingModel, string> = {
  MONTHLY: "Monthly Fee",
  ONE_TIME: "Full Course",
  DAILY: "Day payments",
  MODULE: "Pay For Module",
  INSTALLMENT: "Installments",
  FREE: "Free",
  SESSION: "Per session (legacy)",
  CUSTOM: "Custom (legacy)",
};

/** Klass dropdown order. */
export const CREATABLE_PRICING_MODELS: CreatablePricingModel[] = [
  "MONTHLY",
  "ONE_TIME",
  "DAILY",
  "MODULE",
  "INSTALLMENT",
  "FREE",
];

const NOT_PURCHASABLE_YET =
  "Can be selected, but students can't buy daily or installment offers yet.";

export const PRICING_MODEL_HELP: Record<CreatablePricingModel, string> = {
  MONTHLY:
    "Students pay per calendar month. Sessions assigned to that month (their Billing month) unlock when the month is paid. Enrollment stays free.",
  ONE_TIME:
    "Students pay once for the whole course. The payment opens every session covered by class fees. Enrollment stays free.",
  DAILY: NOT_PURCHASABLE_YET,
  MODULE:
    "Students pay per module. Set a price on each module; a paid module opens its sessions covered by class fees. Enrollment stays free.",
  INSTALLMENT: NOT_PURCHASABLE_YET,
  FREE: "No course fee. Sessions marked Paid still need payment.",
};

/** Pricing models whose course-level price is entered in the create modal. */
export function pricingModelTakesPrice(model: string): model is "ONE_TIME" | "MONTHLY" {
  return model === "ONE_TIME" || model === "MONTHLY";
}

export const LESSON_PAYMENT_TYPE_LABELS: Record<LessonPaymentType, string> = {
  COVERED_BY_CLASS_FEES: "Covered by class fees",
  PAID: "Paid",
  FREE: "Free",
};

export const LESSON_PAYMENT_TYPES: LessonPaymentType[] = ["COVERED_BY_CLASS_FEES", "PAID", "FREE"];

export const LESSON_PAYMENT_TYPE_HELP: Record<LessonPaymentType, string> = {
  COVERED_BY_CLASS_FEES:
    "Opens with the course's class fee: the paid month (Monthly Fee), the paid module (Pay For Module) or the full-course payment.",
  PAID: "Students buy this session on its own at the price below, even if they already paid class fees.",
  FREE: "No payment needed. Students still have to be enrolled, and the session dates still apply.",
};

/** Formats a decimal-string/number amount with two decimals, or `null` when not a finite number. */
export function formatAmount(value: MoneyValue | null | undefined): string | null {
  if (value === null || value === undefined || value === "") return null;
  const n = typeof value === "number" ? value : Number(value);
  if (!Number.isFinite(n)) return null;
  return n.toFixed(2);
}

/** Normalises a user-entered amount to a 2-decimal string for the request body. */
export function toMoneyString(value: string): string {
  return Number(value).toFixed(2);
}

// ---------------------------------------------------------------------------
// Query keys
// ---------------------------------------------------------------------------

export interface KlassCourseListParams {
  archived: boolean;
  search: string;
  page: number;
  size: number;
  tagId?: string | null;
}

export const klassAdminKeys = {
  /**
   * Nested under `courseKeys.list()`'s prefix (`["courses","list",{...}]`), so
   * every existing course mutation that invalidates `courseKeys.list()` also
   * refreshes this list (React Query matches object keys partially).
   */
  courseList: (params: KlassCourseListParams) =>
    [...courseKeys.all, "list", { variant: "klass-admin", ...params }] as const,
  courseListPrefix: [...courseKeys.all, "list"] as const,
  tags: ["course-tags"] as const,
  resourceTypes: ["courses", "lesson-resource-types"] as const,
};

// ---------------------------------------------------------------------------
// Courses
// ---------------------------------------------------------------------------

/** `GET /api/v1/courses?archived=&search=&page=&size=` — server search, sorted by displayOrder, createdAt. */
export function useKlassCourses(params: KlassCourseListParams) {
  const { authorizedFetch } = useAuth();
  const qs = new URLSearchParams();
  qs.set("archived", String(params.archived));
  if (params.search.trim()) qs.set("search", params.search.trim());
  qs.set("page", String(params.page));
  qs.set("size", String(params.size));
  if (params.tagId) qs.set("tagId", params.tagId);
  return useQuery({
    queryKey: klassAdminKeys.courseList(params),
    queryFn: () =>
      authorizedFetch<PageResponse<KlassCourse>>("tenant", `/v1/courses?${qs.toString()}`),
    placeholderData: (previous) => previous,
  });
}

/** `GET /api/v1/course-tags` → `[{id,name}]`. */
export function useCourseTags(options?: { enabled?: boolean }) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: klassAdminKeys.tags,
    queryFn: () => authorizedFetch<CourseTagRef[]>("tenant", "/v1/course-tags"),
    enabled: options?.enabled ?? true,
  });
}

/**
 * `POST /api/v1/courses` with the §4.1 fields and an `Idempotency-Key`
 * header, so a repeated submit from the same modal returns the same course.
 */
export function useCreateKlassCourse() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ body, idempotencyKey }: { body: KlassCourseCreateRequest; idempotencyKey: string }) =>
      authorizedFetch<KlassCourse>("tenant", "/v1/courses", {
        method: "POST",
        headers: { "Idempotency-Key": idempotencyKey },
        body: JSON.stringify(body),
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: klassAdminKeys.courseListPrefix });
      queryClient.invalidateQueries({ queryKey: klassAdminKeys.tags });
    },
  });
}

/** `POST /api/v1/courses/{id}/cover` (multipart `file`). The server sniffs the type and enforces 5 MB. */
export function useUploadCourseCover() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ courseId, file }: { courseId: string; file: File }) => {
      const form = new FormData();
      form.append("file", file);
      return authorizedFetch<KlassCourse>("tenant", `/v1/courses/${courseId}/cover`, {
        method: "POST",
        body: form,
      });
    },
    onSuccess: (_data, { courseId }) => {
      queryClient.invalidateQueries({ queryKey: klassAdminKeys.courseListPrefix });
      queryClient.invalidateQueries({ queryKey: courseKeys.detail(courseId) });
    },
  });
}

/**
 * `GET /api/v1/courses/{id}` typed with the §4.1 fields. Same endpoint and
 * cache entry as `useCourse` (`courseKeys.detail`), so a page that already
 * loaded the course through the workspace shell reads it from cache.
 */
export function useKlassCourse(courseId: string) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: courseKeys.detail(courseId),
    queryFn: () => authorizedFetch<KlassCourse>("tenant", `/v1/courses/${courseId}`),
    enabled: courseId.length > 0,
  });
}

/** `PATCH /api/v1/courses/{id}` with the course edit form body. */
export function useUpdateKlassCourse(courseId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: KlassCourseUpdateRequest) =>
      authorizedFetch<KlassCourse>("tenant", `/v1/courses/${courseId}`, {
        method: "PATCH",
        body: JSON.stringify(body),
      }),
    onSuccess: (updated) => {
      queryClient.setQueryData(courseKeys.detail(courseId), updated);
      queryClient.invalidateQueries({ queryKey: klassAdminKeys.courseListPrefix });
      queryClient.invalidateQueries({ queryKey: klassAdminKeys.tags });
    },
  });
}

/** `DELETE /api/v1/courses/{id}/cover` → the course falls back to the default cover. */
export function useDeleteCourseCover() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ courseId }: { courseId: string }) =>
      authorizedFetch<KlassCourse>("tenant", `/v1/courses/${courseId}/cover`, { method: "DELETE" }),
    onSuccess: (_data, { courseId }) => {
      queryClient.invalidateQueries({ queryKey: klassAdminKeys.courseListPrefix });
      queryClient.invalidateQueries({ queryKey: courseKeys.detail(courseId) });
    },
  });
}

type ListSnapshot = Array<[QueryKey, unknown]>;

function isCoursePage(value: unknown): value is PageResponse<{ id: string }> {
  return (
    typeof value === "object" &&
    value !== null &&
    Array.isArray((value as { content?: unknown }).content)
  );
}

/**
 * `PATCH /api/v1/courses/{id}/registration {enabled}`. Optimistic: flips the
 * switch in every cached list page immediately and restores the snapshot if
 * the server refuses. Blocks new enrollments only (D15).
 */
export function useSetCourseRegistration() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ courseId, enabled }: { courseId: string; enabled: boolean }) =>
      authorizedFetch<KlassCourse>("tenant", `/v1/courses/${courseId}/registration`, {
        method: "PATCH",
        body: JSON.stringify({ enabled }),
      }),
    onMutate: async ({ courseId, enabled }) => {
      await queryClient.cancelQueries({ queryKey: klassAdminKeys.courseListPrefix });
      const snapshot: ListSnapshot = queryClient.getQueriesData({
        queryKey: klassAdminKeys.courseListPrefix,
      });
      for (const [key, data] of snapshot) {
        if (!isCoursePage(data)) continue;
        queryClient.setQueryData(key, {
          ...data,
          content: data.content.map((course) =>
            course.id === courseId ? { ...course, registrationEnabled: enabled } : course
          ),
        });
      }
      return { snapshot };
    },
    onError: (_error, _vars, context) => {
      for (const [key, data] of context?.snapshot ?? []) {
        queryClient.setQueryData(key, data);
      }
    },
    onSettled: (_data, _error, { courseId }) => {
      queryClient.invalidateQueries({ queryKey: klassAdminKeys.courseListPrefix });
      queryClient.invalidateQueries({ queryKey: courseKeys.detail(courseId) });
    },
  });
}

/**
 * `POST /api/v1/courses/{id}/publish` (status -> PUBLIC) or `/unpublish`
 * (status -> DRAFT), with the course id supplied at call time so one hook
 * instance can act on a course that was only just created. The server
 * authorizes the call and decides the resulting status; the UI only reflects
 * the response. Students see a course in Available Courses only once it is
 * PUBLIC, not archived and registration-enabled (server-side catalog filter).
 */
export function useSetKlassCoursePublished() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ courseId, publish }: { courseId: string; publish: boolean }) =>
      authorizedFetch<CourseResponse>(
        "tenant",
        `/v1/courses/${courseId}/${publish ? "publish" : "unpublish"}`,
        { method: "POST" }
      ),
    onSettled: (_data, _error, { courseId }) => {
      queryClient.invalidateQueries({ queryKey: klassAdminKeys.courseListPrefix });
      queryClient.invalidateQueries({ queryKey: courseKeys.detail(courseId) });
    },
  });
}

/** `PUT /api/v1/courses/order {courseIds}` → 204. */
export function useReorderCourses() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (courseIds: string[]) =>
      authorizedFetch<null>("tenant", "/v1/courses/order", {
        method: "PUT",
        body: JSON.stringify({ courseIds }),
      }),
    onSettled: () => queryClient.invalidateQueries({ queryKey: klassAdminKeys.courseListPrefix }),
  });
}

// ---------------------------------------------------------------------------
// Modules
// ---------------------------------------------------------------------------

/** Same endpoint and cache key as `useCourseModules`, typed with the new `price` field. */
export function useKlassModules(courseId: string) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: courseKeys.modules(courseId),
    queryFn: () => authorizedFetch<KlassModule[]>("tenant", `/v1/courses/${courseId}/modules`),
    enabled: courseId.length > 0,
  });
}

export function useCreateKlassModule(courseId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: KlassModuleRequest) =>
      authorizedFetch<KlassModule>("tenant", `/v1/courses/${courseId}/modules`, {
        method: "POST",
        body: JSON.stringify(body),
      }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: courseKeys.modules(courseId) }),
  });
}

export function useUpdateKlassModule(courseId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ moduleId, body }: { moduleId: string; body: KlassModuleRequest }) =>
      authorizedFetch<KlassModule>("tenant", `/v1/courses/${courseId}/modules/${moduleId}`, {
        method: "PATCH",
        body: JSON.stringify(body),
      }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: courseKeys.modules(courseId) }),
  });
}

/** `PUT /api/v1/courses/{courseId}/modules/order {moduleIds}`. */
export function useReorderModules(courseId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (moduleIds: string[]) =>
      authorizedFetch<null>("tenant", `/v1/courses/${courseId}/modules/order`, {
        method: "PUT",
        body: JSON.stringify({ moduleIds }),
      }),
    onSettled: () => queryClient.invalidateQueries({ queryKey: courseKeys.modules(courseId) }),
  });
}

// ---------------------------------------------------------------------------
// Lessons ("Sessions")
// ---------------------------------------------------------------------------

export function useKlassLessons(courseId: string, moduleId: string) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: courseKeys.lessons(courseId, moduleId),
    queryFn: () =>
      authorizedFetch<KlassLesson[]>(
        "tenant",
        `/v1/courses/${courseId}/modules/${moduleId}/lessons`
      ),
    enabled: courseId.length > 0 && moduleId.length > 0,
  });
}

export function useCreateKlassLesson(courseId: string, moduleId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: KlassLessonRequest) =>
      authorizedFetch<KlassLesson>("tenant", `/v1/courses/${courseId}/modules/${moduleId}/lessons`, {
        method: "POST",
        body: JSON.stringify(body),
      }),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: courseKeys.lessons(courseId, moduleId) }),
  });
}

export function useUpdateKlassLesson(courseId: string, moduleId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ lessonId, body }: { lessonId: string; body: KlassLessonRequest }) =>
      authorizedFetch<KlassLesson>(
        "tenant",
        `/v1/courses/${courseId}/modules/${moduleId}/lessons/${lessonId}`,
        { method: "PATCH", body: JSON.stringify(body) }
      ),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: courseKeys.lessons(courseId, moduleId) }),
  });
}

/** `PUT .../modules/{moduleId}/lessons/order {lessonIds}`. */
export function useReorderLessons(courseId: string, moduleId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (lessonIds: string[]) =>
      authorizedFetch<null>("tenant", `/v1/courses/${courseId}/modules/${moduleId}/lessons/order`, {
        method: "PUT",
        body: JSON.stringify({ lessonIds }),
      }),
    onSettled: () =>
      queryClient.invalidateQueries({ queryKey: courseKeys.lessons(courseId, moduleId) }),
  });
}

/** `GET /api/v1/courses/lesson-resource-types` (static catalog). */
export function useLessonResourceTypes() {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: klassAdminKeys.resourceTypes,
    queryFn: () =>
      authorizedFetch<LessonResourceTypeOption[]>("tenant", "/v1/courses/lesson-resource-types"),
    staleTime: 60 * 60 * 1000,
  });
}

// ---------------------------------------------------------------------------
// Tenant timezone
// ---------------------------------------------------------------------------

function isUsableTimeZone(value: unknown): value is string {
  if (typeof value !== "string" || value.trim() === "") return false;
  try {
    new Intl.DateTimeFormat("en-US", { timeZone: value });
    return true;
  } catch {
    return false;
  }
}

function browserTimeZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC";
  } catch {
    return "UTC";
  }
}

export interface TenantTimeZone {
  timeZone: string;
  /** `tenant` when read from the `GENERAL.default_timezone` config; `browser` when that read failed or is unset. */
  source: "tenant" | "browser";
  isLoading: boolean;
}

/**
 * Tenant timezone for session date/time entry and display, from the
 * `GENERAL` config domain's `default_timezone`. That read needs
 * `BRANDING_SETTINGS`/`VIEW`; a role without it (403) falls back to the
 * browser's zone, and the form says so, so nobody is silently misled.
 */
export function useTenantTimeZone(): TenantTimeZone {
  const query = useTenantConfigDomain("GENERAL");
  if (query.status === "pending") {
    return { timeZone: browserTimeZone(), source: "browser", isLoading: true };
  }
  const entry = query.data?.find((property) => property.key === "default_timezone");
  const value = entry?.value ?? entry?.defaultValue;
  if (isUsableTimeZone(value)) {
    return { timeZone: value, source: "tenant", isLoading: false };
  }
  return { timeZone: browserTimeZone(), source: "browser", isLoading: false };
}
