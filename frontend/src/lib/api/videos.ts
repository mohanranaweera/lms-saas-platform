import { useMutation } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";

/**
 * Typed client + React Query hooks for the new video-access-management
 * domain (`/api/v1/videos/**`, `com.lms.videoaccessmanagement.web.VideoController`),
 * Wave 5 (plan §4/§5). Mirrors `lib/api/materials.ts`'s exact style (`useAuth().
 * authorizedFetch("tenant", ...)`, no ad hoc `fetch`, mutations rather than
 * cached queries for anything that must be re-issued fresh — see each hook's
 * own doc comment for why).
 *
 * There is deliberately no `useX Query`/query-key object here (unlike
 * `materialKeys` in `materials.ts`): every one of this domain's reads that
 * matters to the UI (a playback session, a freshly-issued asset) is a
 * one-shot, never-cached, never-replayed value by design (`.claude/rules/
 * security.md`'s "never a stable or predictable URL" + "single-use" video
 * rules) — caching any of these in React Query's query cache would be the
 * exact mistake `useMaterialDownloadUrl`'s doc comment already warns
 * against, just more security-sensitive here.
 */

export type VideoAssetStatus = "PENDING" | "READY" | "FAILED";

/** Mirrors `VideoAssetResponse` (backend `com.lms.videoaccessmanagement.web.dto`) field-for-field. Metadata only — never a raw storage URL/key. */
export interface VideoAssetResponse {
  id: string;
  originalFilename: string;
  mimeType: string;
  sizeBytes: number;
  durationSeconds: number | null;
  status: VideoAssetStatus;
  uploadedBy: string;
  createdAt: string;
}

/** Mirrors `VideoPlaybackPolicyRequest` — the four booleans + `maxConcurrentSessions` always required together (the backend has server-side defaults only when the field is entirely omitted from the JSON body; this client always sends all four booleans explicitly to avoid relying on that ambiguity from a form). */
export interface VideoPlaybackPolicyRequest {
  accessStartAt?: string | null;
  accessEndAt?: string | null;
  maxViewsPerStudent?: number | null;
  maxWatchDurationSeconds?: number | null;
  allowSeeking: boolean;
  allowDownload: boolean;
  watermarkEnabled: boolean;
  maxConcurrentSessions?: number | null;
}

/** Mirrors `VideoPlaybackPolicyResponse` field-for-field. */
export interface VideoPlaybackPolicyResponse {
  videoAssetId: string;
  accessStartAt: string | null;
  accessEndAt: string | null;
  maxViewsPerStudent: number | null;
  maxWatchDurationSeconds: number | null;
  allowSeeking: boolean;
  allowDownload: boolean;
  watermarkEnabled: boolean;
  maxConcurrentSessions: number;
}

/**
 * Mirrors `PlaybackSessionResponse` field-for-field. `watchSessionId`/
 * `playbackToken` are `null` for a Teacher preview session (no policy
 * enforcement, no heartbeat/end lifecycle applies) — see
 * `VideoController`/`VideoPlaybackSessionService`'s own javadoc. There is no
 * separate `watermarkEnabled` boolean on this response — a non-empty
 * `watermarkText` IS the on/off signal (verified against the real backend
 * DTO; see `secure-video-player.tsx`'s doc comment for where this is used).
 */
export interface PlaybackSessionResponse {
  watchSessionId: string | null;
  playbackToken: string | null;
  signedUrl: string;
  expiresAt: string;
  watermarkText: string | null;
  allowSeeking: boolean;
  allowDownload: boolean;
}

/** Mirrors `PlaybackProgressRequest`. */
export interface PlaybackProgressInput {
  playbackToken: string;
  positionSeconds: number;
  watchedDeltaSeconds: number;
}

function videosBasePath(): string {
  return "/v1/videos";
}

/**
 * `POST /videos` (multipart, field `file`) — how a Teacher obtains a
 * `videoAssetId` to pass into `useCreateMaterial` for a `VIDEO`/`RECORDING`
 * material. Returns the created `VideoAssetResponse` (`status` starts
 * `PENDING`/`READY`/`FAILED` — the upload form should not let the Teacher
 * proceed to attach-as-material while `status === "FAILED"`).
 */
