import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { z } from "zod";
import { useAuth } from "@/lib/auth/auth-context";
import { isApiClientError } from "@/lib/api/error";
import type { PageResponse } from "@/lib/api/courses";

/**
 * Klass gaps round 2 (G1) - the institute's document / image library
 * (`/api/v1/media-files`, backend `MediaFileController`).
 *
 * The tenant, the uploader and every permission are decided on the server:
 * nothing here sends a tenant id, and `canEdit` / `canDelete` are server-computed
 * hints that only decide which buttons are shown - the backend re-checks every
 * rename / delete. Image `thumbnailUrl`s and `open-url` links are short-lived
 * signed URLs; they are never stored beyond the current render / click.
 */

export type MediaFileKind = "DOCUMENT" | "IMAGE" | "OTHER";

export interface MediaFile {
  id: string;
  kind: MediaFileKind;
  title: string;
  originalFilename: string;
  contentType: string;
  sizeBytes: number;
  uploadedBy: string;
  uploaderName: string | null;
  createdAt: string;
  /** Live lesson materials using this file; a file in use cannot be deleted (409 `MEDIA_ATTACHED`). */
  attachedLessonCount: number;
  /** IMAGE only: a 5-minute signed inline URL. May expire while the page is open - refetch the list. */
  thumbnailUrl: string | null;
  canEdit: boolean;
  canDelete: boolean;
}

export interface MediaFileSummary {
  documents: number;
  images: number;
  other: number;
  total: number;
}

export interface MediaFileListParams {
  q?: string;
  kind?: MediaFileKind;
  page?: number;
  size?: number;
}

export interface MediaFileOpenUrl {
  url: string;
  expiresAt: string | null;
}

/** Client-side UX checks only - the server content-sniffs and enforces the real limits (R2-D3). */
export const MEDIA_FILE_ACCEPT = ".pdf,.png,.jpg,.jpeg,.gif,.txt";
export const MEDIA_FILE_MAX_BYTES = 25 * 1024 * 1024;
const ALLOWED_EXTENSIONS = ["pdf", "png", "jpg", "jpeg", "gif", "txt"];

/** A user-facing reason the chosen file can't be sent, or null when it looks fine. */
export function precheckMediaFile(file: File): string | null {
  if (file.size === 0) return "This file is empty.";
  if (file.size > MEDIA_FILE_MAX_BYTES) return "This file is larger than 25 MB.";
  const extension = file.name.includes(".") ? file.name.split(".").pop()!.toLowerCase() : "";
  if (!ALLOWED_EXTENSIONS.includes(extension)) {
    return "This file type isn't supported. Upload a PDF, PNG, JPEG, GIF or plain-text file.";
  }
  return null;
}

const TITLE_MAX = 255;

/** Rename form (UX only; the server re-validates and its field errors are shown). */
export const mediaFileRenameSchema = z.object({
  title: z.string().trim().min(1, "Enter a title.").max(TITLE_MAX, `Use at most ${TITLE_MAX} characters.`),
});
export type MediaFileRenameFormValues = z.infer<typeof mediaFileRenameSchema>;

/** NEW MEDIA form: optional title plus a file that passes {@link precheckMediaFile}. */
export const mediaFileUploadSchema = z.object({
  title: z.string().trim().max(TITLE_MAX, `Use at most ${TITLE_MAX} characters.`),
  file: z
    .custom<File | null>((value) => value === null || value instanceof File)
    .refine((file): file is File => file instanceof File, "Choose a file to upload.")
    .superRefine((file, ctx) => {
      const problem = file instanceof File ? precheckMediaFile(file) : null;
      if (problem) ctx.addIssue({ code: "custom", message: problem });
    }),
});
export type MediaFileUploadFormValues = z.input<typeof mediaFileUploadSchema>;
export type MediaFileUploadFormOutput = z.output<typeof mediaFileUploadSchema>;

export const mediaFileKeys = {
  all: ["media-files"] as const,
  lists: () => [...mediaFileKeys.all, "list"] as const,
  list: (params: MediaFileListParams) => [...mediaFileKeys.lists(), params] as const,
  summary: () => [...mediaFileKeys.all, "summary"] as const,
};

const BASE = "/v1/media-files";
const filePath = (id: string) => `${BASE}/${encodeURIComponent(id)}`;

function listPath(params: MediaFileListParams): string {
  const search = new URLSearchParams();
  const q = params.q?.trim();
  if (q) search.set("q", q.slice(0, 200));
  if (params.kind) search.set("kind", params.kind);
  search.set("page", String(params.page ?? 0));
  search.set("size", String(Math.min(params.size ?? 24, 100)));
  return `${BASE}?${search.toString()}`;
}

