/**
 * VIDEO Phase 13c (ADR-026 Amendment 1): the DynTube bridge page.
 *
 * DynTube serves its AES keys only to its own player on whitelisted domains,
 * so DynTube video plays in DynTube's JS embed, loaded here on a dedicated
 * embed origin and framed by the LMS web player or the AOC Player.
 *
 * - Served only when the request's Host is the embed host
 *   (`EMBED_HOST`, dev default `embed.lms.test:3000`): never on a tenant origin,
 *   so DynTube's script never runs next to an LMS session.
 * - `frame-ancestors` limits who may frame it (`EMBED_PARENT_ORIGINS`).
 * - It plays nothing until an allowed parent sends `load` with the embed key,
 *   and it reports playback only to that parent.
 *
 * Protocol (postMessage, origins checked on both sides):
 *   parent -> bridge: { type: "load", key, allowSeeking, startAt } | { type: "stop" }
 *                   | { type: "fullscreen-state", on }
 *   bridge -> parent: { type: "state", positionSeconds, durationSeconds, state: "PLAYING" | "PAUSED" }
 *                   | { type: "error", code: "LOAD_FAILED" } | { type: "fullscreen", on }
 *
 * Fullscreen: DynTube's fullscreen calls are turned into a request to the
 * parent, which fullscreens its whole stage, so its watermark stays on top
 * (a fullscreen frame would leave it behind). The parent reports the result
 * back and the bridge mirrors it to DynTube.
 */

const DEV_EMBED_HOST = "embed.lms.test:3000";
/** LMS sites and the AOC Player (`tauri.localhost`; `127.0.0.1:1430` under `tauri dev`). */
const DEV_PARENT_ORIGINS =
  "http://*.lms.test:3000 http://localhost:3000 http://tauri.localhost http://127.0.0.1:1430";

const DYNTUBE_SCRIPT = "https://embed.dyntube.com/v1.0/dyntube.js";

