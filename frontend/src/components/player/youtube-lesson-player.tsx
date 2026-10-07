"use client";

import { useEffect } from "react";
import { AlertCircle, Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { NativePlayerLauncher } from "@/components/player/native-player-launcher";
import { playbackErrorMessage, VIDEO_NOT_AVAILABLE } from "@/components/player/playback-errors";
import { useStartPlayback } from "@/lib/api/playback";

/**
 * Student WEB player for a YouTube LessonMedia (VIDEO Phase 7, B3-only).
 *
 * - Playback is authorized by `POST /api/v1/playback/sessions` when this
 *   component mounts. The Student materials page only mounts it once the
 *   Student opens the video, so nothing is authorized for unopened videos.
 * - YouTube mode is **LMS-gated, not protected** (B3): the embed needs the
 *   video id, and anyone holding an unlisted id can watch it on YouTube.
 *   The copy below never claims otherwise.
 * - Nothing is drawn over the YouTube player (YouTube's Required Minimum
 *   Functionality forbids overlays); a watermark, when the descriptor has
 *   one, goes in a band *below* the player (VID-ADR-007).
 * - Denials use fixed copy, never the backend's message: 403/404 are
 *   anti-enumeration and must look the same whatever the reason.
 */

/** YouTube video ids are 11 URL-safe characters; anything else is never embedded. */
const YOUTUBE_VIDEO_ID = /^[A-Za-z0-9_-]{11}$/;

interface YouTubeLessonPlayerProps {
  lessonMediaId: string;
  title: string;
}

export function YouTubeLessonPlayer({ lessonMediaId, title }: YouTubeLessonPlayerProps) {
  const playback = useStartPlayback();
  const { mutate } = playback;

  useEffect(() => {
    mutate(lessonMediaId);
  }, [lessonMediaId, mutate]);

  if (playback.isError) {
    const { message, retryable } = playbackErrorMessage(playback.error);
    return (
      <div
        role="alert"
        className="flex flex-col items-start gap-2 rounded-md border border-border bg-muted/20 p-3 text-sm"
      >
        <p className="flex items-center gap-2 text-foreground">
          <AlertCircle aria-hidden="true" className="size-4 shrink-0 text-destructive" />
          {message}
        </p>
        {retryable ? (
          <Button type="button" variant="outline" size="sm" onClick={() => mutate(lessonMediaId)}>
            Try again
          </Button>
        ) : null}
      </div>
    );
  }

  // VIDEO Phase 8: the institute requires the AOC Player - hand over to the launcher.
  if (playback.data?.channel === "NATIVE" && playback.data.sessionId && playback.data.launch) {
    return (
      <NativePlayerLauncher
        lessonMediaId={lessonMediaId}
        title={title}
        initial={{ sessionId: playback.data.sessionId, launch: playback.data.launch }}
      />
    );
  }

  const descriptor = playback.data?.descriptor;
  if (!descriptor) {
    return (
      <div
        role="status"
        className="flex aspect-video w-full items-center justify-center gap-2 rounded-md bg-muted text-sm text-muted-foreground"
      >
        <Loader2 aria-hidden="true" className="size-4 animate-spin" />
        Preparing video…
      </div>
    );
  }

  const videoId = descriptor.providerVideoId;
  if (descriptor.provider !== "YOUTUBE" || !videoId || !YOUTUBE_VIDEO_ID.test(videoId)) {
    return (
      <p role="alert" className="rounded-md border border-border bg-muted/20 p-3 text-sm text-foreground">
        {VIDEO_NOT_AVAILABLE}
      </p>
    );
  }

  const embedUrl = `https://www.youtube-nocookie.com/embed/${videoId}?rel=0&playsinline=1`;

  return (
    <div className="flex flex-col gap-2">
      <div className="relative aspect-video w-full overflow-hidden rounded-md bg-black">
        <iframe
          src={embedUrl}
          title={title}
          className="absolute inset-0 size-full border-0"
          allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share"
          allowFullScreen
          referrerPolicy="strict-origin-when-cross-origin"
        />
      </div>
      {descriptor.watermarkText ? (
        <p
          data-testid="watermark-band"
          className="truncate rounded-md bg-muted px-3 py-1 text-center text-xs text-muted-foreground"
        >
          {descriptor.watermarkText}
        </p>
      ) : null}
      <p className="text-xs text-muted-foreground">This video plays through YouTube.</p>
    </div>
  );
}
