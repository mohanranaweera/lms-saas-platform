import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";

/**
 * Typed client + React Query hooks for course-management's authenticated
 * endpoints (`/api/v1/courses/**`, Teacher/Staff/Tenant-Admin only — see
 * `CourseController`/`CourseModuleController`/`CourseLessonController`).
 *
 * Every call goes through `useAuth().authorizedFetch("tenant", ...)` (bearer
 * token attach + one-retry-on-401 refresh), never a bare `apiFetch` — this is
 * the established pattern for every protected endpoint in this app. Public
 * storefront reads live in `./public-courses.ts` instead, using plain
 * `apiFetch` (no auth), per that endpoint's anonymous contract.
 */

export type CourseStatus = "DRAFT" | "PRIVATE" | "PUBLIC";

/** Mirrors `CoursePricingModel` (backend `com.lms.coursemanagement.course.domain`). */
export type CoursePricingModel = "FREE" | "ONE_TIME" | "MONTHLY" | "SESSION" | "CUSTOM";

/**
 * Mirrors the backend's generic `PageResponse<T>` envelope (wrapped inside
 * `ApiResponse<T>` like every other response). Every paginated list endpoint
 * in this module (`GET /api/v1/courses`, `GET /api/v1/public/courses`)
 * returns this shape now instead of a bare array.
 */
export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/**
 * Mirrors `CourseResponse` (backend `com.lms.coursemanagement.course.web.dto`)
 * field-for-field, including Wave 2's `pricingModel`/`archivedAt` additions
 * and the pricing-model-aware `resolvedAmount`/`currency`/`requiresManualQuote`
 * gap-fix fields. `archivedAt` is `null` for an active (non-archived) course —
 * see `CourseListFilter`'s javadoc for the "excluded from default listing"
 * behavior this drives. `price` must never be rendered directly for checkout
 * purposes — see `components/courses/course-price-display.tsx`'s
 * `CoursePricingInfo` doc comment for `resolvedAmount`'s exact semantics per
 * pricing model.
 */
export interface CourseResponse {
  id: string;
  teacherId: string;
  name: string;
  slug: string;
  category: string;
  subject: string | null;
  stream: string | null;
  grade: string | null;
  academicYear: string | null;
  description: string | null;
  price: number;
  accessDurationDays: number | null;
  enrollmentRule: string | null;
  status: CourseStatus;
  pricingModel: CoursePricingModel;
  archivedAt: string | null;
  createdAt: string;
  updatedAt: string;
  resolvedAmount: number | null;
  currency: string;
  requiresManualQuote: boolean;
}

/**
 * Mirrors `CourseCreateRequest`. `teacherId` is staff-only effective (Wave 2
 * PAR-05-02) — silently ignored server-side for a Teacher-role caller
 * regardless of what's sent, and mandatory server-side for a staff caller
 * (`CourseService#createCourse`); the Teacher self-create flow never sends
 * it. There is no `pricingModel` field here — `CourseCreateRequest` has no
 * such field on the backend; every course is created `ONE_TIME` (the
 * column default) and, if a different model is chosen in the create form,
 * the client follows up with a separate `PATCH .../pricing-model` call
 * after creation succeeds (see `useCreateCourse`'s callers).
 */
export interface CourseCreateRequest {
  name: string;
  slug: string;
  category: string;
  subject?: string;
  stream?: string;
  grade?: string;
  academicYear?: string;
  description?: string;
  price: number;
  accessDurationDays?: number;
  enrollmentRule?: string;
  status: CourseStatus;
  /** Staff-only effective; omit entirely for the Teacher self-create flow. */
  teacherId?: string;
}

export interface CoursePricingModelChangeRequest {
  pricingModel: CoursePricingModel;
}

/**
 * Mirrors `CourseUpdateRequest`. Deliberately excludes `price`, `status`, and
 * `teacherId` — the backend silently drops them via `@JsonIgnoreProperties
 * (ignoreUnknown = true)` even if sent, so this type has no field for them at
 * all (see `CoursePriceChangeRequest`/publish-unpublish/`CourseTeacherReassignRequest`
 * below for their dedicated actions).
 */
