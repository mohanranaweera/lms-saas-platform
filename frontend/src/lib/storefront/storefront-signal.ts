import { classifyStorefrontError, type StorefrontHostOutcome } from "./site-context";

/**
 * STORE-01: any `/api` call on an unknown/disabled/retired storefront host
 * answers `404 STOREFRONT_NOT_FOUND` / `STOREFRONT_MOVED`. `apiFetch` reports
 * those here, and the root `StorefrontGate` replaces the page with the
 * not-found page or redirects - whichever page made the call. Browser only.
 */
type Listener = (outcome: StorefrontHostOutcome) => void;

const listeners = new Set<Listener>();
let last: StorefrontHostOutcome | null = null;

export function reportStorefrontError(status: number, code: string, details: Record<string, unknown> | null): void {
  if (typeof window === "undefined") return;
  const outcome = classifyStorefrontError(status, code, details, window.location.host);
  if (!outcome) return;
  last = outcome;
  for (const listener of listeners) listener(outcome);
}

/** Subscribes; a listener registered after a report still receives it. */
export function subscribeStorefrontErrors(listener: Listener): () => void {
  listeners.add(listener);
  if (last) listener(last);
  return () => {
    listeners.delete(listener);
  };
}
