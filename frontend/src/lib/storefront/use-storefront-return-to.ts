"use client";

import { useSyncExternalStore } from "react";
import { usePathname } from "next/navigation";
import { useCurrentStorefront } from "@/lib/api/site-context";
import { safeReturnTo, withReturnTo, RETURN_TO_PARAM } from "@/lib/navigation/return-to";

/**
 * STORE-01: on a storefront host, sign-in/registration links carry the page
 * the visitor is on (`?returnTo=/courses/x`) so they come back to this
 * storefront afterwards. Tenant hosts keep the plain links (unchanged flows).
 */
export function useStorefrontAuthHref(path: "/login" | "/register"): string {
  const pathname = usePathname();
  const storefront = useCurrentStorefront();
  return storefront ? withReturnTo(path, pathname) : path;
}

function subscribe(): () => void {
  return () => {};
}

/**
 * The validated `returnTo` of the current URL (or `null`). Reads
 * `window.location` so pages without a Suspense boundary can use it.
 */
export function useReturnToParam(): string | null {
  const search = useSyncExternalStore(
    subscribe,
    () => window.location.search,
    () => ""
  );
  return safeReturnTo(new URLSearchParams(search).get(RETURN_TO_PARAM));
}
