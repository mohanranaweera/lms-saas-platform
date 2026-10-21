import { controlBar, type ControllableMedia } from "./controls";
import type { Engine, EngineError, Media, PlaybackReport, PlaybackState } from "./types";

const VIDEO_ID = /^[A-Za-z0-9_-]{11}$/;
const EMBED_ORIGIN = "https://www.youtube-nocookie.com";
/** How often to ask the embed for updates until it answers. */
const LISTEN_RETRY_MS = 1_000;
const LISTEN_ATTEMPTS = 30;
/** Forward jumps larger than this (seconds) are undone when seeking is off. */
const SEEK_TOLERANCE_SECONDS = 2;
/**
 * Documented embed parameters: YouTube's own controls, keyboard, fullscreen
 * button and annotations off; our control bar drives the player instead.
 */
const EMBED_PARAMS = "rel=0&playsinline=1&enablejsapi=1&controls=0&disablekb=1&fs=0&iv_load_policy=3";

/**
 * YouTube engine (ADR-025 Amendment 3). The embed is shown with its own
 * controls hidden and can't be clicked, focused or open anything: our
 * control bar drives it through the IFrame API messages, Rust refuses every
 * new window, and YouTube lessons only ever play here (the server never gives
 * a browser the video id). Amendment 4: the frame is taller than the visible
 * surface so YouTube's title bar and "Watch on YouTube"/share buttons fall
 * outside it, and a black cover hides the start and end screens. This is
 * deterrence, not protection; the id is still the only secret.
 *
 * Position and state (VIDEO Phase 12) come from the embed's own
 * `enablejsapi` messages, accepted only from this frame's window and the
 * embed origin, and only as numbers/booleans.
 */
export function youtubeEngine(
  stage: HTMLElement,
  media: Extract<Media, { engine: "youtube" }>,
  title: string,
  onError: EngineError,
  onPlayback: PlaybackReport,
): Engine {
  if (!VIDEO_ID.test(media.videoId)) {
    onError("This video can't be played in the AOC Player.");
    return { kind: "youtube", refresh() {}, destroy() {} };
  }
  const origin = encodeURIComponent(window.location.origin);
  const frame = document.createElement("iframe");
  frame.title = title;
  frame.src = `${EMBED_ORIGIN}/embed/${media.videoId}?${EMBED_PARAMS}&origin=${origin}`;
  // No picture-in-picture or embed fullscreen: our own fullscreen covers the
  // player window, which keeps its Phase 11 capture protection.
  frame.allow = "autoplay; encrypted-media";
  frame.referrerPolicy = "strict-origin-when-cross-origin";
  // No allow-popups: nothing in the embed may open a window.
  frame.setAttribute("sandbox", "allow-scripts allow-same-origin allow-presentation");
  // Not clickable or focusable: YouTube's links and keys can't be reached.
  frame.tabIndex = -1;
  frame.style.pointerEvents = "none";

  const player = new YouTubeMedia((func, args) => {
    frame.contentWindow?.postMessage(JSON.stringify({ event: "command", func, args: args ?? [] }), EMBED_ORIGIN);
  });

  // A transparent layer takes the clicks the frame can't (play/pause).
  const shield = document.createElement("div");
  shield.className = "youtube-shield";
  shield.addEventListener("click", () => player.dispatchEvent(new Event("click")));
  shield.addEventListener("contextmenu", (event) => event.preventDefault());

  const surface = document.createElement("div");
  surface.className = "youtube-surface";
  // Covered until the video plays: the unstarted, cued and ended screens show
  // YouTube's title, logo and suggestions.
  surface.toggleAttribute("data-covered", true);
  surface.append(frame, shield);

  const wrapper = document.createElement("div");
  wrapper.className = "file-player";
  wrapper.append(surface, controlBar(player, wrapper, media.allowSeeking));

  let furthest = 0;
  if (!media.allowSeeking) {
    player.addEventListener("timeupdate", () => {
      if (player.currentTime > furthest + SEEK_TOLERANCE_SECONDS) player.currentTime = furthest;
      else furthest = Math.max(furthest, player.currentTime);
    });
  }

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
      // The embed never reported its state: show it rather than a black box.
      if (!heard) surface.removeAttribute("data-covered");
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
    player.update(typeof info === "object" && info !== null ? (info as Record<string, unknown>) : { playerState: info });
    // Playing, paused or buffering show the video; everything else is covered.
    const state = player.playerState;
    surface.toggleAttribute("data-covered", state !== 1 && state !== 2 && state !== 3);
    onPlayback(player.currentTime, toState(player.playerState));
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
  stage.replaceChildren(wrapper);

  return {
    kind: "youtube",
    refresh() {},
    destroy() {
      destroyed = true;
      window.removeEventListener("message", onMessage);
      stopListening();
      frame.src = "about:blank";
      wrapper.remove();
    },
  };
}

