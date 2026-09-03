import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";

/**
 * Typed client + React Query hooks for content-management's material
 * endpoints (`/api/v1/courses/{courseId}/modules/{moduleId}/lessons/{lessonId}/materials/**`,
 * `com.lms.contentmanagement.material.web.MaterialController`). Mirrors the
 * shape of `./courses.ts` (query keys object, `useQuery`/`useMutation` via
 * `useAuth().authorizedFetch("tenant", ...)`, `onSuccess` cache
 * invalidation) — see that file's doc comment for the established pattern.
 *
 * `GET .../materials` is already filtered server-side to `VISIBLE`-only
 * materials for a Student caller — this client never re-filters or infers
 * visibility client-side (see module brief / `.claude/rules/security.md`).
 */

export type MaterialVisibility = "VISIBLE" | "HIDDEN";

/** Mirrors `MaterialResponse` (backend `com.lms.contentmanagement.material.web.dto`) field-for-field. */
export interface MaterialResponse {
  id: string;
  lessonId: string;
  title: string;
  originalFilename: string;
  mimeType: string;
  sizeBytes: number;
  sequence: number;
  visibility: MaterialVisibility;
  expiryAt: string | null;
  uploadedBy: string;
  createdAt: string;
  updatedAt: string;
}

/** Input to `useCreateMaterial` — sent as `multipart/form-data` (`file` + `title` parts), never JSON. */
export interface MaterialCreateInput {
  title: string;
  file: File;
}

/**
 * Mirrors `MaterialUpdateRequest` — a full-resource replace, not a partial
 * patch. `title`, `sequence`, and `visibility` are all required together on
 * every call: there is no separate reorder/rename/visibility-only endpoint,
 * so every caller (rename, visibility toggle, reorder) must resend the two
 * fields it isn't changing at their current value.
 */
export interface MaterialUpdateRequest {
  title: string;
  sequence: number;
  visibility: MaterialVisibility;
}

export interface MaterialDownloadUrlResponse {
  url: string;
  expiresAt: string;
}

export const materialKeys = {
  all: ["materials"] as const,
  list: (courseId: string, moduleId: string, lessonId: string) =>
    [...materialKeys.all, courseId, moduleId, lessonId, "list"] as const,
};

function materialsBasePath(courseId: string, moduleId: string, lessonId: string): string {
  return `/v1/courses/${courseId}/modules/${moduleId}/lessons/${lessonId}/materials`;
}

/**
 * `GET .../materials` — ordered by `sequence` server-side (this client also
 * sorts client-side via `sortBySequence` at render time, matching the
 * module/lesson list convention, since the backend contract doesn't
 * guarantee ordering is preserved end-to-end).
 */
export function useMaterials(
  courseId: string,
  moduleId: string,
  lessonId: string,
  options?: { enabled?: boolean }
) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: materialKeys.list(courseId, moduleId, lessonId),
    queryFn: () =>
      authorizedFetch<MaterialResponse[]>("tenant", materialsBasePath(courseId, moduleId, lessonId)),
    enabled:
      (options?.enabled ?? true) &&
      courseId.length > 0 &&
      moduleId.length > 0 &&
      lessonId.length > 0,
  });
}

/** `POST .../materials` (multipart) — relies on the `apiFetch` fix that omits `Content-Type` for a `FormData` body. */
export function useCreateMaterial(courseId: string, moduleId: string, lessonId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ title, file }: MaterialCreateInput) => {
      const formData = new FormData();
      formData.append("file", file);
      formData.append("title", title);
      return authorizedFetch<MaterialResponse>(
        "tenant",
        materialsBasePath(courseId, moduleId, lessonId),
        { method: "POST", body: formData }
      );
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: materialKeys.list(courseId, moduleId, lessonId) });
    },
  });
}

/** `PATCH .../materials/{materialId}` — full-resource replace; see `MaterialUpdateRequest` doc comment. */
export function useUpdateMaterial(courseId: string, moduleId: string, lessonId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ materialId, body }: { materialId: string; body: MaterialUpdateRequest }) =>
      authorizedFetch<MaterialResponse>(
        "tenant",
        `${materialsBasePath(courseId, moduleId, lessonId)}/${materialId}`,
        { method: "PATCH", body: JSON.stringify(body) }
      ),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: materialKeys.list(courseId, moduleId, lessonId) });
    },
  });
}

export function useDeleteMaterial(courseId: string, moduleId: string, lessonId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (materialId: string) =>
      authorizedFetch<null>(
        "tenant",
        `${materialsBasePath(courseId, moduleId, lessonId)}/${materialId}`,
        { method: "DELETE" }
      ),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: materialKeys.list(courseId, moduleId, lessonId) });
    },
  });
}

/**
 * `GET .../materials/{materialId}/download-url` — a **mutation**, not a
 * query: a short-lived signed URL must be fetched fresh on every "View"
 * click and never cached/prefetched/stored (`.claude/rules/security.md`), so
 * this deliberately does not go through React Query's query cache. Callers
 * trigger it imperatively (`mutateAsync(materialId)`) and immediately
 * `window.open` the returned URL — never render or persist it beyond that
 * one action.
 */
export function useMaterialDownloadUrl(courseId: string, moduleId: string, lessonId: string) {
  const { authorizedFetch } = useAuth();
  return useMutation({
    mutationFn: (materialId: string) =>
      authorizedFetch<MaterialDownloadUrlResponse>(
        "tenant",
        `${materialsBasePath(courseId, moduleId, lessonId)}/${materialId}/download-url`
      ),
  });
}

/** Human-readable file size (e.g. "245 KB") for `MaterialResponse.sizeBytes`. */
export function formatFileSize(bytes: number): string {
  const units = ["B", "KB", "MB", "GB"] as const;
  let value = bytes;
  let unitIndex = 0;
  while (value >= 1024 && unitIndex < units.length - 1) {
    value /= 1024;
    unitIndex += 1;
  }
  const rounded = unitIndex === 0 ? Math.round(value) : Math.round(value * 10) / 10;
  return `${rounded} ${units[unitIndex]}`;
}
