import Hls from "hls.js";
import { controlBar } from "./controls";
import type { Engine, EngineError, Media, PlaybackReport } from "./types";

/** Forward jumps larger than this (seconds) are undone when seeking is off. */
const SEEK_TOLERANCE_SECONDS = 2;
/** Give up after this many fatal errors in a row with no progress in between. */
const MAX_CONSECUTIVE_ERRORS = 3;
/** A single 4xx could be a blip; two in a row from the key server is a refusal. */
const KEY_REFUSALS_BEFORE_GIVING_UP = 2;
const KEY_REFUSED_MESSAGE =
  "The video host refused to unlock this video for playback. Ask your institute to check the video host's security settings.";

/**
 * HLS engine for managed-streaming video (VIDEO Phase 13, ADR-026: DynTube).
 * Rust has already checked the playlist URL (HTTPS, compiled-in host, the
 * tokenized playlist path); the WebView's CSP only lets media requests reach
 * that host and the DynTube CDN. Each heartbeat brings a freshly signed
 * playlist, used only to reload after a fatal error: a running stream keeps
 * its segments, which carry the CDN's own signatures. Same control bar and
 * seek deterrent as the file engine; no download, picture-in-picture or
 * casting. hls.js runs without a worker so the CSP needs no `worker-src`.
 */
export function hlsEngine(
  stage: HTMLElement,
  media: Extract<Media, { engine: "hls" }>,
  title: string,
  onError: EngineError,
  onPlayback: PlaybackReport,
): Engine {
  let currentUrl = media.url;
  let latestUrl = media.url;
  let furthest = 0;
  let errors = 0;
  let destroyed = false;
  /** A fatal error came before a fresher playlist: reload when the next heartbeat brings one. */
  let reloadWhenFresh = false;
  let hls: Hls | null = null;

  const video = document.createElement("video");
  video.title = title;
  video.preload = "auto";
  video.playsInline = true;
  video.disablePictureInPicture = true;
  video.disableRemotePlayback = true;
  video.controls = false;
  video.setAttribute("controlsList", "nodownload noremoteplayback");
  video.addEventListener("contextmenu", (event) => event.preventDefault());

  const wrapper = document.createElement("div");
  wrapper.className = "file-player";
  wrapper.append(video);

  wrapper.append(controlBar(video, wrapper, media.allowSeeking));
  if (!media.allowSeeking) {
    video.addEventListener("timeupdate", () => {
      if (!video.seeking) furthest = Math.max(furthest, video.currentTime);
    });
    video.addEventListener("seeking", () => {
      if (video.currentTime > furthest + SEEK_TOLERANCE_SECONDS) video.currentTime = furthest;
    });
  }

  video.addEventListener("timeupdate", () => {
    if (video.currentTime > 0) errors = 0;
  });

  // Running only while frames advance: buffering, a pause or the end is PAUSED.
  let stalled = false;
  const report = () => {
    if (destroyed) return;
    const running = !video.paused && !video.ended && !stalled;
    onPlayback(video.currentTime, running ? "PLAYING" : "PAUSED");
  };
  video.addEventListener("waiting", () => {
    stalled = true;
    report();
  });
  video.addEventListener("playing", () => {
    stalled = false;
    report();
  });
  for (const event of ["pause", "ended", "seeked", "timeupdate"]) {
    video.addEventListener(event, report);
  }

  function load(url: string, resumeAt: number, resumePlaying: boolean) {
    hls?.destroy();
    currentUrl = url;
    reloadWhenFresh = false;
    const instance = new Hls({ enableWorker: false });
    hls = instance;
    let lastMediaRecovery = 0;
    let refusedKeys = 0;
    instance.on(Hls.Events.ERROR, (_event, data) => {
      if (destroyed || hls !== instance) return;
      // The host's key server refused the decryption key (4xx). hls.js reports that as
      // non-fatal and keeps retrying; a fresher playlist can't fix it either - it is a
      // security setting on the host's account - so after a repeat, say so and stop.
      const code = data.response?.code ?? 0;
      if (data.details === Hls.ErrorDetails.KEY_LOAD_ERROR && code >= 400 && code < 500) {
        refusedKeys += 1;
        if (data.fatal || refusedKeys >= KEY_REFUSALS_BEFORE_GIVING_UP) {
          instance.destroy();
          hls = null;
          onError(KEY_REFUSED_MESSAGE);
        }
        return;
      }
      if (!data.fatal) return;
      // One media-pipeline recovery per 5 s (hls.js API docs); anything else is a failure.
      if (data.type === Hls.ErrorTypes.MEDIA_ERROR && Date.now() - lastMediaRecovery > 5000) {
        lastMediaRecovery = Date.now();
        instance.recoverMediaError();
        return;
      }
      fail();
    });
    if (resumeAt > 0 || resumePlaying) {
      video.addEventListener(
        "loadedmetadata",
        () => {
          video.currentTime = resumeAt;
          if (resumePlaying) void video.play().catch(() => undefined);
        },
        { once: true },
      );
    }
    instance.loadSource(url);
    instance.attachMedia(video);
  }

  function fail() {
    errors += 1;
    if (errors >= MAX_CONSECUTIVE_ERRORS) {
      onError("The video couldn't be loaded. Press Play in the LMS again.");
      return;
    }
    if (latestUrl === currentUrl) {
      reloadWhenFresh = true;
      return;
    }
    // The playlist token expired or was refused: continue from the same point with the newest one.
    load(latestUrl, video.currentTime, !video.paused);
  }

  if (!Hls.isSupported()) {
    onError("This video can't be played in the AOC Player.");
    stage.replaceChildren();
    return { kind: "hls", refresh() {}, destroy() {} };
  }

  load(currentUrl, 0, false);
  stage.replaceChildren(wrapper);

  return {
    kind: "hls",
    refresh(next) {
      if (next.engine !== "hls") return;
      latestUrl = next.url;
      if (reloadWhenFresh && latestUrl !== currentUrl && !destroyed) {
        load(latestUrl, video.currentTime, false);
      }
    },
    destroy() {
      destroyed = true;
      hls?.destroy();
      hls = null;
      video.pause();
      video.removeAttribute("src");
      video.load();
      wrapper.remove();
    },
  };
}
