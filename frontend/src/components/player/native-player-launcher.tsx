"use client";

import Link from "next/link";
import { useState } from "react";
import { AlertCircle, CheckCircle2, ExternalLink, Loader2, MonitorX } from "lucide-react";
import { Button } from "@/components/ui/button";
import { playbackErrorMessage } from "@/components/player/playback-errors";
import { useStartPlayback, type NativeLaunch } from "@/lib/api/playback";
import { useLaunchStatus } from "@/lib/api/player";
import { openInAocPlayer, useAocPlayerSupported } from "@/lib/player/native-launch";

/**
 * "Open in AOC Player" (VIDEO Phase 8, ADR-025). Used when the institute
 * requires the desktop player. From the Student's click it hands a one-time
 * `aocplayer://play` link to the OS, then polls the backend to learn whether
 * the player claimed it - the browser never trusts anything the player says.
 * A link close to expiry is never reused: a fresh one is requested first.
 */

const REFRESH_BEFORE_EXPIRY_MS = 8_000;

interface PendingLaunch {
  sessionId: string;
  launch: NativeLaunch;
}

interface NativePlayerLauncherProps {
  lessonMediaId: string;
  title: string;
  /** A launch already issued (e.g. by the YouTube player's first request). */
  initial?: PendingLaunch | null;
}

export function NativePlayerLauncher({ lessonMediaId, title, initial = null }: NativePlayerLauncherProps) {
  const start = useStartPlayback();
  const [pending, setPending] = useState<PendingLaunch | null>(initial);
  const [opened, setOpened] = useState(false);
  const [unexpected, setUnexpected] = useState(false);
  const status = useLaunchStatus(opened && pending ? pending.sessionId : null);
  const supported = useAocPlayerSupported();

  const open = async () => {
    setUnexpected(false);
    let target = pending;
    // A link is used once: after the first open, always ask for a fresh one.
    if (!target || opened || Date.parse(target.launch.expiresAt) - Date.now() < REFRESH_BEFORE_EXPIRY_MS) {
      let response;
      try {
        response = await start.mutateAsync(lessonMediaId);
      } catch {
        return; // shown from start.error below
      }
      if (response.channel !== "NATIVE" || !response.launch || !response.sessionId) {
        setUnexpected(true);
        return;
      }
      target = { sessionId: response.sessionId, launch: response.launch };
      setPending(target);
    }
    if (!openInAocPlayer(target.launch.url)) {
      setUnexpected(true);
      return;
    }
    setOpened(true);
  };

  if (supported === false) {
    return <UnsupportedPlatformNotice />;
  }

  if (start.isError) {
    const { message, retryable } = playbackErrorMessage(start.error);
    return (
      <div role="alert" className="flex flex-col items-start gap-2 rounded-md border border-border bg-muted/20 p-3 text-sm">
        <p className="flex items-center gap-2 text-foreground">
          <AlertCircle aria-hidden="true" className="size-4 shrink-0 text-destructive" />
          {message}
        </p>
        {retryable ? (
          <Button type="button" variant="outline" size="sm" onClick={() => start.reset()}>
            Try again
          </Button>
        ) : null}
      </div>
    );
  }

  const state = status.data;
  const claimed = state?.exchanged === true;
  const missed = opened && !claimed && state !== undefined && state.status !== "PENDING_LAUNCH";

  return (
    <div className="flex flex-col gap-3 rounded-md border border-border bg-muted/20 p-4 text-sm">
      <p className="text-foreground">
        Your institute plays lesson videos in the AOC Player app.
        <span className="block text-xs text-muted-foreground">&ldquo;{title}&rdquo; opens in the player on this computer.</span>
      </p>

      <div role="status" aria-live="polite" className="text-sm">
        {claimed ? (
          <p className="flex items-center gap-2 text-foreground">
            <CheckCircle2 aria-hidden="true" className="size-4 text-primary" />
            Playing in the AOC Player.
          </p>
        ) : opened && !missed ? (
          <p className="flex items-center gap-2 text-muted-foreground">
            <Loader2 aria-hidden="true" className="size-4 animate-spin" />
            Opening the AOC Player…
          </p>
        ) : null}
      </div>

      {missed || unexpected ? (
        <div role="alert" className="flex flex-col gap-1">
          <p className="text-foreground">The AOC Player didn&apos;t open.</p>
          <p className="text-xs text-muted-foreground">
            Make sure it&apos;s installed and paired with this browser, then try again.{" "}
            <Link href="/student/player" className="underline">
              Set up the AOC Player
            </Link>
          </p>
        </div>
      ) : null}

      <div>
        <Button
          type="button"
          size="sm"
          onClick={open}
          disabled={start.isPending}
          aria-busy={start.isPending}
        >
          <ExternalLink aria-hidden="true" />
          {start.isPending ? "Preparing…" : opened ? "Open again" : "Open in AOC Player"}
        </Button>
      </div>
    </div>
  );
}

/**
 * Phones, tablets and Macs: the AOC Player is Windows-only for now (mobile apps
 * are planned), and an institute that requires it allows no browser playback.
 */
export function UnsupportedPlatformNotice() {
  return (
    <div role="status" className="flex items-start gap-2 rounded-md border border-border bg-muted/20 p-4 text-sm">
      <MonitorX aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
      <p className="text-foreground">
        This video can only be watched on a Windows computer with the AOC Player.
        <span className="block text-xs text-muted-foreground">
          Your institute plays lesson videos in the AOC Player app, which isn&apos;t available for phones, tablets or
          Macs yet. Open this lesson on a Windows computer.
        </span>
      </p>
    </div>
  );
}
