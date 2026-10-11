"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { AlertCircle, Loader2, Pause, Play } from "lucide-react";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/states/empty-state";
import {
  useEndPlaybackSession,
  useIssuePlaybackSession,
  useRecordPlaybackProgress,
  type PlaybackSessionResponse,
} from "@/lib/api/videos";
import { isApiClientError } from "@/lib/api/error";
import { attachHls, type HlsFailure } from "./hls-source";

/**
 * Secure video player for a `VIDEO`/`RECORDING` material (Wave 5, plan §5/§6;
 * `.claude/rules/security.md`'s "Video & Session Protection" section). Every
 * limit this component appears to enforce (no-seek, view/duration caps,
 * concurrent-session cap, watermark) is a UX deterrent layered on top of
 * server-side enforcement that runs independently on every
 * `useRecordPlaybackProgress` heartbeat — a direct API call bypassing this
 * component is still rejected by the same backend checks
 * (`VideoPlaybackSessionService`). Never claim otherwise in UI copy.
 *
 * **Design decisions (documented per the task brief's request):**
 * - A playback session is requested on *mount* (`useIssuePlaybackSession`),
 *   not on the user's first "play" click — so the `<video>` element always
 *   has a ready `src` the moment this component renders, matching how every
 *   other player in the ecosystem behaves (no extra click-to-buffer lag).
 *   The plan's own §5 wording ("requests a playback session on play") is
 *   satisfied in substance: no session exists before this component is
 *   mounted, and mounting only happens once the Student/Teacher actually
 *   expands the material's "Open/Preview video" accordion (see
 *   `material-row.tsx`/the Student materials page — both keep this
 *   component unmounted, not just visually hidden, until expanded), so no
 *   session is ever issued for a video that was never opened.
 * - `session` (the current `PlaybackSessionResponse`) is plain component
 *   state, not a ref — it drives render output (the watermark, `controls`/
 *   `allowSeeking`), and this codebase's React Compiler lint config
 *   (`react-hooks/refs`) correctly rejects reading a ref's `.current` during
 *   render. A separate `latestSessionRef` mirrors it (updated in its own
 *   effect, mirroring `exams/[examId]/take/page.tsx`'s `answersRef` idiom)
 *   purely so the unmount cleanup below can reach the *latest* session
 *   without re-subscribing that effect on every session refresh.
 * - When `allowSeeking === false`, the native `<video controls>` attribute
 *   is deliberately omitted entirely (a native scrubber is not suppressible
 *   while `controls` is present) in favor of small custom play/pause
 *   controls, AND a `seeking` listener that snaps the playhead back to the
 *   last known-good forward position whenever the user still manages to
 *   seek the underlying element (e.g. via keyboard focus arrow keys) — both
 *   together, the more robust option the task brief flagged, rather than
 *   relying on either alone.
 * - The on/off signal for the watermark overlay is a non-empty
 *   `watermarkText` on `PlaybackSessionResponse` — there is no separate
 *   `watermarkEnabled` boolean on that DTO (verified against the real
 *   backend file; see `lib/api/videos.ts`'s doc comment).
 * - VIDEO Phase 13 (ADR-026): a session with `mediaFormat: "HLS"` (DynTube)
 *   is played through `attachHls` (hls.js, loaded on demand). The near-expiry
 *   refresh still renews the session and its heartbeat token, but keeps the
 *   running stream: its segments carry the CDN's own signatures, and swapping
 *   the playlist would restart playback. The stream is only reloaded (with a
 *   fresh signed playlist) after a playback error, through the same bounded
 *   retry as a stale file URL.
 */

const HEARTBEAT_INTERVAL_MS = 12_000;
const WATERMARK_REPOSITION_INTERVAL_MS = 18_000;
/** How close to `expiresAt` a transparent session refresh kicks in. */
const REFRESH_BEFORE_EXPIRY_MS = 20_000;
/** Forward-jump tolerance (seconds) before the client-side seek deterrent snaps back, when `allowSeeking === false`. */
const SEEK_TOLERANCE_SECONDS = 2;
/**
 * Max consecutive native `<video>` `error` events (with no genuine
 * progress in between — see `handleTimeUpdate` resetting this counter)
 * before giving up and showing the terminal "error" state, instead of
 * transparently auto-refreshing forever against a persistently broken/
 * unreachable signed URL. Deliberately a plain attempt counter, not a
 * `Date.now()`-based cooldown — a wall-clock debounce interacts badly with
 * a test environment's faked clock (real browser network events still fire
 * at real wall-clock pace even while `Date.now()` is frozen), and a counter
 * is simpler and just as correct for this "stop retrying" purpose.
 */
