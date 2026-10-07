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

const subscribeNever = () => () => undefined;

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

const ALLOWED = /^aocplayer:\/\/(play|pair)\?code=[A-Za-z0-9_-]{43}$/;

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
