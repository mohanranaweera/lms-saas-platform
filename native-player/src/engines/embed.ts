import type { Engine, EngineError, Media, PlaybackReport } from "./types";

/**
 * Embed engine (VIDEO Phase 13c, ADR-026 Amendment 1): DynTube's own player
 * inside the bridge page compiled into this build. DynTube serves its AES
 * keys only to its own player, so our HLS engine can't decrypt its streams.
 *
 * The bridge is cross-origin: DynTube's script never runs in this window,
 * which holds the Tauri IPC. Messages are exchanged only with the bridge's
 * origin and window; the embed key is sent once, after the bridge loads.
 * Playback controls are DynTube's; the seek rule is applied by the bridge.
 * DynTube's fullscreen button asks us (the bridge turns its fullscreen calls
 * into a message): we fullscreen the whole stage, so the overlay watermark
 * stays on top, and tell the bridge the result.
 */
export function embedEngine(
  stage: HTMLElement,
  media: Extract<Media, { engine: "embed" }>,
  title: string,
  onError: EngineError,
  onPlayback: PlaybackReport,
): Engine {
  let bridgeOrigin: string;
  try {
    bridgeOrigin = new URL(media.bridgeUrl).origin;
  } catch {
    onError("This video can't be played in the AOC Player.");
    return { kind: "embed", refresh() {}, destroy() {} };
  }

  const frame = document.createElement("iframe");
  frame.title = title;
  frame.src = media.bridgeUrl;
  // DynTube's player needs scripts and its own origin's storage; it may not
  // open windows (no allow-popups), navigate us, or go fullscreen itself.
  frame.setAttribute("sandbox", "allow-scripts allow-same-origin");
  frame.allow = "autoplay; encrypted-media";
  frame.referrerPolicy = "strict-origin";

  const surface = document.createElement("div");
  surface.className = "embed-surface";
  surface.append(frame);

  const wrapper = document.createElement("div");
  wrapper.className = "file-player";
  wrapper.append(surface);

  let sentLoad = false;
  let destroyed = false;

  const onMessage = (event: MessageEvent) => {
    if (event.origin !== bridgeOrigin || event.source !== frame.contentWindow) return;
    const data: unknown = event.data;
    if (!data || typeof data !== "object") return;
    const message = data as Record<string, unknown>;
    if (message.type === "state") {
      const position = message.positionSeconds;
      if (typeof position !== "number" || !Number.isFinite(position) || position < 0) return;
      onPlayback(position, message.state === "PLAYING" ? "PLAYING" : "PAUSED");
    } else if (message.type === "error") {
      onError("The video host's player couldn't load. Try again from the LMS.");
    } else if (message.type === "fullscreen") {
      // The click in the frame gives this window user activation, so this is allowed.
      if (message.on === true && !document.fullscreenElement) {
        void stage.requestFullscreen().catch(() => undefined);
      } else if (message.on !== true && document.fullscreenElement) {
        void document.exitFullscreen().catch(() => undefined);
      }
    }
  };

  const onFullscreenChange = () => {
    frame.contentWindow?.postMessage({ type: "fullscreen-state", on: document.fullscreenElement === stage }, bridgeOrigin);
  };

  window.addEventListener("message", onMessage);
  document.addEventListener("fullscreenchange", onFullscreenChange);
  frame.addEventListener("load", () => {
    if (destroyed || sentLoad) return;
    sentLoad = true;
    frame.contentWindow?.postMessage(
      { type: "load", key: media.key, allowSeeking: media.allowSeeking, startAt: 0 },
      bridgeOrigin,
    );
  });
  stage.replaceChildren(wrapper);

  return {
    kind: "embed",
    refresh() {},
    destroy() {
      destroyed = true;
      window.removeEventListener("message", onMessage);
      document.removeEventListener("fullscreenchange", onFullscreenChange);
      if (document.fullscreenElement === stage) void document.exitFullscreen().catch(() => undefined);
      frame.contentWindow?.postMessage({ type: "stop" }, bridgeOrigin);
      frame.src = "about:blank";
      wrapper.remove();
    },
  };
}
