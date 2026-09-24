import type { Page, Route } from "@playwright/test";
import { apiError, apiSuccess } from "./auth-mocks";

/**
 * Shared stateful mocks for Wave 5's new `video-access-management` client
 * (`lib/api/videos.ts`, `/api/v1/videos/**`). Mirrors `materials-mocks.ts`'s
 * established pattern — a single `page.route()` catch-all dispatching on
 * method + parsed pathname, no real backend in this environment.
 */

export const VIDEO_ASSET_ID = "video-asset-1";
export const WATCH_SESSION_ID = "watch-session-1";

function nowIso(): string {
  return new Date().toISOString();
}

export interface VideoAssetRecord {
  id: string;
  originalFilename: string;
  mimeType: string;
  sizeBytes: number;
  durationSeconds: number | null;
  status: "PENDING" | "READY" | "FAILED";
  uploadedBy: string;
  createdAt: string;
}

export function makeVideoAsset(overrides: Partial<VideoAssetRecord> = {}): VideoAssetRecord {
  return {
    id: VIDEO_ASSET_ID,
    originalFilename: "lecture.mp4",
    mimeType: "video/mp4",
    sizeBytes: 2048,
    durationSeconds: 120,
    status: "READY",
    uploadedBy: "teacher-1",
    createdAt: nowIso(),
    ...overrides,
  };
}

export interface PlaybackSessionRecord {
  watchSessionId: string | null;
  playbackToken: string | null;
  signedUrl: string;
  expiresAt: string;
  watermarkText: string | null;
  allowSeeking: boolean;
  allowDownload: boolean;
}

export function makePlaybackSession(overrides: Partial<PlaybackSessionRecord> = {}): PlaybackSessionRecord {
  return {
    watchSessionId: WATCH_SESSION_ID,
    playbackToken: "fake-playback-token",
    signedUrl: "https://cdn.example.test/signed/lecture.mp4?token=abc",
    expiresAt: new Date(Date.now() + 180_000).toISOString(),
    watermarkText: "student-1 · tenant-1",
    allowSeeking: true,
    allowDownload: false,
    ...overrides,
  };
}

export interface ProgressFailure {
  status: number;
  code: string;
  message: string;
}

export interface VideoMockOptions {
  uploadResponse?: Partial<VideoAssetRecord>;
  /** Fails the upload with this status/body instead of succeeding. */
  uploadFailure?: { status: number; code: string; message: string };
  playbackSession?: Partial<PlaybackSessionRecord>;
  /** Fails `POST .../playback-sessions` with this status/body (e.g. 404 anti-enumeration) instead of succeeding. */
  playbackSessionFailure?: { status: number; code: string; message: string };
  /**
   * Consumed in order, one per `POST .../progress` call: the Nth heartbeat
   * fails with `progressFailures[N]` until the queue is exhausted, after
   * which heartbeats succeed with 200. Lets a test drive "heartbeat N
   * returns a 409 POLICY_VIOLATION" deterministically.
   */
  progressFailures?: ProgressFailure[];
}

export interface VideoMockState {
  uploadCalls: number;
  policyRequests: unknown[];
  playbackSessionCalls: number;
  progressCalls: Array<{ playbackToken: string; positionSeconds: number; watchedDeltaSeconds: number }>;
  endCalls: number;
}

export async function setupVideoMocks(page: Page, options: VideoMockOptions = {}): Promise<VideoMockState> {
  const state: VideoMockState = {
    uploadCalls: 0,
    policyRequests: [],
    playbackSessionCalls: 0,
    progressCalls: [],
    endCalls: 0,
  };
  const progressFailureQueue = [...(options.progressFailures ?? [])];

  // `makePlaybackSession`'s `signedUrl` points at a non-existent domain
  // (`cdn.example.test`) — the `<video>` element genuinely attempts a real
  // DNS lookup for it, which fails after a real, variable amount of wall
  // -clock time and fires a native `error` event on the element
  // (triggering `secure-video-player.tsx`'s own bounded auto-refresh-then
  // -give-up logic). That real-network timing races against a test's own
  // deterministic steps (e.g. a faked `page.clock`), so every request to
  // this domain is intercepted and left permanently pending instead —
  // never erroring, never loading, which is all a test needs (no test in
  // this suite asserts on genuine video playback/decoding).
  await page.route("https://cdn.example.test/**", () => new Promise<never>(() => {}));

  await page.route("**/api/v1/videos**", async (route: Route) => {
    const request = route.request();
    const method = request.method();
    const path = new URL(request.url()).pathname;

    if (path.endsWith("/api/v1/videos") && method === "POST") {
      state.uploadCalls += 1;
      if (options.uploadFailure) {
        await route.fulfill({
          status: options.uploadFailure.status,
          contentType: "application/json",
          body: JSON.stringify(apiError(options.uploadFailure.code, options.uploadFailure.message)),
        });
        return;
      }
      await route.fulfill({
        status: 201,
        contentType: "application/json",
        body: JSON.stringify(apiSuccess(makeVideoAsset(options.uploadResponse))),
      });
      return;
    }

    const policyMatch = path.match(/\/api\/v1\/videos\/([^/]+)\/policy$/);
    if (policyMatch && method === "PUT") {
      const body = request.postDataJSON();
      state.policyRequests.push(body);
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(
          apiSuccess({
            videoAssetId: policyMatch[1],
            accessStartAt: body.accessStartAt ?? null,
            accessEndAt: body.accessEndAt ?? null,
            maxViewsPerStudent: body.maxViewsPerStudent ?? null,
            maxWatchDurationSeconds: body.maxWatchDurationSeconds ?? null,
            allowSeeking: body.allowSeeking,
            allowDownload: body.allowDownload,
            watermarkEnabled: body.watermarkEnabled,
            maxConcurrentSessions: body.maxConcurrentSessions ?? 1,
          })
        ),
      });
      return;
    }

    const sessionMatch = path.match(/\/api\/v1\/videos\/([^/]+)\/playback-sessions$/);
    if (sessionMatch && method === "POST") {
      state.playbackSessionCalls += 1;
      if (options.playbackSessionFailure) {
        await route.fulfill({
          status: options.playbackSessionFailure.status,
          contentType: "application/json",
          body: JSON.stringify(
            apiError(options.playbackSessionFailure.code, options.playbackSessionFailure.message)
          ),
        });
        return;
      }
      await route.fulfill({
        status: 201,
        contentType: "application/json",
        body: JSON.stringify(apiSuccess(makePlaybackSession(options.playbackSession))),
      });
      return;
    }

    const progressMatch = path.match(/\/api\/v1\/videos\/playback-sessions\/([^/]+)\/progress$/);
    if (progressMatch && method === "POST") {
      const body = request.postDataJSON();
      state.progressCalls.push(body);
      const failure = progressFailureQueue.shift();
      if (failure) {
        await route.fulfill({
          status: failure.status,
          contentType: "application/json",
          body: JSON.stringify(apiError(failure.code, failure.message)),
        });
        return;
      }
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(apiSuccess(null)),
      });
      return;
    }

    const endMatch = path.match(/\/api\/v1\/videos\/playback-sessions\/([^/]+)\/end$/);
    if (endMatch && method === "POST") {
      state.endCalls += 1;
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(apiSuccess(null)),
      });
      return;
    }

    await route.fulfill({
      status: 404,
      contentType: "application/json",
      body: JSON.stringify(apiError("NOT_FOUND", `Unhandled video mock route: ${method} ${path}`)),
    });
  });

  return state;
}
