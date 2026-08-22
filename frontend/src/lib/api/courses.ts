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

/** Mirrors `CourseResponse` (backend `com.lms.coursemanagement.course.web.dto`) field-for-field. */
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
  createdAt: string;
  updatedAt: string;
}

/**
 * Mirrors `CourseCreateRequest`. Deliberately has no `teacherId` field: per
 * the module brief, only the Teacher-role self-create flow is built at MVP
 * (Tenant Admin has no create-course screen), and `teacherId` is ignored
 * server-side for a Teacher-role caller regardless of what's sent — so this
 * client never sends it.
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
  list: () => [...courseKeys.all, "list"] as const,
  detail: (courseId: string) => [...courseKeys.all, "detail", courseId] as const,
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
  page?: number;
  size?: number;
  sort?: string;
}

function buildCourseListQuery(params?: CourseListParams): string {
  const search = new URLSearchParams();
  if (params?.status) search.set("status", params.status);
  if (params?.category) search.set("category", params.category);
  if (params?.teacherId) search.set("teacherId", params.teacherId);
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
 */
export function useCourses(params?: CourseListParams) {
  const { authorizedFetch } = useAuth();
  const queryString = buildCourseListQuery(params);
  return useQuery({
    queryKey: courseKeys.list(),
    queryFn: () =>
      authorizedFetch<PageResponse<CourseResponse>>("tenant", `/api/v1/courses${queryString}`),
  });
}

export function useCourse(courseId: string) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: courseKeys.detail(courseId),
    queryFn: () => authorizedFetch<CourseResponse>("tenant", `/api/v1/courses/${courseId}`),
    enabled: courseId.length > 0,
  });
}

export function useCreateCourse() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: CourseCreateRequest) =>
      authorizedFetch<CourseResponse>("tenant", "/api/v1/courses", {
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
      authorizedFetch<CourseResponse>("tenant", `/api/v1/courses/${courseId}`, {
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
      authorizedFetch<CourseResponse>("tenant", `/api/v1/courses/${courseId}/teacher`, {
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
      authorizedFetch<CourseResponse>("tenant", `/api/v1/courses/${courseId}/price`, {
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
      authorizedFetch<CourseResponse>("tenant", `/api/v1/courses/${courseId}/publish`, {
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
      authorizedFetch<CourseResponse>("tenant", `/api/v1/courses/${courseId}/unpublish`, {
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
      authorizedFetch<null>("tenant", `/api/v1/courses/${courseId}`, {
        method: "DELETE",
      }),
    onSuccess: () => {
      queryClient.removeQueries({ queryKey: courseKeys.detail(courseId) });
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
      authorizedFetch<CourseModuleResponse[]>("tenant", `/api/v1/courses/${courseId}/modules`),
    enabled: courseId.length > 0,
  });
}

export function useCreateCourseModule(courseId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: CourseModuleRequest) =>
      authorizedFetch<CourseModuleResponse>("tenant", `/api/v1/courses/${courseId}/modules`, {
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
        `/api/v1/courses/${courseId}/modules/${moduleId}`,
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
      authorizedFetch<null>("tenant", `/api/v1/courses/${courseId}/modules/${moduleId}`, {
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
        `/api/v1/courses/${courseId}/modules/${moduleId}/lessons`
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
        `/api/v1/courses/${courseId}/modules/${moduleId}/lessons`,
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
        `/api/v1/courses/${courseId}/modules/${moduleId}/lessons/${lessonId}`,
        { method: "PATCH", body: JSON.stringify(body) }
      ),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: courseKeys.lessons(courseId, moduleId) });
    },
  });
}

export function useDeleteCourseLesson(courseId: string, moduleId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (lessonId: string) =>
      authorizedFetch<null>(
        "tenant",
        `/api/v1/courses/${courseId}/modules/${moduleId}/lessons/${lessonId}`,
        { method: "DELETE" }
      ),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: courseKeys.lessons(courseId, moduleId) });
    },
  });
}
