import type { Engine, EngineError, PlaybackReport, PlaybackState } from "./types";

const VIDEO_ID = /^[A-Za-z0-9_-]{11}$/;
const EMBED_ORIGIN = "https://www.youtube-nocookie.com";
/** How often to ask the embed for updates until it answers. */
const LISTEN_RETRY_MS = 1_000;
const LISTEN_ATTEMPTS = 30;

/**
 * YouTubeStandardEngine (plan §22): the official embed with documented
 * parameters only. Nothing is drawn over the player (YouTube's Required
 * Minimum Functionality); the watermark band sits below it. YouTube mode is
 * LMS-gated, not protected (B3). No YouTube script runs in this window: the
 * player lives in a cross-origin, sandboxed frame that cannot navigate the
 * app.
 *
 * Position and state (VIDEO Phase 12) come from the embed's own
 * `enablejsapi` messages - the channel the IFrame API uses - accepted only
 * from this frame's window and the embed origin, and only as numbers.
 */
export function youtubeEngine(
  stage: HTMLElement,
  videoId: string,
  title: string,
  onError: EngineError,
  onPlayback: PlaybackReport,
): Engine {
  if (!VIDEO_ID.test(videoId)) {
    onError("This video can't be played in the AOC Player.");
    return { kind: "youtube", refresh() {}, destroy() {} };
  }
  const origin = encodeURIComponent(window.location.origin);
  const frame = document.createElement("iframe");
  frame.className = "media";
  frame.title = title;
  frame.src = `${EMBED_ORIGIN}/embed/${videoId}?rel=0&playsinline=1&enablejsapi=1&origin=${origin}`;
  // No picture-in-picture: a PiP window would escape the player window (and
  // its Phase 11 capture protection).
  frame.allow = "autoplay; encrypted-media; fullscreen";
  frame.allowFullscreen = true;
  frame.referrerPolicy = "strict-origin-when-cross-origin";
  // Popups are allowed so "Watch on YouTube" works; Rust sends those links to
  // the system browser and refuses any other new window.
  frame.setAttribute("sandbox", "allow-scripts allow-same-origin allow-presentation allow-popups");

  let position = 0;
  let state: PlaybackState = "IDLE";
  let heard = false;
  let attempts = 0;
  let listenTimer: number | null = null;
  let destroyed = false;

  const stopListening = () => {
    if (listenTimer !== null) window.clearInterval(listenTimer);
    listenTimer = null;
  };

  const listen = () => {
    if (heard || attempts >= LISTEN_ATTEMPTS) {
      stopListening();
      return;
    }
    attempts += 1;
    frame.contentWindow?.postMessage(JSON.stringify({ event: "listening", id: 1, channel: "widget" }), EMBED_ORIGIN);
  };

  const onMessage = (event: MessageEvent) => {
    if (event.origin !== EMBED_ORIGIN || event.source !== frame.contentWindow || typeof event.data !== "string") {
      return;
    }
    let message: unknown;
    try {
      message = JSON.parse(event.data);
    } catch {
      return;
    }
    if (!message || typeof message !== "object") return;
    const { event: name, info } = message as { event?: unknown; info?: unknown };
    if (name !== "infoDelivery" && name !== "initialDelivery" && name !== "onStateChange") return;
    heard = true;
    // onStateChange carries the bare state number; the others an info object.
    const update: Record<string, unknown> =
      typeof info === "object" && info !== null ? (info as Record<string, unknown>) : { playerState: info };
    const time = update.currentTime;
    if (typeof time === "number" && Number.isFinite(time) && time >= 0) position = time;
    if (typeof update.playerState === "number") state = toState(update.playerState);
    onPlayback(position, state);
  };

  window.addEventListener("message", onMessage);
  // The embed only answers after it has loaded, so keep asking for a while.
  frame.addEventListener("load", () => {
    if (destroyed) return;
    heard = false;
    attempts = 0;
    stopListening();
    listen();
    listenTimer = window.setInterval(listen, LISTEN_RETRY_MS);
  });
  stage.replaceChildren(frame);

  return {
    kind: "youtube",
    refresh() {},
    destroy() {
      destroyed = true;
      window.removeEventListener("message", onMessage);
      stopListening();
      frame.src = "about:blank";
      frame.remove();
    },
  };
}

/** YouTube player states: 1 playing; 0 ended, 2 paused, 3 buffering; -1 unstarted, 5 cued. */
function toState(playerState: number): PlaybackState {
  switch (playerState) {
    case 1:
      return "PLAYING";
    case 0:
    case 2:
    case 3:
      return "PAUSED";
    default:
      return "IDLE";
  }
}