function embedHost(): string | null {
  const configured = process.env.EMBED_HOST?.trim();
  const raw = configured || (process.env.NODE_ENV === "production" ? "" : DEV_EMBED_HOST);
  if (!raw || raw.length > 256 || /[\s/@?#]/.test(raw)) return null;
  try {
    const url = new URL(`https://${raw}`);
    if (url.pathname !== "/" || url.search || url.hash || url.username || url.password || !url.hostname) {
      return null;
    }
    if (url.port && (!/^\d{1,5}$/.test(url.port) || Number(url.port) > 65535)) return null;
    return `${url.hostname.toLowerCase()}${url.port ? `:${url.port}` : ""}`;
  } catch {
    return null;
  }
}

function parentOrigins(): string[] {
  const configured = process.env.EMBED_PARENT_ORIGINS?.trim();
  const raw = configured || (process.env.NODE_ENV === "production" ? "" : DEV_PARENT_ORIGINS);
  // Production values must be exact origins. Development retains only the
  // explicit local subdomain wildcard needed by the local tenant fixtures.
  return raw
    .split(/[\s,]+/)
    .map((origin) => normalizeOrigin(origin))
    .filter((origin): origin is string => origin !== null);
}

function normalizeOrigin(raw: string): string | null {
  if (!raw || raw.length > 256 || raw.includes("@") || raw.includes("#") || raw.includes("?")) return null;
  const wildcard = raw.startsWith("http://*.") || raw.startsWith("https://*.");
  const candidate = wildcard ? raw.replace("*.", "wildcard.") : raw;
  try {
    const url = new URL(candidate);
    if (!url.hostname || url.username || url.password || url.pathname !== "/" || url.search || url.hash) return null;
    if (url.port && (!/^\d{1,5}$/.test(url.port) || Number(url.port) > 65535)) return null;
    if (wildcard && process.env.NODE_ENV === "production") return null;
    const hostname = wildcard ? url.hostname.replace(/^wildcard\./, "") : url.hostname;
    return wildcard ? `${url.protocol}//*.${hostname}${url.port ? `:${url.port}` : ""}` : url.origin;
  } catch {
    return null;
  }
}

function nonce(): string {
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  return btoa(String.fromCharCode(...bytes));
}

export function GET(request: Request): Response {
  const host = embedHost();
  const origins = parentOrigins();
  if (!host || request.headers.get("host")?.toLowerCase() !== host || origins.length === 0) {
    return new Response("Not found", { status: 404, headers: { "Content-Type": "text/plain" } });
  }

  const scriptNonce = nonce();
  const csp = [
    "default-src 'none'",
    // DynTube's player evaluates strings as code (live run 2026-10-11). Allowed only
    // here: this origin holds no LMS session, cookies or storage.
    `script-src 'nonce-${scriptNonce}' 'unsafe-eval' https://embed.dyntube.com https://*.dyntube.com https://*.dyntube.net`,
    "style-src 'unsafe-inline' https://*.dyntube.com https://*.dyntube.net",
    "img-src data: blob: https://*.dyntube.com https://*.dyntube.net",
    "font-src data: https://*.dyntube.com https://*.dyntube.net",
    "connect-src https://*.dyntube.com https://*.dyntube.net",
    "media-src blob: https://*.dyntube.com https://*.dyntube.net",
    "worker-src blob:",
    "frame-src https://*.dyntube.com",
    "base-uri 'none'",
    "form-action 'none'",
    `frame-ancestors ${origins.join(" ")}`,
  ].join("; ");

  return new Response(page(scriptNonce, origins), {
    status: 200,
    headers: {
      "Content-Type": "text/html; charset=utf-8",
      "Content-Security-Policy": csp,
      "Cache-Control": "no-store",
      "Referrer-Policy": "strict-origin",
      "X-Content-Type-Options": "nosniff",
    },
  });
}

function page(scriptNonce: string, origins: string[]): string {
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex">
<title>Lesson video</title>
<style>
  html, body { margin: 0; height: 100%; background: #000; overflow: hidden; }
  #player { position: absolute; inset: 0; }
  #player > * { width: 100% !important; height: 100% !important; }
  #player video { object-fit: contain !important; background: #000; }
</style>
</head>
<body>
<div id="player"></div>
<script nonce="${scriptNonce}">
(() => {
  "use strict";
  const ALLOWED = ${JSON.stringify(origins)};
  const SCRIPT = ${JSON.stringify(DYNTUBE_SCRIPT)};
  const KEY = /^[A-Za-z0-9_-]{6,64}$/;
  const SEEK_TOLERANCE_SECONDS = 2;
  const REPORT_EVERY_MS = 2000;

  const allowed = (origin) => {
    let candidate;
    try { candidate = new URL(origin); } catch { return false; }
    return ALLOWED.some((pattern) => {
      if (!pattern.includes("*.")) return origin === pattern;
      let wildcard;
      try { wildcard = new URL(pattern.replace("*.", "wildcard.")); } catch { return false; }
      if (candidate.protocol !== wildcard.protocol || candidate.port !== wildcard.port) return false;
      const suffix = wildcard.hostname.toLowerCase().replace(/^wildcard\./, "");
      const host = candidate.hostname.toLowerCase();
      return host.endsWith("." + suffix) && host.length > suffix.length + 1;
    });
  };

  let parentOrigin = null;
  let loaded = false;
  let video = null;
  let lastSent = "";
  let lastSentAt = 0;

  const send = (message) => {
    if (parentOrigin) window.parent.postMessage(message, parentOrigin);
  };

  const report = (force) => {
    if (!video) return;
    const state = !video.paused && !video.ended && video.readyState >= 3 ? "PLAYING" : "PAUSED";
    const now = Date.now();
    if (!force && state === lastSent && now - lastSentAt < REPORT_EVERY_MS) return;
    lastSent = state;
    lastSentAt = now;
    send({
      type: "state",
      positionSeconds: Number.isFinite(video.currentTime) ? video.currentTime : 0,
      durationSeconds: Number.isFinite(video.duration) ? video.duration : null,
      state,
    });
  };

  // DynTube sizes its player as a fixed-ratio box; stretch every container
  // between #player and the video so the frame is filled and the video
  // letterboxed, instead of a black strip under a 16:9 box.
  const fill = (element) => {
    for (let node = element.parentElement; node && node.id !== "player"; node = node.parentElement) {
      for (const [name, value] of [["width", "100%"], ["height", "100%"], ["max-height", "none"],
          ["padding-top", "0"], ["padding-bottom", "0"]]) {
        node.style.setProperty(name, value, "important");
      }
    }
    for (const [name, value] of [["width", "100%"], ["height", "100%"], ["object-fit", "contain"]]) {
      element.style.setProperty(name, value, "important");
    }
  };

  // Fullscreen goes through the parent (see the file comment).
  let fullscreenOn = false;
  const requestToggle = function () {
    send({ type: "fullscreen", on: !fullscreenOn });
    return Promise.resolve();
  };
  const requestExit = function () {
    if (fullscreenOn) send({ type: "fullscreen", on: false });
    return Promise.resolve();
  };
  for (const name of ["requestFullscreen", "webkitRequestFullscreen", "webkitRequestFullScreen"]) {
    Element.prototype[name] = requestToggle;
  }
  HTMLVideoElement.prototype.webkitEnterFullscreen = requestToggle;
  for (const name of ["exitFullscreen", "webkitExitFullscreen", "webkitCancelFullScreen"]) {
    Document.prototype[name] = requestExit;
  }
  for (const name of ["fullscreenElement", "webkitFullscreenElement", "webkitCurrentFullScreenElement"]) {
    Object.defineProperty(Document.prototype, name, {
      configurable: true,
      get() { return fullscreenOn ? document.documentElement : null; },
    });
  }
  for (const name of ["fullscreenEnabled", "webkitFullscreenEnabled"]) {
    Object.defineProperty(Document.prototype, name, { configurable: true, get() { return true; } });
  }

  const attach = (element, allowSeeking, startAt) => {
    video = element;
    fill(video);
    video.disablePictureInPicture = true;
    video.setAttribute("controlsList", "nodownload noremoteplayback");
    video.addEventListener("contextmenu", (event) => event.preventDefault());
    let furthest = startAt;
    let resumed = startAt <= 0;
    const resume = () => {
      if (resumed || !(video.duration > 0)) return;
      resumed = true;
      video.currentTime = Math.min(startAt, Math.max(0, video.duration - 1));
    };
    video.addEventListener("loadedmetadata", resume);
    resume();
    video.addEventListener("timeupdate", () => {
      if (!video.seeking) furthest = Math.max(furthest, video.currentTime);
      report(false);
    });
    if (!allowSeeking) {
      video.addEventListener("seeking", () => {
        if (video.currentTime > furthest + SEEK_TOLERANCE_SECONDS) video.currentTime = furthest;
      });
    }
    for (const name of ["play", "playing", "pause", "waiting", "ended", "seeked"]) {
      video.addEventListener(name, () => report(true));
    }
    report(true);
  };

  const load = (key, allowSeeking, startAt) => {
    const holder = document.createElement("div");
    holder.setAttribute("data-dyntube-key", key);
    document.getElementById("player").replaceChildren(holder);

    // DynTube's player draws its <video> inside this page: watch for it.
    const observer = new MutationObserver(() => {
      const element = document.querySelector("#player video");
      if (element && element !== video) attach(element, allowSeeking, startAt);
    });
    observer.observe(document.getElementById("player"), { childList: true, subtree: true });

    const script = document.createElement("script");
    script.async = true;
    script.src = SCRIPT;
    script.addEventListener("error", () => send({ type: "error", code: "LOAD_FAILED" }));
    document.head.appendChild(script);
  };

  window.addEventListener("message", (event) => {
    if (event.source !== window.parent || !allowed(event.origin)) return;
    const data = event.data;
    if (!data || typeof data !== "object") return;
    if (data.type === "load" && !loaded) {
      if (typeof data.key !== "string" || !KEY.test(data.key)) return;
      loaded = true;
      parentOrigin = event.origin;
      const startAt = Number.isFinite(data.startAt) && data.startAt > 0 ? data.startAt : 0;
      load(data.key, data.allowSeeking === true, startAt);
    } else if (data.type === "fullscreen-state" && event.origin === parentOrigin) {
      const on = data.on === true;
      if (on !== fullscreenOn) {
        fullscreenOn = on;
        document.dispatchEvent(new Event("fullscreenchange"));
        document.dispatchEvent(new Event("webkitfullscreenchange"));
      }
      if (video) fill(video);
    } else if (data.type === "stop" && event.origin === parentOrigin) {
      if (video) video.pause();
      document.getElementById("player").replaceChildren();
      video = null;
      parentOrigin = null;
    }
  });
})();
</script>
</body>
</html>`;
}
