"use client";

import Link from "next/link";
import { AlertCircle } from "lucide-react";
import { Button, buttonVariants } from "@/components/ui/button";
import { playbackErrorMessage } from "@/components/player/playback-errors";

interface PlaybackErrorNoticeProps {
  error: unknown;
  onRetry: () => void;
}

/**
 * A refused `POST /api/v1/playback/sessions`, with the copy from
 * `playbackErrorMessage`, a "Try again" when retrying can help, and a link
 * to the AOC Player page when the copy suggests it.
 */
export function PlaybackErrorNotice({ error, onRetry }: PlaybackErrorNoticeProps) {
  const { message, retryable, suggestPairing } = playbackErrorMessage(error);
  return (
    <div role="alert" className="flex flex-col items-start gap-2 rounded-md border border-border bg-muted/20 p-3 text-sm">
      <p className="flex items-center gap-2 text-foreground">
        <AlertCircle aria-hidden="true" className="size-4 shrink-0 text-destructive" />
        {message}
      </p>
      <div className="flex flex-wrap gap-2">
        {suggestPairing ? (
          <Link href="/student/player" className={buttonVariants({ size: "sm" })}>
            Pair the AOC Player
          </Link>
        ) : null}
        {retryable ? (
          <Button type="button" variant="outline" size="sm" onClick={onRetry}>
            Try again
          </Button>
        ) : null}
      </div>
    </div>
  );
}