const MAX_CONSECUTIVE_VIDEO_ERRORS = 2;

interface SecureVideoPlayerProps {
  videoAssetId: string;
  title: string;
  /**
   * VIDEO Phase 8: called when the backend answers `409 NATIVE_PLAYER_REQUIRED`
   * (the institute requires the AOC Player), so the parent can show the launcher.
   */
  onNativePlayerRequired?: () => void;
}

type PlayerState = "loading" | "not-entitled" | "error" | "ready" | "revoked" | "token-invalid" | "key-refused";

/**
 * VIDEO Phase 13: the HLS host's key server refused the decryption key. A new
 * session can't fix that - it is a security setting on the host's account.
 */
const KEY_REFUSED_MESSAGE =
  "The video host refused to unlock this video for playback. Ask your institute to check the video host's security settings.";

/** VIDEO Phase 13c: DynTube plays in its own player, which only the AOC Player embeds so far. */
const EMBED_ONLY_MESSAGE = "This video plays in the AOC Player. Open the lesson there to watch it.";

/** `PLAYBACK_WATCH_LIMIT` (VIDEO Phase 12): the Student's watch time for this video is used up. */
const WATCH_LIMIT_MESSAGE =
  "You've used all the watch time allowed for this video. Ask your institute if you need more time.";

