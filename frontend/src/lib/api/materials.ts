import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";
import { isApiClientError } from "@/lib/api/error";

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
 *
 * Extended Wave 5 (plan §3/§4/§5) with the `materialType` discriminator and
 * its per-type fields (`externalUrl`/`noteContent`/`videoAssetId`), the
 * optional `sessionId` association, and the download-limit/availability
 * -window fields — mirrors `MaterialResponse`/`MaterialCreateCommand`
 * field-for-field (backend `com.lms.contentmanagement.material.web.dto`/
 * `.service`).
 */

export type MaterialVisibility = "VISIBLE" | "HIDDEN";

/** Mirrors backend `com.lms.contentmanagement.material.domain.MaterialType`. */
export type MaterialType = "PDF" | "IMAGE" | "DOCUMENT" | "LINK" | "NOTE" | "VIDEO" | "RECORDING" | "OTHER";

/** Mirrors `MaterialResponse` (backend `com.lms.contentmanagement.material.web.dto`) field-for-field. */
export interface MaterialResponse {
  id: string;
  lessonId: string | null;
  sessionId: string | null;
  materialType: MaterialType;
  title: string;
  originalFilename: string | null;
  mimeType: string | null;
  sizeBytes: number | null;
  externalUrl: string | null;
  noteContent: string | null;
  videoAssetId: string | null;
  sequence: number;
  visibility: MaterialVisibility;
  maxDownloads: number | null;
  downloadCount: number;
  availableFromAt: string | null;
  expiryAt: string | null;
  uploadedBy: string;
  createdAt: string;
  updatedAt: string;
}

/**
 * Input to `useCreateMaterial` — sent as `multipart/form-data`, never JSON
 * (mirrors `MaterialController#createMaterial`'s multipart request-param
 * shape, never a single JSON body). `file` is required (and only relevant)
 * for an uploaded-file `materialType` (`PDF`/`IMAGE`/`DOCUMENT`/`OTHER`);
 * `externalUrl` only for `LINK`; `noteContent` only for `NOTE`;
 * `videoAssetId` only for `VIDEO`/`RECORDING` — see `MaterialType`'s backend
 * javadoc for the full per-type contract. `useCreateMaterial`'s mutation
 * function appends only the fields actually present, so a caller building
 * this from a per-type form (see `lib/validation/material.ts`'s
 * discriminated-union schema) never has to fabricate empty strings for
 * fields that don't apply to the chosen type.
 */
export interface MaterialCreateInput {
  title: string;
  materialType: MaterialType;
  file?: File;
  externalUrl?: string;
  noteContent?: string;
  videoAssetId?: string;
  sessionId?: string;
  maxDownloads?: number;
  /** Raw ISO-8601 instant string (e.g. from `<input type="datetime-local">`, converted at submit time) — never a `Date`, matching `MaterialController#parseInstant`'s expected wire format. */
  availableFromAt?: string;
  /** Raw ISO-8601 instant string — see `availableFromAt`. */
  expiryAt?: string;
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
  expiresAt: string | null;
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

/**
 * `POST .../materials` (multipart) — relies on the `apiFetch` fix that omits
 * `Content-Type` for a `FormData` body. Builds the multipart body
 * conditionally on `materialType`: `file` is appended only when present
 * (uploaded-file types), `externalUrl`/`noteContent`/`videoAssetId` only
 * when present (their respective types), `sessionId`/`maxDownloads`/
 * `availableFromAt`/`expiryAt` only when present (all optional regardless of
 * type). A 400 for a missing required-for-this-type field comes back as the
 * standard `ApiClientError` shape (`error.fieldErrors`) — see
 * `lib/validation/material.ts`'s Zod schema for the client-side mirror of
 * this same per-type requirement (UX convenience only, never a substitute
 * for this backend validation).
 */
export function useCreateMaterial(courseId: string, moduleId: string, lessonId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: MaterialCreateInput) => {
      const formData = new FormData();
      formData.append("title", input.title);
      formData.append("materialType", input.materialType);
      if (input.file) formData.append("file", input.file);
      if (input.externalUrl) formData.append("externalUrl", input.externalUrl);
      if (input.noteContent) formData.append("noteContent", input.noteContent);
      if (input.videoAssetId) formData.append("videoAssetId", input.videoAssetId);
      if (input.sessionId) formData.append("sessionId", input.sessionId);
      if (input.maxDownloads !== undefined) formData.append("maxDownloads", String(input.maxDownloads));
      if (input.availableFromAt) formData.append("availableFromAt", input.availableFromAt);
      if (input.expiryAt) formData.append("expiryAt", input.expiryAt);
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
 *
 * Wave 5: this now enforces type-dependent behavior server-side (plan §4) —
 * callers must handle each of these via `isApiClientError(error).code`
 * rather than a single generic failure message:
 * - `403 DOWNLOAD_LIMIT_REACHED` — `material.maxDownloads` already reached.
 * - `403 MATERIAL_NOT_YET_AVAILABLE` — before `availableFromAt`.
 * - `403 MATERIAL_EXPIRED` — after `expiryAt`.
 * - `404 NOT_FOUND` — anti-enumeration (no access), or a structurally
 *   inapplicable action for this material's type (`NOTE`/`VIDEO`/`RECORDING`
 *   — those are never fetched through this endpoint; see
 *   `material-row.tsx`/the Student materials page for the per-type
 *   rendering that avoids calling this at all for those types).
 * For `LINK` materials this still returns `{url, expiresAt}` (the raw
 * `externalUrl`, `expiresAt` a far-future/null-ish placeholder) — callers
 * open `result.url`, never the list response's own `externalUrl` field
 * directly, since the availability/limit guards only run on this endpoint.
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

/**
 * Shared friendly-copy mapper for a failed `useMaterialDownloadUrl` call —
 * used by both the Teacher `MaterialRow` and the Student materials page so
 * the three type-dependent 403 codes render identically everywhere (per
 * `.claude/rules/frontend.md`'s "shared, reusable" state-handling guidance),
 * never a generic "something went wrong" for a failure the backend already
 * told us the specific reason for. Anti-enumeration 404s are deliberately
 * NOT special-cased here — callers on an anti-enumeration surface (the
 * Student materials page) branch on status before ever reaching this
 * helper, since a 404 there must stay backend-message-free.
 */
export function materialDownloadErrorMessage(
  error: unknown,
  fallback: string = "Couldn't open this material. Please try again."
): string {
  if (isApiClientError(error)) {
    switch (error.code) {
      case "MATERIAL_NOT_YET_AVAILABLE":
        return "This material isn't available yet.";
      case "MATERIAL_EXPIRED":
        return "This material has expired.";
      case "DOWNLOAD_LIMIT_REACHED":
        return "The download limit for this material has been reached.";
      default:
        return fallback;
    }
  }
  return fallback;
}

/** Human-readable file size (e.g. "245 KB") for `MaterialResponse.sizeBytes`. */
export function formatFileSize(bytes: number | null | undefined): string {
  if (bytes == null) return "";
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