/**
 * The embed as a `ControllableMedia`: commands go out as IFrame API
 * messages, and `update` applies what the embed reports, raising the same
 * events a `<video>` would.
 */
class YouTubeMedia extends EventTarget implements ControllableMedia {
  playerState = -1;
  private position = 0;
  private length = Number.NaN;
  private level = 1;
  private silent = false;
  private rate = 1;
  private readonly send: (func: string, args?: unknown[]) => void;

  constructor(send: (func: string, args?: unknown[]) => void) {
    super();
    this.send = send;
  }

  get paused(): boolean {
    // Buffering (3) counts as playing for the play/pause button.
    return this.playerState !== 1 && this.playerState !== 3;
  }

  get currentTime(): number {
    return this.position;
  }

  set currentTime(seconds: number) {
    this.position = Math.max(0, seconds);
    this.send("seekTo", [this.position, true]);
    this.dispatchEvent(new Event("timeupdate"));
  }

  get duration(): number {
    return this.length;
  }

  get muted(): boolean {
    return this.silent;
  }

  set muted(value: boolean) {
    this.silent = value;
    this.send(value ? "mute" : "unMute");
    this.dispatchEvent(new Event("volumechange"));
  }

  get volume(): number {
    return this.level;
  }

  set volume(value: number) {
    this.level = Math.min(1, Math.max(0, value));
    this.send("setVolume", [Math.round(this.level * 100)]);
    this.dispatchEvent(new Event("volumechange"));
  }

  get playbackRate(): number {
    return this.rate;
  }

  set playbackRate(value: number) {
    this.rate = value;
    this.send("setPlaybackRate", [value]);
  }

  play(): Promise<void> {
    this.send("playVideo");
    return Promise.resolve();
  }

  pause(): void {
    this.send("pauseVideo");
  }

  update(info: Record<string, unknown>): void {
    const { currentTime, duration, volume, muted, playerState, playbackRate } = info;
    if (typeof currentTime === "number" && Number.isFinite(currentTime) && currentTime >= 0) {
      this.position = currentTime;
      this.dispatchEvent(new Event("timeupdate"));
    }
    if (typeof duration === "number" && Number.isFinite(duration) && duration > 0 && duration !== this.length) {
      this.length = duration;
      this.dispatchEvent(new Event("durationchange"));
    }
    if (typeof volume === "number" && Number.isFinite(volume)) {
      this.level = Math.min(1, Math.max(0, volume / 100));
      this.dispatchEvent(new Event("volumechange"));
    }
    if (typeof muted === "boolean" && muted !== this.silent) {
      this.silent = muted;
      this.dispatchEvent(new Event("volumechange"));
    }
    if (typeof playbackRate === "number" && Number.isFinite(playbackRate)) this.rate = playbackRate;
    if (typeof playerState === "number" && playerState !== this.playerState) {
      const wasPaused = this.paused;
      this.playerState = playerState;
      if (wasPaused !== this.paused) this.dispatchEvent(new Event(this.paused ? "pause" : "play"));
    }
  }
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
