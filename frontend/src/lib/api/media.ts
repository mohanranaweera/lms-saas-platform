import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";
import type { PageResponse } from "@/lib/api/courses";

/**
 * VIDEO Phases 2, 4 and 5 - the Media Library (`/api/v1/media`). Contract:
 * `docs/api/video-access-management.md` ("Media Library", "Media Library
 * management", "Media upload to YouTube").
 *
 * The tenant, the uploader, the S3 staging key and the YouTube session all
 * live on the server; nothing here sends a tenant id. Visibility and every
 * permission are decided by the backend (a Teacher sees only their own
 * uploads; other staff need `MATERIALS`).
 */

export type MediaAssetProvider = "INTERNAL" | "YOUTUBE";
export type MediaPrivacyStatus = "PRIVATE" | "UNLISTED" | "PUBLIC";
export type MediaProcessingStatus = "CREATED" | "UPLOADING" | "UPLOADED" | "PROCESSING" | "READY" | "FAILED" | "DELETED";
export type MediaUploadState =
  | "AWAITING_PARTS"
  | "QUEUED"
  | "TRANSFERRING"
  | "RETRY_WAIT"
  | "QUOTA_PAUSED"
  | "PARKED_RECONNECT"
  | "COMPLETED"
  | "FAILED"
  | "CANCELLED";

export interface MediaUploadProgress {
  state: MediaUploadState;
  bytesSent: number;
  totalBytes: number;
  /** Client-safe reason for a paused / parked / failed upload. */
  message: string | null;
}

export interface MediaAsset {
  id: string;
  provider: MediaAssetProvider;
  title: string;
  description: string | null;
  thumbnailUrl: string | null;
  durationSeconds: number | null;
  privacyStatus: MediaPrivacyStatus | null;
  processingStatus: MediaProcessingStatus;
  failureReason: string | null;
  uploadedBy: string;
  createdAt: string;
  capabilities: Record<string, boolean>;
  enabledPlaybackModes: string[];
  uploadProgress: MediaUploadProgress | null;
  /** A lesson uses this media; it cannot be deleted. */
  attached: boolean;
  /**
   * VIDEO Phase 5c: the video's YouTube watch page, once YouTube has it (Media Library only).
   * Unlisted videos can be watched by anyone with this link - never show it to students.
   */
  youtubeUrl: string | null;
}

export type MediaChannelState = "NOT_CONNECTED" | "CONNECTED" | "EXPIRED" | "DISABLED";

export interface MediaUploadLimits {
  uploadsEnabled: boolean;
  channel: MediaChannelState;
  /** False for a channel connected before editing was allowed - reconnect to edit. */
  editingEnabled: boolean;
  dailyLimit: number;
  usedToday: number;
  maxFileSizeBytes: number;
  partSizeBytes: number;
  allowedMimeTypes: string[];
  /** VIDEO Phase 5b: false for a teacher not on the institute channel's allowed-teacher list. */
  canUpload: boolean;
  /** The server's explanation when `canUpload` is false. */
  notAllowedReason: string | null;
  /** VIDEO Phase 5c: which switch turned uploads off - the platform's or the institute's. */
  uploadsDisabledBy: "PLATFORM" | "INSTITUTE" | null;
  /** The server's explanation when `uploadsEnabled` is false. */
  uploadsDisabledReason: string | null;
}

export interface PresignedPart {
  partNumber: number;
  url: string;
  expiresAt: string;
}

export interface UploadInstructions {
  partSizeBytes: number;
  partCount: number;
  parts: PresignedPart[];
}

export interface StartedUpload {
  mediaAsset: MediaAsset;
  upload: UploadInstructions;
}

export interface StartUploadInput {
  title: string;
  description?: string;
  privacyStatus?: "UNLISTED" | "PRIVATE";
  fileName: string;
  mimeType: string;
  sizeBytes: number;
}

export interface MediaListParams {
  q?: string;
  status?: MediaProcessingStatus;
  page?: number;
  size?: number;
}

export interface MediaUpdateInput {
  title?: string;
  description?: string;
  privacyStatus?: "UNLISTED" | "PRIVATE";
}

export const mediaKeys = {
  all: ["media"] as const,
  list: (params: MediaListParams) => [...mediaKeys.all, "list", params] as const,
  detail: (id: string) => [...mediaKeys.all, "detail", id] as const,
  limits: () => [...mediaKeys.all, "limits"] as const,
};

