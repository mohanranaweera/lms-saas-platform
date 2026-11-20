import { useQuery } from "@tanstack/react-query";
import { apiFetch } from "./client";
import { normalizeSiteContext, type SiteContext } from "@/lib/storefront/site-context";

/**
 * STORE-01 / ADR-037: `GET /api/v1/public/site-context` - what the current
 * host is (the tenant site or one teacher's storefront). Anonymous, resolved
 * server-side from the host; there is nothing to send. Keyed by host so a
 * browser moving between hosts never reuses another host's answer.
 */
export const siteContextKeys = {
  all: ["site-context"] as const,
  host: (host: string) => ["site-context", host] as const,
};

export async function getSiteContext(): Promise<SiteContext> {
  return normalizeSiteContext(await apiFetch<unknown>("/v1/public/site-context"));
}

function currentHost(): string {
  return typeof window === "undefined" ? "server" : window.location.host;
}

export function useSiteContext() {
  return useQuery({
    queryKey: siteContextKeys.host(currentHost()),
    queryFn: getSiteContext,
    // 404 STOREFRONT_* is final for this host (the gate takes over); an older
    // backend without the endpoint means "tenant site".
    retry: false,
    staleTime: 5 * 60_000,
  });
}

/**
 * The storefront of this host, `null` on a tenant host, while loading, or when
 * the endpoint failed (fail-open to the tenant site; the backend scopes every
 * catalog/checkout call on a storefront host regardless).
 */
export function useCurrentStorefront() {
  const query = useSiteContext();
  return query.data?.kind === "STOREFRONT" ? query.data.storefront : null;
}
