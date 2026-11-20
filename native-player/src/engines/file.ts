import { controlBar } from "./controls";
import type { Engine, EngineError, Media, PlaybackReport } from "./types";

/** Forward jumps larger than this (seconds) are undone when seeking is off. */
const SEEK_TOLERANCE_SECONDS = 2;
/** Give up after this many failures in a row with no progress in between. */
const MAX_CONSECUTIVE_ERRORS = 3;
/** Pause before retrying a failed load, so a heartbeat can refresh the upstream URL. */
const RETRY_DELAY_MS = 1500;

/**
 * File engine for platform-hosted (INTERNAL) video behind a short-lived
 * signed URL. Each heartbeat brings a fresh URL; it is only swapped in when
 * the current one fails (it has expired), so playback does not stutter every
 * heartbeat. Own control bar (`controls.ts`); no download,
 * picture-in-picture or casting. When the institute disallows seeking there
 * is no scrubber and forward jumps are undone - a deterrent only; the
 * server's checks are what count.
 */
export function fileEngine(
  stage: HTMLElement,
  media: Extract<Media, { engine: "file" }>,
  title: string,
  onError: EngineError,
  onPlayback: PlaybackReport,
): Engine {
  let currentUrl = media.url;
  let latestUrl = media.url;
  let furthest = 0;
  let errors = 0;
  let destroyed = false;

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
  video.addEventListener("error", () => {
    if (destroyed) return;
    errors += 1;
    if (errors >= MAX_CONSECUTIVE_ERRORS) {
      onError("The video couldn't be loaded. Press Play in the LMS again.");
      return;
    }
    // Continue from the same point. Proxied files keep one opaque URL while Rust
    // swaps the signed upstream on each heartbeat, so retrying that same URL is
    // what picks the fresh one up; never fail silently on a black screen.
    window.setTimeout(() => {
      if (!destroyed) resumeWith(latestUrl);
    }, RETRY_DELAY_MS);
  });

  function resumeWith(url: string) {
    const position = video.currentTime;
    const playing = !video.paused;
    currentUrl = url;
    video.src = url;
    video.addEventListener(
      "loadedmetadata",
      () => {
        video.currentTime = position;
        if (playing) void video.play().catch(() => undefined);
      },
      { once: true },
    );
  }

  video.src = currentUrl;
  stage.replaceChildren(wrapper);

  return {
    kind: "file",
    refresh(next) {
      if (next.engine === "file") latestUrl = next.url;
    },
    destroy() {
      destroyed = true;
      video.pause();
      video.removeAttribute("src");
      video.load();
      wrapper.remove();
    },
  };
}
