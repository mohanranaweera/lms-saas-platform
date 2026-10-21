import { useMutation } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";
import type { MediaAssetProvider } from "@/lib/api/media";

/**
 * VIDEO Phase 7 (B3-only WEB scope) — `POST /api/v1/playback/sessions`
 * (`docs/api/playback-authorization.md`). The backend decides everything:
 * tenant (from the session), Student role, enrollment, visibility,
 * availability window, asset status, channel state and the platform flag.
 * The client only sends the LessonMedia id.
 */

/** Mirrors `PlaybackDescriptor`. YouTube WEB descriptors carry only the provider video id. */
export interface PlaybackDescriptor {
  sessionId: string | null;
  provider: MediaAssetProvider;
  providerVideoId: string | null;
  signedUrl: string | null;
  expiresAt: string;
  title: string | null;
  durationSeconds: number | null;
  watermarkText: string | null;
  allowSeeking: boolean;
  allowDownload: boolean;
}

/** VIDEO Phase 8: a one-time `aocplayer://play?code=` link for the AOC Player. */
export interface NativeLaunch {
  url: string;
  expiresAt: string;
}

/**
 * Mirrors `PlayerDtos.PlaybackStartResponse`. `WEB` carries a descriptor;
 * `NATIVE` (the institute requires the AOC Player) carries a launch link and
 * a session id to poll, and no descriptor.
 */
export interface PlaybackStart {
  sessionId: string | null;
  channel: "WEB" | "NATIVE";
  expiresAt: string;
  descriptor: PlaybackDescriptor | null;
  launch: NativeLaunch | null;
}

export function useStartPlayback() {
  const { authorizedFetch } = useAuth();
  return useMutation({
    mutationFn: (lessonMediaId: string) =>
      authorizedFetch<PlaybackStart>("tenant", "/v1/playback/sessions", {
        method: "POST",
        body: JSON.stringify({ lessonMediaId }),
      }),
  });
}