export interface CourseUpdateRequest {
  name: string;
  slug: string;
  category: string;
  subject?: string;
  stream?: string;
  grade?: string;
  academicYear?: string;
  description?: string;
  enrollmentRule?: string;
  accessDurationDays?: number;
}

export interface CoursePriceChangeRequest {
  price: number;
}

export interface CourseTeacherReassignRequest {
  teacherId: string;
}

/** Mirrors `CourseModuleResponse`. */
export interface CourseModuleResponse {
  id: string;
  courseId: string;
  title: string;
  sequence: number;
  createdAt: string;
  updatedAt: string;
}

export interface CourseModuleRequest {
  title: string;
  sequence: number;
}

/** Mirrors `CourseLessonResponse`. */
export interface CourseLessonResponse {
  id: string;
  moduleId: string;
  title: string;
  sequence: number;
  createdAt: string;
  updatedAt: string;
}

export interface CourseLessonRequest {
  title: string;
  sequence: number;
}

export const courseKeys = {
  all: ["courses"] as const,
  /**
   * `params` is included in the key (when supplied) so two callers using
   * different filters (e.g. toggling `includeArchived`) don't collide in the
   * cache. `queryClient.invalidateQueries({ queryKey: courseKeys.list() })`
   * (no args) still invalidates every params-specific entry too — React
   * Query's default `exact: false` treats the shorter key as a prefix match.
   */
  list: (params?: CourseListParams) => [...courseKeys.all, "list", params ?? {}] as const,
  detail: (courseId: string) => [...courseKeys.all, "detail", courseId] as const,
  billingConfiguration: (courseId: string) => [...courseKeys.detail(courseId), "billing-configuration"] as const,
  billingPeriods: (courseId: string, page: number) =>
    [...courseKeys.detail(courseId), "billing-periods", page] as const,
  modules: (courseId: string) => [...courseKeys.detail(courseId), "modules"] as const,
  lessons: (courseId: string, moduleId: string) =>
    [...courseKeys.modules(courseId), moduleId, "lessons"] as const,
};

// ---------------------------------------------------------------------------
// Courses
// ---------------------------------------------------------------------------

export interface CourseListParams {
  status?: CourseStatus;
  /** Exact match, server-side. */
  category?: string;
  /** Staff-only effective — silently ignored server-side for a Teacher caller. */
  teacherId?: string;
  /**
   * Wave 2 (`includeArchived` query param). Defaults to `false` server-side
   * (`CourseListFilter.EMPTY`) — an archived course is excluded from every
   * listing read unless this is explicitly set `true`.
   */
  includeArchived?: boolean;
  page?: number;
  size?: number;
  sort?: string;
}

function buildCourseListQuery(params?: CourseListParams): string {
  const search = new URLSearchParams();
  if (params?.status) search.set("status", params.status);
  if (params?.category) search.set("category", params.category);
  if (params?.teacherId) search.set("teacherId", params.teacherId);
  if (params?.includeArchived) search.set("includeArchived", "true");
  search.set("page", String(params?.page ?? 0));
  // Defaults to 100 (the server's own clamp/max) rather than the server's
  // own default of 20: both current callers (Teacher "My Courses", Tenant
  // Admin "Courses") still do client-side search/status/category filtering
  // over the fetched page — see those pages' doc comments — so this fetches
  // one large-enough page instead of an unbounded full list, without
  // redesigning that filter UX into real pagination controls. A caller that
  // needs a smaller page (or real pagination) can still override `size`.
  search.set("size", String(params?.size ?? 100));
  if (params?.sort) search.set("sort", params.sort);
  const qs = search.toString();
  return qs ? `?${qs}` : "";
}

/**
 * `GET /api/v1/courses` — paginated (`PageResponse<CourseResponse>`).
 * Staff sees every tenant course; Teacher sees only their own — both
 * enforced server-side. `teacherId` is staff-only effective (ignored
 * server-side for a Teacher caller). Defaults to a single `size=100` page
 * (see `buildCourseListQuery`) since both current callers still filter/search
 * client-side over the fetched page rather than driving real pagination UI.
 *
 * `options.enabled` (default `true`) — first needed by the Wave 3 "Enroll
 * student" sheet (`app/(tenant-admin)/tenant-admin/students/[studentId]/enroll-student-sheet.tsx`),
 * which is always mounted (controlled `open` prop, matching this codebase's
 * existing always-mounted-sheet convention) but must not fetch the course
 * list until actually opened.
 */
