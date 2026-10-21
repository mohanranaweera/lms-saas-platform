import { useSyncExternalStore } from "react";

/**
 * Hands an `aocplayer://` link to the operating system (VIDEO Phase 8). Must be
 * called from a user's click so the browser lets the custom scheme open.
 *
 * `window.__aocLaunch` lets Playwright capture the link instead of asking the
 * OS (headless Chromium has no AOC Player). It only ever receives the one-time
 * link the backend just issued to this signed-in user.
 */

declare global {
  interface Window {
    __aocLaunch?: (url: string) => void;
  }
}

/**
 * Whether this browser runs on a platform the AOC Player supports (Windows
 * desktop only for now, ADR-025 Q-015; mobile apps are planned). UX only -
 * the backend never trusts the device type, and a tenant that requires the
 * player gets no browser playback on any platform.
 */
export function isAocPlayerSupportedPlatform(userAgent: string): boolean {
  return /Windows NT/.test(userAgent) && !/Mobile|Phone|Tablet|ARM;/.test(userAgent);
}

export type MobilePlatform = "android" | "ios";

/** Android or iOS (iPadOS reports itself as a Mac with touch). UX only. */
export function mobilePlatform(userAgent: string, maxTouchPoints = 0): MobilePlatform | null {
  if (/Android/.test(userAgent)) return "android";
  if (/iPhone|iPad|iPod/.test(userAgent)) return "ios";
  if (/Macintosh/.test(userAgent) && maxTouchPoints > 1) return "ios";
  return null;
}

const subscribeNever = () => () => undefined;

/** {@link mobilePlatform} for the current browser; `null` while rendering on the server. */
export function useMobilePlatform(): MobilePlatform | null {
  return useSyncExternalStore(
    subscribeNever,
    () => mobilePlatform(window.navigator.userAgent, window.navigator.maxTouchPoints),
    () => null
  );
}

/**
 * {@link isAocPlayerSupportedPlatform} for the current browser. `null` during
 * server rendering and hydration (the user agent is only known in the
 * browser), so callers render a neutral state first.
 */
export function useAocPlayerSupported(): boolean | null {
  return useSyncExternalStore(
    subscribeNever,
    () => isAocPlayerSupportedPlatform(window.navigator.userAgent),
    () => null
  );
}

// A play link may also carry a pairing code (ADR-025 Amendment 2).
const ALLOWED = /^aocplayer:\/\/(pair\?code=[A-Za-z0-9_-]{43}|play\?code=[A-Za-z0-9_-]{43}(&pair=[A-Za-z0-9_-]{43})?)$/;

export function openInAocPlayer(url: string): boolean {
  if (!ALLOWED.test(url)) {
    return false;
  }
  if (typeof window.__aocLaunch === "function") {
    window.__aocLaunch(url);
    return true;
  }
  window.location.href = url;
  return true;
}