export function useMediaFiles(params: MediaFileListParams, enabled = true) {
  const { authorizedFetch, session } = useAuth();
  return useQuery({
    queryKey: [...mediaFileKeys.list(params), session?.userId, session?.role, session?.kind],
    queryFn: () => authorizedFetch<PageResponse<MediaFile>>("tenant", listPath(params)),
    enabled: enabled && session?.kind === "tenant" && Boolean(session.userId),
    // Image thumbnails are signed for 5 minutes; refresh well before they lapse.
    refetchInterval: 4 * 60_000,
  });
}

export function useMediaFileSummary(enabled = true) {
  const { authorizedFetch, session } = useAuth();
  return useQuery({
    queryKey: [...mediaFileKeys.summary(), session?.userId, session?.role, session?.kind],
    queryFn: () => authorizedFetch<MediaFileSummary>("tenant", `${BASE}/summary`),
    enabled: enabled && session?.kind === "tenant" && Boolean(session.userId),
  });
}

function useInvalidateMediaFiles() {
  const queryClient = useQueryClient();
  return () => void queryClient.invalidateQueries({ queryKey: mediaFileKeys.all });
}

export function useUploadMediaFile() {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateMediaFiles();
  return useMutation({
    mutationFn: ({ file, title }: { file: File; title?: string }) => {
      const formData = new FormData();
      formData.append("file", file);
      if (title?.trim()) formData.append("title", title.trim());
      return authorizedFetch<MediaFile>("tenant", BASE, { method: "POST", body: formData });
    },
    onSuccess: invalidate,
  });
}

export function useRenameMediaFile() {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateMediaFiles();
  return useMutation({
    mutationFn: ({ id, title }: { id: string; title: string }) =>
      authorizedFetch<MediaFile>("tenant", filePath(id), {
        method: "PATCH",
        body: JSON.stringify({ title }),
      }),
    onSuccess: invalidate,
    onError: (error) => {
      if (isApiClientError(error) && error.status === 404) invalidate();
    },
  });
}

export function useDeleteMediaFile() {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateMediaFiles();
  return useMutation({
    mutationFn: (id: string) => authorizedFetch<null>("tenant", filePath(id), { method: "DELETE" }),
    onSuccess: invalidate,
    // A 404 means the file is already gone (or no longer ours to change): refresh either way.
    onError: (error) => {
      if (isApiClientError(error) && error.status === 404) invalidate();
    },
  });
}

/**
 * `GET .../{id}/open-url` - a mutation, never a cached query: the signed URL is
 * fetched fresh on each "Open" click and only handed to `window.open`.
 */
export function useMediaFileOpenUrl() {
  const { authorizedFetch } = useAuth();
  return useMutation({
    mutationFn: (id: string) => authorizedFetch<MediaFileOpenUrl>("tenant", `${filePath(id)}/open-url`),
  });
}

/** Upload failures mapped to clear copy (415 / 413 / 400 per the contract). */
export function mediaFileUploadErrorMessage(error: unknown): string {
  if (isApiClientError(error)) {
    if (error.status === 415) {
      return "This file type isn't supported. Upload a PDF, PNG, JPEG, GIF or plain-text file.";
    }
    if (error.status === 413) return "This file is larger than 25 MB.";
    if (error.status === 403) return "You don't have permission to upload to the Media Library.";
    if (error.status === 400) {
      return error.message || "The file is empty or the title isn't valid. Check them and try again.";
    }
    return error.message;
  }
  return "Couldn't upload the file. Check your connection and try again.";
}

export function lessonCountLabel(count: number): string {
  return `${count} ${count === 1 ? "lesson" : "lessons"}`;
}

/** Delete failures: 409 `MEDIA_ATTACHED` gets the "remove it from those sessions first" copy. */
export function mediaFileDeleteErrorMessage(error: unknown, attachedLessonCount: number): string {
  if (isApiClientError(error)) {
    if (error.status === 409 || error.code === "MEDIA_ATTACHED") {
      const count = Math.max(attachedLessonCount, 1);
      return `Used in ${lessonCountLabel(count)} — remove it from those sessions first.`;
    }
    if (error.status === 404) return "This file no longer exists or you can't delete it. The list has been refreshed.";
    return error.message;
  }
  return "Couldn't delete the file. Please try again.";
}

export function mediaFileRenameErrorMessage(error: unknown): string {
  if (isApiClientError(error)) {
    if (error.status === 404) return "This file no longer exists or you can't rename it.";
    return error.message;
  }
  return "Couldn't rename the file. Please try again.";
}
