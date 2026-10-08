import type { Engine, EngineError } from "./types";

const VIDEO_ID = /^[A-Za-z0-9_-]{11}$/;

/**
 * YouTubeStandardEngine (plan §22): the official embed with documented
 * parameters only. Nothing is drawn over the player (YouTube's Required
 * Minimum Functionality); the watermark band sits below it. YouTube mode is
 * LMS-gated, not protected (B3). No YouTube script runs in this window: the
 * player lives in a cross-origin, sandboxed frame that cannot navigate the
 * app.
 */
export function youtubeEngine(stage: HTMLElement, videoId: string, title: string, onError: EngineError): Engine {
  if (!VIDEO_ID.test(videoId)) {
    onError("This video can't be played in the AOC Player.");
    return { kind: "youtube", refresh() {}, destroy() {} };
  }
  const frame = document.createElement("iframe");
  frame.className = "media";
  frame.title = title;
  frame.src = `https://www.youtube-nocookie.com/embed/${videoId}?rel=0&playsinline=1`;
  // No picture-in-picture: a PiP window would escape the player window (and
  // its Phase 11 capture protection).
  frame.allow = "autoplay; encrypted-media; fullscreen";
  frame.allowFullscreen = true;
  frame.referrerPolicy = "strict-origin-when-cross-origin";
  // Popups are allowed so "Watch on YouTube" works; Rust sends those links to
  // the system browser and refuses any other new window.
  frame.setAttribute("sandbox", "allow-scripts allow-same-origin allow-presentation allow-popups");
  stage.replaceChildren(frame);

  return {
    kind: "youtube",
    refresh() {},
    destroy() {
      frame.src = "about:blank";
      frame.remove();
    },
  };
}
