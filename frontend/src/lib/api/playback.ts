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

/** Mirrors `WebPlaybackSessionResponse`. */
export interface WebPlaybackSession {
  sessionId: string | null;
  expiresAt: string;
  descriptor: PlaybackDescriptor;
}

export function useCreateWebPlayback() {
  const { authorizedFetch } = useAuth();
  return useMutation({
    mutationFn: (lessonMediaId: string) =>
      authorizedFetch<WebPlaybackSession>("tenant", "/v1/playback/sessions", {
        method: "POST",
        body: JSON.stringify({ lessonMediaId }),
      }),
  });
}
