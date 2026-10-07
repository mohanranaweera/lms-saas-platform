"use client";

import { useEffect } from "react";
import { AlertCircle, Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useCreateWebPlayback } from "@/lib/api/playback";
import { isApiClientError } from "@/lib/api/error";

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

const NOT_AVAILABLE = "This video isn't available. It may have been removed, or you may not have access to it.";

function playbackErrorMessage(error: unknown): { message: string; retryable: boolean } {
  if (!isApiClientError(error)) {
    return { message: "Couldn't load this video. Please try again.", retryable: true };
  }
  if (error.status === 403 || error.status === 404) {
    return { message: NOT_AVAILABLE, retryable: false };
  }
  switch (error.code) {
    case "MEDIA_NOT_READY":
      return { message: "This video is still processing. Try again in a few minutes.", retryable: true };
    case "VIDEO_PLAYBACK_DISABLED":
      return { message: "YouTube videos can't be played right now. Please try again later.", retryable: true };
    case "VIDEO_PROVIDER_DISABLED_BY_PLATFORM":
      return { message: "This video's channel isn't available right now.", retryable: false };
  }
  if (error.status === 409) {
    return { message: "This video isn't available right now.", retryable: false };
  }
  return { message: "Couldn't load this video. Please try again.", retryable: true };
}

interface YouTubeLessonPlayerProps {
  lessonMediaId: string;
  title: string;
}

export function YouTubeLessonPlayer({ lessonMediaId, title }: YouTubeLessonPlayerProps) {
  const playback = useCreateWebPlayback();
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
        {NOT_AVAILABLE}
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
