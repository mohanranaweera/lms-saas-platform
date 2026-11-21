"use client";

import { useSyncExternalStore } from "react";
import { tenantHostOf } from "./site-context";

function subscribe(): () => void {
  return () => {};
}

/**
 * The tenant site of this storefront host, same protocol and port
 * (`http://abc.lms.test:3000/` for `alice.abc.lms.test:3000`), optionally with
 * a path. `null` during SSR or when the host has too few labels.
 */
export function useTenantSiteUrl(path = "/"): string | null {
  return useSyncExternalStore(
    subscribe,
    () => {
      const parent = tenantHostOf(window.location.host);
      return parent ? `${window.location.protocol}//${parent}${path}` : null;
    },
    () => null
  );
}