export function SecureVideoPlayer({ videoAssetId, title, onNativePlayerRequired }: SecureVideoPlayerProps) {
  const issueSessionMutation = useIssuePlaybackSession();
  const progressMutation = useRecordPlaybackProgress();
  const endSessionMutation = useEndPlaybackSession();

  const [state, setState] = useState<PlayerState>("loading");
  const [session, setSession] = useState<PlaybackSessionResponse | null>(null);
  const [terminalMessage, setTerminalMessage] = useState<string | null>(null);
  const [seekNotice, setSeekNotice] = useState<string | null>(null);
  const [isPlaying, setIsPlaying] = useState(false);
  const [watermarkPosition, setWatermarkPosition] = useState({ top: 10, left: 10 });

  const videoRef = useRef<HTMLVideoElement>(null);
  const lastGoodPositionRef = useRef(0);
  const watchedSinceHeartbeatRef = useRef(0);
  const lastTimeUpdateRef = useRef(0);
  const consecutiveVideoErrorsRef = useRef(0);
  // VIDEO Phase 13: releases the current hls.js stream, if one is attached. `streamGenerationRef`
  // invalidates an attach still loading when a newer session (or teardown) replaces it.
  const detachHlsRef = useRef<(() => void) | null>(null);
  const streamGenerationRef = useRef(0);
  // The latest error handler, for hls.js's fatal-error callback (set in an effect below).
  const fatalStreamErrorRef = useRef<(failure: HlsFailure) => void>(() => undefined);
  // Mirrors `session` for the unmount-cleanup effect below only — never read
  // during render (see module doc comment).
  const latestSessionRef = useRef<PlaybackSessionResponse | null>(null);

  useEffect(() => {
    latestSessionRef.current = session;
  }, [session]);

  const detachStream = useCallback(() => {
    streamGenerationRef.current += 1;
    detachHlsRef.current?.();
    detachHlsRef.current = null;
  }, []);

  const requestSession = useCallback(
    /**
     * @param keepStream a token refresh: an HLS stream that is already
     * playing keeps going (see the module doc comment)
     */
    async (preserveCurrentTime: boolean, keepStream = false) => {
      const currentTime = preserveCurrentTime ? (videoRef.current?.currentTime ?? 0) : 0;
      const wasPlaying = preserveCurrentTime && isPlaying;
      try {
        const nextSession = await issueSessionMutation.mutateAsync(videoAssetId);
        if (nextSession.mediaFormat === "EMBED") {
          detachStream();
          setTerminalMessage(EMBED_ONLY_MESSAGE);
          setState("revoked");
          return;
        }
        setSession(nextSession);
        setState("ready");
        setTerminalMessage(null);
        // Swap `src` in place and restore the saved position, so a
        // transparent mid-playback refresh (near-expiry, or a stale-URL
        // player error) doesn't visibly interrupt the Student — see the
        // module doc comment's "transparent to the student" requirement.
        requestAnimationFrame(() => {
          const el = videoRef.current;
          if (!el) return;
          const hls = nextSession.mediaFormat === "HLS";
          if (hls && keepStream && detachHlsRef.current) return;
          detachStream();
          if (hls) {
            const generation = streamGenerationRef.current;
            void attachHls(el, nextSession.signedUrl, (failure) => fatalStreamErrorRef.current(failure)).then((detach) => {
              if (streamGenerationRef.current === generation) {
                detachHlsRef.current = detach;
              } else {
                detach();
              }
            });
          } else {
            el.src = nextSession.signedUrl;
          }
          if (preserveCurrentTime) {
            const onLoaded = () => {
              el.currentTime = currentTime;
              lastGoodPositionRef.current = currentTime;
              if (wasPlaying) void el.play().catch(() => undefined);
              el.removeEventListener("loadedmetadata", onLoaded);
            };
            el.addEventListener("loadedmetadata", onLoaded);
          }
        });
      } catch (error) {
        if (isApiClientError(error) && error.code === "NATIVE_PLAYER_REQUIRED" && onNativePlayerRequired) {
          onNativePlayerRequired();
        } else if (isApiClientError(error) && error.code === "PLAYBACK_WATCH_LIMIT") {
          // Covers a mid-playback refresh too: the session is over, so drop the media.
          detachStream();
          const el = videoRef.current;
          if (el) {
            el.pause();
            el.removeAttribute("src");
            el.load();
          }
          setIsPlaying(false);
          setTerminalMessage(WATCH_LIMIT_MESSAGE);
          setState("revoked");
        } else if (isApiClientError(error) && error.status === 404) {
          setState("not-entitled");
        } else {
          setState("error");
        }
      }
    },
    [issueSessionMutation, videoAssetId, isPlaying, onNativePlayerRequired, detachStream]
  );

  // Initial session on mount — the canonical "fetch on mount" effect
  // (https://react.dev/learn/synchronizing-with-effects#fetching-data):
  // this is a genuine synchronization with an external system (issuing a
  // network-backed playback grant), not derived UI state, so an effect is
  // the right tool even though the eventual `setSession`/`setState` calls
  // inside `requestSession` happen after an `await` — the lint rule below
  // can't see across that `await` boundary and flags it regardless.
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- fetch-on-mount side effect; state updates happen asynchronously after `requestSession`'s internal `await`, not synchronously in this effect body.
    void requestSession(false);
    // Re-runs only when the caller swaps to a different video (or on the
    // manual "Try again" retry below, which calls `requestSession`
    // directly rather than re-running this effect) — `requestSession` is
    // stable via `useCallback` and must not re-trigger a brand-new session
    // on every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [videoAssetId]);

  // Best-effort session end on unmount — never blocks navigation, never
  // surfaces a failure to the user (per `useEndPlaybackSession`'s own doc
  // comment). Reads `latestSessionRef` (not `session`) so a session issued
  // after this effect first mounted is still ended correctly.
  useEffect(() => {
    return () => {
      streamGenerationRef.current += 1;
      detachHlsRef.current?.();
      detachHlsRef.current = null;
      const activeSession = latestSessionRef.current;
      if (activeSession?.watchSessionId) {
        endSessionMutation.mutate(activeSession.watchSessionId, {
          onError: (error) => {
            console.warn("Failed to end playback session (best-effort)", error);
          },
        });
      }
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Watermark repositioning.
  useEffect(() => {
    if (!session?.watermarkText) return;
    const interval = setInterval(() => {
      setWatermarkPosition({
        top: 5 + Math.random() * 80,
        left: 5 + Math.random() * 70,
      });
    }, WATERMARK_REPOSITION_INTERVAL_MS);
    return () => clearInterval(interval);
  }, [session?.watermarkText]);

  // Transparent refresh shortly before the signed URL/token expires.
  useEffect(() => {
    if (state !== "ready" || !session) return;
    const msUntilRefresh = new Date(session.expiresAt).getTime() - Date.now() - REFRESH_BEFORE_EXPIRY_MS;
    if (msUntilRefresh <= 0) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- same justification as the mount effect above: a legitimate fetch/refresh side effect whose state updates happen after `requestSession`'s internal `await`.
      void requestSession(true);
      return;
    }
    const timeout = setTimeout(() => void requestSession(true, true), msUntilRefresh);
    return () => clearTimeout(timeout);
  }, [state, session, requestSession]);

  const endTerminal = useCallback((message: string, next: PlayerState) => {
    detachStream();
    const el = videoRef.current;
    if (el) {
      el.pause();
      el.removeAttribute("src");
      el.load();
    }
    setIsPlaying(false);
    setState(next);
    setTerminalMessage(message);
  }, [detachStream]);

  const sendHeartbeat = useCallback(async () => {
    const el = videoRef.current;
    if (!session?.watchSessionId || !session.playbackToken || !el) return;

    const positionSeconds = Math.floor(el.currentTime);
    const watchedDeltaSeconds = Math.max(0, Math.floor(watchedSinceHeartbeatRef.current));
    watchedSinceHeartbeatRef.current = 0;

    try {
      await progressMutation.mutateAsync({
        watchSessionId: session.watchSessionId,
        body: { playbackToken: session.playbackToken, positionSeconds, watchedDeltaSeconds },
      });
      lastGoodPositionRef.current = positionSeconds;
    } catch (error) {
      if (isApiClientError(error)) {
        if (error.code === "POLICY_VIOLATION") {
          endTerminal("This viewing session has ended because a limit was reached.", "revoked");
          return;
        }
        if (error.code === "PLAYBACK_WATCH_LIMIT") {
          endTerminal(WATCH_LIMIT_MESSAGE, "revoked");
          return;
        }
        if (error.code === "SEEK_NOT_ALLOWED") {
          if (el) el.currentTime = lastGoodPositionRef.current;
          setSeekNotice("Seeking isn't allowed for this video.");
          setTimeout(() => setSeekNotice(null), 4000);
          return;
        }
        if (error.code === "PLAYBACK_TOKEN_INVALID") {
          endTerminal("Your session is no longer valid. Reload to keep watching.", "token-invalid");
          return;
        }
      }
      // Any other heartbeat failure (network blip, 5xx) is deliberately
      // swallowed here — a heartbeat is best-effort telemetry, not the
      // enforcement point itself (the next heartbeat retries), so a
      // transient failure must not interrupt playback.
    }
  }, [session, progressMutation, endTerminal]);

  // Heartbeat loop while playing.
  useEffect(() => {
    if (state !== "ready" || !isPlaying) return;
    const interval = setInterval(() => void sendHeartbeat(), HEARTBEAT_INTERVAL_MS);
    return () => clearInterval(interval);
  }, [state, isPlaying, sendHeartbeat]);

  const handleTimeUpdate = () => {
    const el = videoRef.current;
    if (!el) return;
    // A genuine `timeupdate` implies media is actually decoding — clears
    // the error streak `handleVideoError` tracks below.
    consecutiveVideoErrorsRef.current = 0;
    const delta = el.currentTime - lastTimeUpdateRef.current;
    // Only accumulate normal forward progression as "watched" — a jump
    // (seek, or a session-swap restoring `currentTime`) never counts toward
    // `watchedDeltaSeconds`.
    if (delta > 0 && delta < 2) {
      watchedSinceHeartbeatRef.current += delta;
      lastGoodPositionRef.current = el.currentTime;
    }
    lastTimeUpdateRef.current = el.currentTime;
  };

  const allowSeeking = session?.allowSeeking ?? true;

  const handleSeeking = () => {
    const el = videoRef.current;
    if (!el || allowSeeking) return;
    if (Math.abs(el.currentTime - lastGoodPositionRef.current) > SEEK_TOLERANCE_SECONDS) {
      el.currentTime = lastGoodPositionRef.current;
      setSeekNotice("Seeking isn't allowed for this video.");
      setTimeout(() => setSeekNotice(null), 4000);
    }
  };

  const handleEnded = () => {
    setIsPlaying(false);
    if (session?.watchSessionId) {
      endSessionMutation.mutate(session.watchSessionId, { onError: () => undefined });
    }
  };

  const handleVideoError = () => {
    // A stale/expired signed URL surfaces as a native `<video>` error —
    // transparently re-issue a session and swap `src` in place, per the
    // module doc comment's "transparent to the student" requirement. Bounded
    // by `MAX_CONSECUTIVE_VIDEO_ERRORS` so a persistently broken/unreachable
    // URL fails into the terminal "error" state instead of retrying forever.
    if (state !== "ready") return;
    consecutiveVideoErrorsRef.current += 1;
    if (consecutiveVideoErrorsRef.current > MAX_CONSECUTIVE_VIDEO_ERRORS) {
      setState("error");
      return;
    }
    void requestSession(true);
  };

  // hls.js reports fatal stream errors outside React; route them to the current handler.
  useEffect(() => {
    fatalStreamErrorRef.current = (failure) => {
      if (failure === "KEY_REFUSED") {
        detachStream();
        setTerminalMessage(KEY_REFUSED_MESSAGE);
        setState("key-refused");
        return;
      }
      handleVideoError();
    };
  });

  const handleRetry = () => {
    setState("loading");
    void requestSession(false);
  };

  if (state === "loading") {
    return (
      <div
        role="status"
        aria-busy="true"
        aria-live="polite"
        className="flex flex-col items-center justify-center gap-2 rounded-md border border-border bg-muted/20 px-6 py-10 text-muted-foreground"
      >
        <Loader2 className="size-6 animate-spin" aria-hidden="true" />
        <span className="text-sm">Preparing &ldquo;{title}&rdquo;…</span>
      </div>
    );
  }

  if (state === "not-entitled") {
    return (
      <EmptyState
        title="This video isn't available"
        description="It may have been removed, or you may not have access to it."
      />
    );
  }

  if (state === "error") {
    return (
      <div role="alert" className="flex flex-col items-center gap-3 rounded-md border border-destructive/30 bg-destructive/5 px-6 py-10 text-center">
        <AlertCircle className="size-6 text-destructive" aria-hidden="true" />
        <p className="text-sm text-destructive">Couldn&apos;t load this video. Please try again.</p>
        <Button type="button" variant="outline" size="sm" onClick={handleRetry}>
          Try again
        </Button>
      </div>
    );
  }

  if (state === "revoked" || state === "token-invalid" || state === "key-refused") {
    return (
      <div role="alert" className="flex flex-col items-center gap-3 rounded-md border border-destructive/30 bg-destructive/5 px-6 py-10 text-center">
        <AlertCircle className="size-6 text-destructive" aria-hidden="true" />
        <p className="text-sm text-destructive">{terminalMessage}</p>
        {state === "token-invalid" || state === "key-refused" ? (
          <Button type="button" variant="outline" size="sm" onClick={handleRetry}>
            {state === "key-refused" ? "Try again" : "Reload"}
          </Button>
        ) : null}
      </div>
    );
  }

  return (
    <div className="relative overflow-hidden rounded-md border border-border bg-black">
      <video
        ref={videoRef}
        className="w-full"
        // `controls` is intentionally omitted entirely when seeking is
        // disallowed — see the module doc comment's custom-controls design
        // decision.
        controls={allowSeeking}
        disablePictureInPicture
        playsInline
        onPlay={() => setIsPlaying(true)}
        onPause={() => setIsPlaying(false)}
        onTimeUpdate={handleTimeUpdate}
        onSeeking={handleSeeking}
        onEnded={handleEnded}
        onError={handleVideoError}
        aria-label={title}
        {...({ controlsList: "nodownload" } as Record<string, string>)}
      >
        <track kind="captions" />
      </video>

      {session?.watermarkText ? (
        <div
          aria-hidden="true"
          className="pointer-events-none absolute max-w-[36%] whitespace-pre-line select-none text-xs font-semibold leading-snug text-white/60"
          style={{ top: `${watermarkPosition.top}%`, left: `${watermarkPosition.left}%` }}
        >
          {session.watermarkText}
        </div>
      ) : null}

      {!allowSeeking ? (
        <div className="flex items-center gap-2 bg-black/80 px-3 py-2">
          <Button
            type="button"
            variant="outline"
            size="icon-xs"
            aria-label={isPlaying ? "Pause" : "Play"}
            onClick={() => {
              const el = videoRef.current;
              if (!el) return;
              if (isPlaying) {
                el.pause();
              } else {
                void el.play().catch(() => undefined);
              }
            }}
          >
            {isPlaying ? <Pause aria-hidden="true" /> : <Play aria-hidden="true" />}
          </Button>
          <span className="text-xs text-white/80">Seeking is disabled for this video.</span>
        </div>
      ) : null}

      {seekNotice ? (
        <p role="status" aria-live="polite" className="bg-black/80 px-3 py-1 text-xs text-amber-300">
          {seekNotice}
        </p>
      ) : null}
    </div>
  );
}
