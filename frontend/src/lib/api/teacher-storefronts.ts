import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";
import type { PageResponse } from "./courses";

/**
 * STORE-01 / ADR-037 Tenant Admin storefront management
 * (`/api/v1/teacher-storefronts/**`, `TEACHER_STOREFRONT_MANAGE`) and the
 * Teacher's read-only `/me`. Tenant comes from the token; the backend
 * re-checks the capability, the teacher's tenant and every course assignment.
 */

export interface StorefrontProduct {
  courseId: string;
  /** `null` when the course no longer resolves. */
  courseTitle: string | null;
  courseSlug: string | null;
  published: boolean;
  sortOrder: number;
}

export interface AssignableCourse {
  courseId: string;
  title: string;
  slug: string;
}

/**
 * `GET /{teacherId}` answers 404 both when the teacher has no storefront yet
 * and when the teacher isn't in the caller's tenant; the edit page then starts
 * from an empty, switched-off storefront (the first PUT creates it, or 404s).
 * `id`/`slug` are nullable only for that client-side placeholder.
 */
export interface StorefrontAdmin {
  id: string | null;
  teacherId: string;
  slug: string | null;
  enabled: boolean;
  previewUrl: string | null;
  publishedUrl: string | null;
  previousSlugs: string[];
  products: StorefrontProduct[];
  /** Absent on `GET /me`. */
  assignableCourses?: AssignableCourse[];
}

export interface TeacherStorefrontRow {
  teacherId: string;
  teacherName: string;
  storefront: StorefrontAdmin | null;
}

export interface StorefrontSettingsInput {
  slug: string;
  enabled: boolean;
}

export interface StorefrontProductItemInput {
  courseId: string;
  published: boolean;
  sortOrder: number;
}

export const teacherStorefrontKeys = {
  all: ["teacher-storefronts"] as const,
  list: (page: number, size: number) => [...teacherStorefrontKeys.all, "list", page, size] as const,
  detail: (teacherId: string) => [...teacherStorefrontKeys.all, "detail", teacherId] as const,
  me: () => [...teacherStorefrontKeys.all, "me"] as const,
};

export const TEACHER_STOREFRONTS_PAGE_SIZE = 20;

export function useTeacherStorefronts(page: number, size = TEACHER_STOREFRONTS_PAGE_SIZE) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: teacherStorefrontKeys.list(page, size),
    queryFn: () =>
      authorizedFetch<PageResponse<TeacherStorefrontRow>>(
        "tenant",
        `/v1/teacher-storefronts?page=${page}&size=${size}`
      ),
  });
}

export function useTeacherStorefront(teacherId: string) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: teacherStorefrontKeys.detail(teacherId),
    queryFn: () => authorizedFetch<StorefrontAdmin>("tenant", `/v1/teacher-storefronts/${encodeURIComponent(teacherId)}`),
    enabled: teacherId.length > 0,
    retry: false,
  });
}

/** `GET /api/v1/teacher-storefronts/me` - 404 when the teacher has no storefront. */
export function useMyStorefront() {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: teacherStorefrontKeys.me(),
    queryFn: () => authorizedFetch<StorefrontAdmin>("tenant", "/v1/teacher-storefronts/me"),
    retry: false,
  });
}

function useStorefrontMutation<TInput>(teacherId: string, path: (id: string) => string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation<StorefrontAdmin, unknown, TInput>({
    mutationFn: (body) =>
      authorizedFetch<StorefrontAdmin>("tenant", path(encodeURIComponent(teacherId)), {
        method: "PUT",
        body: JSON.stringify(body),
      }),
    onSuccess: (saved) => {
      queryClient.setQueryData(teacherStorefrontKeys.detail(teacherId), (previous: StorefrontAdmin | undefined) =>
        // PUT responses may omit assignableCourses; keep the list we already have.
        ({ ...saved, assignableCourses: saved.assignableCourses ?? previous?.assignableCourses })
      );
      void queryClient.invalidateQueries({ queryKey: [...teacherStorefrontKeys.all, "list"] });
    },
  });
}

/** `PUT /api/v1/teacher-storefronts/{teacherId}` `{slug, enabled}` (creates on first call). */
export function useSaveStorefrontSettings(teacherId: string) {
  return useStorefrontMutation<StorefrontSettingsInput>(teacherId, (id) => `/v1/teacher-storefronts/${id}`);
}

/** `PUT /api/v1/teacher-storefronts/{teacherId}/products` - full replace. */
export function useSaveStorefrontProducts(teacherId: string) {
  return useStorefrontMutation<{ items: StorefrontProductItemInput[] }>(
    teacherId,
    (id) => `/v1/teacher-storefronts/${id}/products`
  );
}