export function useCourses(params?: CourseListParams, options?: { enabled?: boolean }) {
  const { authorizedFetch } = useAuth();
  const queryString = buildCourseListQuery(params);
  return useQuery({
    queryKey: courseKeys.list(params),
    queryFn: () =>
      authorizedFetch<PageResponse<CourseResponse>>("tenant", `/v1/courses${queryString}`),
    enabled: options?.enabled ?? true,
  });
}

export function useCourse(courseId: string) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: courseKeys.detail(courseId),
    queryFn: () => authorizedFetch<CourseResponse>("tenant", `/v1/courses/${courseId}`),
    enabled: courseId.length > 0,
  });
}

export function useCreateCourse() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: CourseCreateRequest) =>
      authorizedFetch<CourseResponse>("tenant", "/v1/courses", {
        method: "POST",
        body: JSON.stringify(body),
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: courseKeys.list() });
    },
  });
}

export function useUpdateCourse(courseId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: CourseUpdateRequest) =>
      authorizedFetch<CourseResponse>("tenant", `/v1/courses/${courseId}`, {
        method: "PATCH",
        body: JSON.stringify(body),
      }),
    onSuccess: (updated) => {
      queryClient.setQueryData(courseKeys.detail(courseId), updated);
      queryClient.invalidateQueries({ queryKey: courseKeys.list() });
    },
  });
}

/**
 * Tenant Admin only (403 for every other role, including the course's own
 * owning Teacher) — server-enforced; this hook is only ever wired up from
 * the Tenant Admin course detail page.
 */
export function useReassignCourseTeacher(courseId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: CourseTeacherReassignRequest) =>
      authorizedFetch<CourseResponse>("tenant", `/v1/courses/${courseId}/teacher`, {
        method: "POST",
        body: JSON.stringify(body),
      }),
    onSuccess: (updated) => {
      queryClient.setQueryData(courseKeys.detail(courseId), updated);
      queryClient.invalidateQueries({ queryKey: courseKeys.list() });
    },
  });
}

export function useChangeCoursePrice(courseId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: CoursePriceChangeRequest) =>
      authorizedFetch<CourseResponse>("tenant", `/v1/courses/${courseId}/price`, {
        method: "PATCH",
        body: JSON.stringify(body),
      }),
    onSuccess: (updated) => {
      queryClient.setQueryData(courseKeys.detail(courseId), updated);
      queryClient.invalidateQueries({ queryKey: courseKeys.list() });
    },
  });
}

/** Sets status -> `PUBLIC`. */
export function usePublishCourse(courseId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () =>
      authorizedFetch<CourseResponse>("tenant", `/v1/courses/${courseId}/publish`, {
        method: "POST",
      }),
    onSuccess: (updated) => {
      queryClient.setQueryData(courseKeys.detail(courseId), updated);
      queryClient.invalidateQueries({ queryKey: courseKeys.list() });
    },
  });
}

/**
 * Sets status -> `DRAFT` (NOT `PRIVATE` — there is no server-side path back
 * to `PRIVATE` after creation). Callers must label this action honestly
 * (e.g. "Unpublish (revert to Draft)"), never "Make Private".
 */
export function useUnpublishCourse(courseId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () =>
      authorizedFetch<CourseResponse>("tenant", `/v1/courses/${courseId}/unpublish`, {
        method: "POST",
      }),
    onSuccess: (updated) => {
      queryClient.setQueryData(courseKeys.detail(courseId), updated);
      queryClient.invalidateQueries({ queryKey: courseKeys.list() });
    },
  });
}