const BASE = "/v1/media";

/** States in which the server is still working on the media - worth polling. */
export function isMediaInFlight(asset: MediaAsset): boolean {
  if (
    asset.processingStatus === "UPLOADING" ||
    asset.processingStatus === "UPLOADED" ||
    asset.processingStatus === "PROCESSING"
  ) {
    return true;
  }
  const state = asset.uploadProgress?.state;
  return state === "QUEUED" || state === "TRANSFERRING" || state === "RETRY_WAIT";
}

function listPath(params: MediaListParams): string {
  const search = new URLSearchParams();
  if (params.q?.trim()) search.set("q", params.q.trim());
  if (params.status) search.set("status", params.status);
  search.set("page", String(params.page ?? 0));
  search.set("size", String(params.size ?? 24));
  return `${BASE}?${search.toString()}`;
}

export function useMediaList(params: MediaListParams) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: mediaKeys.list(params),
    queryFn: () => authorizedFetch<PageResponse<MediaAsset>>("tenant", listPath(params)),
    refetchInterval: (query) => (query.state.data?.content.some(isMediaInFlight) ? 10_000 : false),
  });
}

export function useMediaAsset(id: string) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: mediaKeys.detail(id),
    queryFn: () => authorizedFetch<MediaAsset>("tenant", `${BASE}/${encodeURIComponent(id)}`),
    refetchInterval: (query) => (query.state.data && isMediaInFlight(query.state.data) ? 5_000 : false),
  });
}

export function useMediaUploadLimits(enabled = true) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: mediaKeys.limits(),
    queryFn: () => authorizedFetch<MediaUploadLimits>("tenant", `${BASE}/upload-limits`),
    enabled,
  });
}

/** The upload calls the panel makes in sequence (not React Query mutations: one flow, one owner). */
export function useMediaUploadApi() {
  const { authorizedFetch } = useAuth();
  return {
    start: (input: StartUploadInput) =>
      authorizedFetch<StartedUpload>("tenant", BASE, { method: "POST", body: JSON.stringify(input) }),
    presign: (assetId: string, partNumbers: number[]) =>
      authorizedFetch<UploadInstructions>("tenant", `${BASE}/${encodeURIComponent(assetId)}/upload/parts`, {
        method: "POST",
        body: JSON.stringify({ partNumbers }),
      }),
    complete: (assetId: string, parts: { partNumber: number; etag: string }[]) =>
      authorizedFetch<MediaAsset>("tenant", `${BASE}/${encodeURIComponent(assetId)}/upload/complete`, {
        method: "POST",
        body: JSON.stringify({ parts }),
      }),
    cancel: (assetId: string) =>
      authorizedFetch<MediaAsset>("tenant", `${BASE}/${encodeURIComponent(assetId)}/upload/cancel`, {
        method: "POST",
      }),
  };
}

export function useCancelMediaUpload() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (assetId: string) =>
      authorizedFetch<MediaAsset>("tenant", `${BASE}/${encodeURIComponent(assetId)}/upload/cancel`, {
        method: "POST",
      }),
    onSettled: () => void queryClient.invalidateQueries({ queryKey: mediaKeys.all }),
  });
}

export function useRetryMediaUpload() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (assetId: string) =>
      authorizedFetch<MediaAsset>("tenant", `${BASE}/${encodeURIComponent(assetId)}/upload/retry`, {
        method: "POST",
      }),
    onSettled: () => void queryClient.invalidateQueries({ queryKey: mediaKeys.all }),
  });
}

export function useUpdateMedia(id: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: MediaUpdateInput) =>
      authorizedFetch<MediaAsset>("tenant", `${BASE}/${encodeURIComponent(id)}`, {
        method: "PATCH",
        body: JSON.stringify(input),
      }),
    onSuccess: (data) => {
      queryClient.setQueryData(mediaKeys.detail(id), data);
      void queryClient.invalidateQueries({ queryKey: [...mediaKeys.all, "list"] });
    },
  });
}

export function useDeleteMedia() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: string) =>
      authorizedFetch<null>("tenant", `${BASE}/${encodeURIComponent(id)}`, { method: "DELETE" }),
    onSuccess: (_data, id) => {
      queryClient.removeQueries({ queryKey: mediaKeys.detail(id) });
      void queryClient.invalidateQueries({ queryKey: [...mediaKeys.all, "list"] });
    },
  });
}
