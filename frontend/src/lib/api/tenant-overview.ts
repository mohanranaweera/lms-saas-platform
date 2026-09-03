"use client";

import { useQueries } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";
import type { CourseResponse, PageResponse } from "./courses";

/**
 * Tenant Admin Overview (MVP-015 TADASH-1) course-count reads. Deliberately
 * NOT built on `useCourses()`/`courseKeys.list()` (`lib/api/courses.ts`) —
 * every `useCourses(params)` call shares the identical `["courses","list"]`
 * cache key regardless of `params`, so firing a total-count and a
 * published-count read from the same page via that hook would silently
 * clobber each other's cache entry. This hook uses its own, separately-keyed
 * queries instead (see `docs/plans/MVP-015 Tenant Admin Dashboard.md`
 * Grounding note item 4 / §21 item 4).
 */

export interface TenantCourseCounts {
  total: number;
  published: number;
  draft: number;
}

export const tenantOverviewKeys = {
  all: ["tenant-overview"] as const,
  courseCounts: () => [...tenantOverviewKeys.all, "course-counts"] as const,
};

interface CombinedCourseCountsResult {
  status: "pending" | "error" | "success";
  data: TenantCourseCounts | undefined;
  error: unknown;
  /**
   * The resolved value isn't meaningful for this combined result (it isn't a
   * single `UseQueryResult`) — only the refetch side-effect (re-running both
   * underlying queries) matters, so callers must not rely on what this
   * promise resolves to.
   */
  refetch: () => Promise<unknown>;
}

/** Fires `GET /api/v1/courses?page=0&size=1` and `?status=PUBLIC&page=0&size=1` in parallel, combined into `{ total, published, draft }` once both resolve. `draft` = total − published. */
export function useTenantCourseCounts(): CombinedCourseCountsResult {
  const { authorizedFetch } = useAuth();

  return useQueries({
    queries: [
      {
        queryKey: [...tenantOverviewKeys.courseCounts(), "total"] as const,
        queryFn: () =>
          authorizedFetch<PageResponse<CourseResponse>>(
            "tenant",
            "/v1/courses?page=0&size=1"
          ),
      },
      {
        queryKey: [...tenantOverviewKeys.courseCounts(), "published"] as const,
        queryFn: () =>
          authorizedFetch<PageResponse<CourseResponse>>(
            "tenant",
            "/v1/courses?status=PUBLIC&page=0&size=1"
          ),
      },
    ],
    combine: (results): CombinedCourseCountsResult => {
      const [totalResult, publishedResult] = results;
      const hasError = totalResult.status === "error" || publishedResult.status === "error";
      const isPending = totalResult.status === "pending" || publishedResult.status === "pending";
      const status: CombinedCourseCountsResult["status"] = hasError
        ? "error"
        : isPending
          ? "pending"
          : "success";

      const data: TenantCourseCounts | undefined =
        status === "success" && totalResult.data && publishedResult.data
          ? {
              total: totalResult.data.totalElements,
              published: publishedResult.data.totalElements,
              draft: totalResult.data.totalElements - publishedResult.data.totalElements,
            }
          : undefined;

      return {
        status,
        data,
        error: totalResult.error ?? publishedResult.error ?? null,
        refetch: () => {
          void totalResult.refetch();
          return publishedResult.refetch();
        },
      };
    },
  });
}
