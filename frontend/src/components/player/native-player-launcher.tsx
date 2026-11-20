"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { CheckCircle2, Download, ExternalLink, Loader2, MonitorX, Smartphone } from "lucide-react";
import { Button, buttonVariants } from "@/components/ui/button";
import { PlaybackErrorNotice } from "@/components/player/playback-error-notice";
import { env } from "@/lib/env";
import { useStartPlayback, type NativeLaunch } from "@/lib/api/playback";
import { useLaunchStatus } from "@/lib/api/player";
import { openInAocPlayer, useAocPlayerSupported, useMobilePlatform } from "@/lib/player/native-launch";

import { useT } from "@/i18n/i18n-provider";
/**
 * "Open in AOC Player" (VIDEO Phase 8, ADR-025). Used when the institute
 * requires the desktop player. From the Student's click it hands a one-time
 * `aocplayer://play` link to the OS, then polls the backend to learn whether
 * the player claimed it - the browser never trusts anything the player says.
 * A link close to expiry is never reused: a fresh one is requested first.
 *
 * There is no separate pairing step (ADR-025 Amendment 2): when this computer
 * has no player yet, the backend puts a pairing code in the same link and the
 * player pairs itself before playing. A browser cannot tell whether the
 * player is installed, so when nothing claims the link after a short wait the
 * launcher offers the installer instead.
 */

const REFRESH_BEFORE_EXPIRY_MS = 8_000;
/** How long to wait for the player to claim a link before offering the installer. */
export const INSTALL_HINT_AFTER_MS = 12_000;
/** Remembered (UX only) once this browser has seen the player take a link. */
const SEEN_KEY = "aoc-player:seen";

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

function readSeen(): boolean {
  try {
    return window.localStorage.getItem(SEEN_KEY) === "1";
  } catch {
    return false;
  }
}

function rememberSeen(): void {
  try {
    window.localStorage.setItem(SEEN_KEY, "1");
  } catch {
    // Storage may be unavailable (private mode); it only changes the wording.
  }
}

