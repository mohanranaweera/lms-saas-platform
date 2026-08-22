import { useQuery } from "@tanstack/react-query";
import { apiFetch } from "./client";
import type { PageResponse } from "./courses";

/**
 * Typed client for course-management's anonymous public storefront endpoints
 * (`/api/v1/public/courses/**`, `CoursePublicController`). Never authenticated
 * — uses plain `apiFetch` directly (no bearer token), matching
 * `lib/api/tenant-registrations.ts`'s existing pattern for the platform's
 * other anonymous endpoint. Tenant identity is resolved server-side from the
 * request's subdomain; there is no `tenantId` param to send, ever.
 */

/**
 * Mirrors `PublicCourseResponse` field-for-field. Deliberately has no
 * `teacherId`, no audit fields, and no `status` — every course returned here
 * is implicitly `PUBLIC` by construction (existence-leakage prevention).
 */
export interface PublicCourseResponse {
  id: string;
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
}

export const publicCourseKeys = {
  all: ["public-courses"] as const,
  list: (params?: PublicCourseListParams) =>
    [...publicCourseKeys.all, "list", params ?? {}] as const,
  detail: (slug: string) => [...publicCourseKeys.all, "detail", slug] as const,
};

/** Default page size for the public catalog's real Previous/Next pagination controls. */
export const PUBLIC_COURSES_PAGE_SIZE = 12;

export interface PublicCourseListParams {
  page?: number;
  size?: number;
  sort?: string;
}

function buildPublicCourseListQuery(params?: PublicCourseListParams): string {
  const search = new URLSearchParams();
  search.set("page", String(params?.page ?? 0));
  search.set("size", String(params?.size ?? PUBLIC_COURSES_PAGE_SIZE));
  if (params?.sort) search.set("sort", params.sort);
  return `?${search.toString()}`;
}

/**
 * `GET /api/v1/public/courses` — paginated (`PageResponse<PublicCourseResponse>`).
 * This is the genuinely public, growth-unbounded catalog, so it gets real
 * Previous/Next pagination (see `app/(public)/courses/page.tsx`) rather than
 * a single large fixed page, unlike the two authenticated/internal course
 * list views in `lib/api/courses.ts`.
 */
export function listPublicCourses(
  params?: PublicCourseListParams
): Promise<PageResponse<PublicCourseResponse>> {
  return apiFetch<PageResponse<PublicCourseResponse>>(
    `/api/v1/public/courses${buildPublicCourseListQuery(params)}`
  );
}

export function getPublicCourseBySlug(slug: string): Promise<PublicCourseResponse> {
  return apiFetch<PublicCourseResponse>(`/api/v1/public/courses/${encodeURIComponent(slug)}`);
}

export function usePublicCourses(params?: PublicCourseListParams) {
  return useQuery({
    queryKey: publicCourseKeys.list(params),
    queryFn: () => listPublicCourses(params),
  });
}

export function usePublicCourse(slug: string) {
  return useQuery({
    queryKey: publicCourseKeys.detail(slug),
    queryFn: () => getPublicCourseBySlug(slug),
    enabled: slug.length > 0,
    // 404 for DRAFT/PRIVATE-in-tenant, nonexistent, and cross-tenant-same-slug
    // is deliberate, generic backend behavior (anti-enumeration) — retrying a
    // 404 would just hammer the endpoint for no benefit, so don't.
    retry: false,
  });
}