/** Tenant Admin only (403 for everyone else, including the owning Teacher). */
export function useDeleteCourse(courseId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () =>
      authorizedFetch<null>("tenant", `/v1/courses/${courseId}`, {
        method: "DELETE",
      }),
    onSuccess: () => {
      queryClient.removeQueries({ queryKey: courseKeys.detail(courseId) });
      queryClient.invalidateQueries({ queryKey: courseKeys.list() });
    },
  });
}

/**
 * `PATCH /api/v1/courses/{id}/pricing-model` (Wave 2) — the sole write path
 * for `pricingModel`. Staff (`CREATE_EDIT`) or the owning Teacher.
 */
export function useChangeCoursePricingModel(courseId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: CoursePricingModelChangeRequest) =>
      authorizedFetch<CourseResponse>("tenant", `/v1/courses/${courseId}/pricing-model`, {
        method: "PATCH",
        body: JSON.stringify(body),
      }),
    onSuccess: (updated) => {
      queryClient.setQueryData(courseKeys.detail(courseId), updated);
      queryClient.invalidateQueries({ queryKey: courseKeys.list() });
    },
  });
}

/**
 * Create-flow-only variant of {@link useChangeCoursePricingModel}: the
 * target `courseId` isn't known until the just-created course's response
 * comes back, so it's supplied per-`mutate()` call instead of baked into the
 * hook via closure (there is no stable `courseId` to close over yet at the
 * point this hook is declared in `course-create-form.tsx`). Used to compose
 * "create, then set the chosen non-`ONE_TIME` pricing model" into two
 * sequential calls — see that component's doc comment for why
 * `CourseCreateRequest` itself has no `pricingModel` field to set this in
 * one call.
 */
export function useChangeCoursePricingModelForCourse() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ courseId, pricingModel }: { courseId: string; pricingModel: CoursePricingModel }) =>
      authorizedFetch<CourseResponse>("tenant", `/v1/courses/${courseId}/pricing-model`, {
        method: "PATCH",
        body: JSON.stringify({ pricingModel }),
      }),
    onSuccess: (updated) => {
      queryClient.setQueryData(courseKeys.detail(updated.id), updated);
      queryClient.invalidateQueries({ queryKey: courseKeys.list() });
    },
  });
}

/**
 * `POST /api/v1/courses/{id}/archive` (Wave 2) — sets `archivedAt`, a purely
 * listing-visibility flag (never deletes anything). Staff (`CREATE_EDIT`) or
 * the owning Teacher.
 */
export function useArchiveCourse(courseId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () =>
      authorizedFetch<CourseResponse>("tenant", `/v1/courses/${courseId}/archive`, {
        method: "POST",
      }),
    onSuccess: (updated) => {
      queryClient.setQueryData(courseKeys.detail(courseId), updated);
      queryClient.invalidateQueries({ queryKey: courseKeys.list() });
    },
  });
}

/** The inverse of {@link useArchiveCourse} — `POST /api/v1/courses/{id}/unarchive`. */
export function useUnarchiveCourse(courseId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () =>
      authorizedFetch<CourseResponse>("tenant", `/v1/courses/${courseId}/unarchive`, {
        method: "POST",
      }),
    onSuccess: (updated) => {
      queryClient.setQueryData(courseKeys.detail(courseId), updated);
      queryClient.invalidateQueries({ queryKey: courseKeys.list() });
    },
  });
}

/**
 * `POST /api/v1/courses/{id}/clone` (Wave 2) — creates a brand-new course (a
 * new id, `DRAFT` status, never archived) that copies classification/content
 * structure only, never enrollment/payment/billing-period history. Staff
 * (`CREATE_EDIT`) or the owning Teacher, on the SOURCE course.
 */
export function useCloneCourse(courseId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () =>
      authorizedFetch<CourseResponse>("tenant", `/v1/courses/${courseId}/clone`, {
        method: "POST",
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: courseKeys.list() });
    },
  });
}

// ---------------------------------------------------------------------------
// Modules
// ---------------------------------------------------------------------------

export function useCourseModules(courseId: string) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: courseKeys.modules(courseId),
    queryFn: () =>
      authorizedFetch<CourseModuleResponse[]>("tenant", `/v1/courses/${courseId}/modules`),
    enabled: courseId.length > 0,
  });
}