export function NativePlayerLauncher({ lessonMediaId, title, initial = null }: NativePlayerLauncherProps) {
  const t = useT();
  const start = useStartPlayback();
  const [pending, setPending] = useState<PendingLaunch | null>(initial);
  const [opened, setOpened] = useState(false);
  const [openedAt, setOpenedAt] = useState<number | null>(null);
  const [hintDue, setHintDue] = useState(false);
  const [unexpected, setUnexpected] = useState(false);
  const status = useLaunchStatus(opened && pending ? pending.sessionId : null);
  const supported = useAocPlayerSupported();

  const state = status.data;
  const claimed = state?.exchanged === true;
  const missed = opened && !claimed && state !== undefined && state.status !== "PENDING_LAUNCH";

  useEffect(() => {
    if (claimed) rememberSeen();
  }, [claimed]);

  // Offer the installer when the player hasn't taken the link after a while.
  useEffect(() => {
    if (openedAt === null || claimed) return;
    const timer = window.setTimeout(() => setHintDue(true), INSTALL_HINT_AFTER_MS);
    return () => window.clearTimeout(timer);
  }, [openedAt, claimed]);

  const open = async () => {
    setUnexpected(false);
    setHintDue(false);
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
    setOpenedAt(Date.now());
  };

  if (supported === false) {
    return <UnsupportedPlatformNotice />;
  }

  if (start.isError) {
    return <PlaybackErrorNotice error={start.error} onRetry={() => start.reset()} />;
  }

  const notResponding = !claimed && (missed || unexpected || (opened && hintDue));
  const offerInstall = notResponding && !readSeen();

  return (
    <div className="flex flex-col gap-3 rounded-md border border-border bg-muted/20 p-4 text-sm">
      <p className="text-foreground">
        {t("player.nativePlayerLauncher.yourInstitutePlaysLesson")}
        <span className="block text-xs text-muted-foreground">&ldquo;{title}&rdquo; opens in the player on this computer.</span>
      </p>

      <div role="status" aria-live="polite" className="text-sm">
        {claimed ? (
          <p className="flex items-center gap-2 text-foreground">
            <CheckCircle2 aria-hidden="true" className="size-4 text-primary" />
            {t("player.nativePlayerLauncher.playingInTheAoc")}
          </p>
        ) : opened && !notResponding ? (
          <p className="flex items-center gap-2 text-muted-foreground">
            <Loader2 aria-hidden="true" className="size-4 animate-spin" />
            {t("player.nativePlayerLauncher.openingTheAocPlayer")}
          </p>
        ) : null}
      </div>

      {offerInstall ? <InstallPrompt /> : null}
      {notResponding && !offerInstall ? (
        <div role="alert" className="flex flex-col gap-1">
          <p className="text-foreground">The AOC Player didn&apos;t open.</p>
          <p className="text-xs text-muted-foreground">
            Check that it&apos;s still installed, then open the video again.{" "}
            <Link href="/student/player" className="underline">
              {t("player.nativePlayerLauncher.aocPlayerHelp")}
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
          {start.isPending
            ? t("player.nativePlayerLauncher.preparing")
            : offerInstall
              ? t("player.nativePlayerLauncher.iveInstalledItPlay")
              : opened
                ? t("player.nativePlayerLauncher.openAgain")
                : t("player.nativePlayerLauncher.openInAocPlayer")}
        </Button>
      </div>
    </div>
  );
}

/** The player didn't take the link and this browser has never seen it: offer the installer. */
function InstallPrompt() {
  const t = useT();
  const downloadUrl = env.NEXT_PUBLIC_AOC_PLAYER_DOWNLOAD_URL;
  return (
    <div role="alert" className="flex flex-col items-start gap-2 rounded-md border border-border bg-background p-3">
      <p className="font-medium text-foreground">{t("player.nativePlayerLauncher.installTheAocPlayer")}</p>
      <p className="text-xs text-muted-foreground">
        It looks like the AOC Player isn&apos;t installed on this computer. Install it, then press &ldquo;I&apos;ve
        installed it - play now&rdquo;. It sets itself up the first time; there is nothing else to do.
      </p>
      {downloadUrl ? (
        <a href={downloadUrl} rel="noopener noreferrer" className={buttonVariants({ variant: "outline", size: "sm" })}>
          <Download aria-hidden="true" />
          {t("player.nativePlayerLauncher.downloadForWindows")}
        </a>
      ) : (
        <p className="text-xs text-muted-foreground">
          The download isn&apos;t available yet - your institute will let you know.{" "}
          <Link href="/student/player" className="underline">
            {t("player.nativePlayerLauncher.aocPlayerHelp")}
          </Link>
        </p>
      )}
    </div>
  );
}

/**
 * Phones, tablets and Macs: the AOC Player is Windows-only, and an institute
 * that requires it allows no browser playback. On Android/iOS, once the AOC
 * mobile app is published (store links configured), point to the app instead
 * (ADR-025 Amendment 2). UX only - the backend never trusts the device type.
 */
export function UnsupportedPlatformNotice() {
  const t = useT();
  const mobile = useMobilePlatform();
  const storeUrl =
    mobile === "android"
      ? env.NEXT_PUBLIC_AOC_MOBILE_APP_ANDROID_URL
      : mobile === "ios"
        ? env.NEXT_PUBLIC_AOC_MOBILE_APP_IOS_URL
        : undefined;

  if (storeUrl) {
    return (
      <div role="status" className="flex items-start gap-2 rounded-md border border-border bg-muted/20 p-4 text-sm">
        <Smartphone aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
        <div className="flex flex-col items-start gap-2">
          <p className="text-foreground">
            {t("player.nativePlayerLauncher.watchThisVideoIn")}
            <span className="block text-xs text-muted-foreground">
              {t("player.nativePlayerLauncher.yourInstitutePlaysLesson2")}
            </span>
          </p>
          <a href={storeUrl} rel="noopener noreferrer" className={buttonVariants({ size: "sm" })}>
            {mobile === "ios" ? t("player.nativePlayerLauncher.getItOnThe") : t("player.nativePlayerLauncher.getItOnGoogle")}
          </a>
        </div>
      </div>
    );
  }

  return (
    <div role="status" className="flex items-start gap-2 rounded-md border border-border bg-muted/20 p-4 text-sm">
      <MonitorX aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
      <p className="text-foreground">
        {t("player.nativePlayerLauncher.thisVideoCanOnly")}
        <span className="block text-xs text-muted-foreground">
          Your institute plays lesson videos in the AOC Player app, which isn&apos;t available for phones, tablets or
          Macs yet. Open this lesson on a Windows computer.
        </span>
      </p>
    </div>
  );
}