export function useUploadVideo() {
  const { authorizedFetch } = useAuth();
  return useMutation({
    mutationFn: (file: File) => {
      const formData = new FormData();
      formData.append("file", file);
      return authorizedFetch<VideoAssetResponse>("tenant", videosBasePath(), {
        method: "POST",
        body: formData,
      });
    },
  });
}

/**
 * `PUT /videos/{id}/policy` — upserts the playback policy for a video asset.
 * Not tied to a single query-invalidation target (no cached policy read
 * exists yet in this client), so this is a plain mutation with no
 * `onSuccess` cache side effect.
 */
export function useUpsertVideoPolicy() {
  const { authorizedFetch } = useAuth();
  return useMutation({
    mutationFn: ({ videoAssetId, body }: { videoAssetId: string; body: VideoPlaybackPolicyRequest }) =>
      authorizedFetch<VideoPlaybackPolicyResponse>(
        "tenant",
        `${videosBasePath()}/${videoAssetId}/policy`,
        { method: "PUT", body: JSON.stringify(body) }
      ),
  });
}

/**
 * `POST /videos/{id}/playback-sessions` (no body) — issues a fresh,
 * short-lived, single-use playback grant. A **mutation**, never a query, for
 * the identical reason `useMaterialDownloadUrl` is a mutation: this must be
 * requested fresh every time playback starts (or resumes after the previous
 * grant expires) and must never be cached/replayed. Entitlement failures
 * (unenrolled, wrong tenant, video not attached to any material) come back
 * as a plain 404 (anti-enumeration) — callers render the same calm
 * "not available" treatment they'd use for any other 404, never a raw error
 * dump.
 */
export function useIssuePlaybackSession() {
  const { authorizedFetch } = useAuth();
  return useMutation({
    mutationFn: (videoAssetId: string) =>
      authorizedFetch<PlaybackSessionResponse>(
        "tenant",
        `${videosBasePath()}/${videoAssetId}/playback-sessions`,
        { method: "POST" }
      ),
  });
}

/**
 * `POST /videos/playback-sessions/{id}/progress` — the periodic heartbeat.
 * Deliberately surfaces every failure (never swallowed here) so the caller
 * (`secure-video-player.tsx`) can branch on `error.code`:
 * - `409 SEEK_NOT_ALLOWED` — snap the playhead back, show a brief notice,
 *   session stays `ACTIVE`.
 * - `409 POLICY_VIOLATION` — the session was just revoked server-side; stop
 *   playback and show a terminal message.
 * - `401 PLAYBACK_TOKEN_INVALID` — token/session no longer valid; stop
 *   playback and show a terminal message.
 */
export function useRecordPlaybackProgress() {
  const { authorizedFetch } = useAuth();
  return useMutation({
    mutationFn: ({
      watchSessionId,
      body,
    }: {
      watchSessionId: string;
      body: PlaybackProgressInput;
    }) =>
      authorizedFetch<null>("tenant", `${videosBasePath()}/playback-sessions/${watchSessionId}/progress`, {
        method: "POST",
        body: JSON.stringify(body),
      }),
  });
}

/**
 * `POST /videos/playback-sessions/{id}/end` (no body) — client-driven
 * graceful end, idempotent server-side. Callers fire this best-effort on
 * unmount/navigate-away/`onEnded` and should NOT block navigation on it or
 * surface its failure to the user — mirrors the module brief's "fire-and
 * -forget-ish" instruction. This hook itself still lets a genuine failure
 * reach the caller (`mutateAsync` rejects normally) so a caller that wants
 * to log it can; it does not silently swallow errors itself, since a plain
 * `useMutation` has no place to log from — see `secure-video-player.tsx` for
 * the actual `.catch()` that discards/logs the error.
 */
export function useEndPlaybackSession() {
  const { authorizedFetch } = useAuth();
  return useMutation({
    mutationFn: (watchSessionId: string) =>
      authorizedFetch<null>("tenant", `${videosBasePath()}/playback-sessions/${watchSessionId}/end`, {
        method: "POST",
      }),
  });
}