export function useCreateCourseModule(courseId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: CourseModuleRequest) =>
      authorizedFetch<CourseModuleResponse>("tenant", `/v1/courses/${courseId}/modules`, {
        method: "POST",
        body: JSON.stringify(body),
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: courseKeys.modules(courseId) });
    },
  });
}

export function useUpdateCourseModule(courseId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ moduleId, body }: { moduleId: string; body: CourseModuleRequest }) =>
      authorizedFetch<CourseModuleResponse>(
        "tenant",
        `/v1/courses/${courseId}/modules/${moduleId}`,
        { method: "PATCH", body: JSON.stringify(body) }
      ),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: courseKeys.modules(courseId) });
    },
  });
}

export function useDeleteCourseModule(courseId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (moduleId: string) =>
      authorizedFetch<null>("tenant", `/v1/courses/${courseId}/modules/${moduleId}`, {
        method: "DELETE",
      }),
    onSuccess: (_data, moduleId) => {
      queryClient.invalidateQueries({ queryKey: courseKeys.modules(courseId) });
      queryClient.removeQueries({ queryKey: courseKeys.lessons(courseId, moduleId) });
    },
  });
}

// ---------------------------------------------------------------------------
// Lessons
// ---------------------------------------------------------------------------

export function useCourseLessons(courseId: string, moduleId: string, options?: { enabled?: boolean }) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: courseKeys.lessons(courseId, moduleId),
    queryFn: () =>
      authorizedFetch<CourseLessonResponse[]>(
        "tenant",
        `/v1/courses/${courseId}/modules/${moduleId}/lessons`
      ),
    enabled: (options?.enabled ?? true) && courseId.length > 0 && moduleId.length > 0,
  });
}

export function useCreateCourseLesson(courseId: string, moduleId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: CourseLessonRequest) =>
      authorizedFetch<CourseLessonResponse>(
        "tenant",
        `/v1/courses/${courseId}/modules/${moduleId}/lessons`,
        { method: "POST", body: JSON.stringify(body) }
      ),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: courseKeys.lessons(courseId, moduleId) });
    },
  });
}

export function useUpdateCourseLesson(courseId: string, moduleId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ lessonId, body }: { lessonId: string; body: CourseLessonRequest }) =>
      authorizedFetch<CourseLessonResponse>(
        "tenant",
        `/v1/courses/${courseId}/modules/${moduleId}/lessons/${lessonId}`,
        { method: "PATCH", body: JSON.stringify(body) }
      ),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: courseKeys.lessons(courseId, moduleId) });
    },
  });
}

// ---------------------------------------------------------------------------
// Roster
// ---------------------------------------------------------------------------

/** Mirrors `CourseRosterEntryResponse` — one row of `GET /v1/courses/{courseId}/roster`'s response body. */
export interface CourseRosterEntryResponse {
  studentId: string;
  userId: string;
  name: string;
  email: string;
}

/**
 * `GET /v1/courses/{courseId}/roster` (Wave 3, PAR-03-06/PAR-04-03) — a real,
 * backend-filtered course roster. Teacher: own-course-only (server-verified
 * ownership, same pattern `AttendanceAccessGuard` already uses — a Teacher
 * requesting another teacher's course id gets a real 404/403, never a
 * client-filtered subset of a wider fetch). Staff: `STUDENTS`/`VIEW` or
 * `COURSES`/`VIEW`. Plain array, no pagination.
 */
export function useCourseRoster(courseId: string) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: [...courseKeys.detail(courseId), "roster"],
    queryFn: () =>
      authorizedFetch<CourseRosterEntryResponse[]>("tenant", `/v1/courses/${courseId}/roster`),
    enabled: courseId.length > 0,
  });
}

export function useDeleteCourseLesson(courseId: string, moduleId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (lessonId: string) =>
      authorizedFetch<null>(
        "tenant",
        `/v1/courses/${courseId}/modules/${moduleId}/lessons/${lessonId}`,
        { method: "DELETE" }
      ),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: courseKeys.lessons(courseId, moduleId) });
    },
  });
}
