"use client";

import { useEffect, useState, type ReactNode } from "react";
import { LoadingState } from "@/components/states/loading-state";
import { StorefrontNotFound } from "./storefront-not-found";
import { movedUrl, type StorefrontHostOutcome } from "@/lib/storefront/site-context";
import { subscribeStorefrontErrors } from "@/lib/storefront/storefront-signal";

/**
 * STORE-01 / ADR-037 decision 3, client side. `src/proxy.ts` already answers a
 * document request on an unknown storefront host with the not-found page (or a
 * 308 for a retired slug); this gate covers everything after that - any API
 * call that answers `404 STOREFRONT_NOT_FOUND` replaces the page with the
 * not-found page, and `STOREFRONT_MOVED` moves the browser to the same path on
 * the current storefront host. On the institute host, `COURSE_ON_STOREFRONT`
 * (a course whose teachers all sell on their own page, ADR-037 decision 6
 * amended 2026-11-21) moves the browser to the same path on that storefront.
 * Every other answer renders `children` untouched.
 */
export function StorefrontGate({ children }: { children: ReactNode }) {
  const [outcome, setOutcome] = useState<StorefrontHostOutcome | null>(null);

  useEffect(() => subscribeStorefrontErrors(setOutcome), []);

  useEffect(() => {
    if (outcome?.kind !== "moved") return;
    window.location.replace(movedUrl(window.location, outcome.host));
  }, [outcome]);

  if (outcome?.kind === "not-found") return <StorefrontNotFound />;
  if (outcome?.kind === "moved") {
    return (
      <main className="flex min-h-svh flex-1 items-center justify-center px-4" data-testid="storefront-moved">
        <LoadingState label="This page has moved. Redirecting…" />
      </main>
    );
  }
  return <>{children}</>;
}
