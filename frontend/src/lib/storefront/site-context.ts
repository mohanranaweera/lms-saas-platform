/**
 * STORE-01 / ADR-037: pure helpers for the host-resolved site context (no
 * React, no fetch) - shared by `src/proxy.ts` (server, per request), the
 * client `StorefrontGate`, and unit tests.
 *
 * The backend resolves tenant + storefront from the host; nothing here
 * decides access. These helpers only choose what to render (storefront page,
 * not-found page) and where a retired slug redirects to.
 */

export const STOREFRONT_NOT_FOUND = "STOREFRONT_NOT_FOUND";
export const STOREFRONT_MOVED = "STOREFRONT_MOVED";
export const STOREFRONT_PRODUCT_OUT_OF_SCOPE = "STOREFRONT_PRODUCT_OUT_OF_SCOPE";

/** Friendly checkout copy for `422 STOREFRONT_PRODUCT_OUT_OF_SCOPE`. */
export const STOREFRONT_OUT_OF_SCOPE_MESSAGE =
  "This course isn't sold on this teacher's page. Remove it from your cart, or buy it from the institute's main site.";

/** `GET /api/v1/public/site-context` storefront projection. */
export interface SiteStorefront {
  slug: string;
  teacherId: string;
  displayName: string;
  photoUrl: string | null;
  bio: string | null;
  host: string;
}

export interface SiteContext {
  kind: "TENANT" | "STOREFRONT";
  storefront: SiteStorefront | null;
}

export type StorefrontHostOutcome =
  | { kind: "not-found" }
  | { kind: "moved"; host: string };

/** Case-insensitive host (optionally with port) split into hostname + port. */
function splitHost(host: string): { hostname: string; port: string } {
  const value = host.trim().toLowerCase();
  const colon = value.lastIndexOf(":");
  if (colon > 0 && /^\d+$/.test(value.slice(colon + 1))) {
    return { hostname: value.slice(0, colon), port: value.slice(colon + 1) };
  }
  return { hostname: value, port: "" };
}

const HOSTNAME_PATTERN = /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$/;

/**
 * The tenant site of a storefront host: the host minus its first label, port
 * kept (`alice.abc.lms.test:3000` -> `abc.lms.test:3000`). `null` when the
 * host has fewer than three labels (cannot be a storefront host).
 */
export function tenantHostOf(host: string): string | null {
  const { hostname, port } = splitHost(host);
  const labels = hostname.split(".");
  if (labels.length < 3 || labels.some((label) => label.length === 0)) return null;
  const parent = labels.slice(1).join(".");
  return port ? `${parent}:${port}` : parent;
}

/**
 * Whether a host could be a storefront host at all (>= 3 labels, not an IP,
 * not localhost). Used only to skip a pointless backend round trip in the
 * proxy; the backend still decides.
 */
export function mayBeStorefrontHost(host: string | null | undefined): boolean {
  if (!host) return false;
  const { hostname } = splitHost(host);
  if (hostname.startsWith("[") || /^\d+(\.\d+){3}$/.test(hostname)) return false;
  return HOSTNAME_PATTERN.test(hostname) && hostname.split(".").length >= 3;
}

/**
 * Validates the backend's `details.host` for `STOREFRONT_MOVED`: a plain
 * hostname under the same tenant as the current host (same parent labels), so
 * a redirect can only ever move between storefronts of this tenant. The
 * current port is kept when the backend's host carries none (local dev).
 * Returns `null` when the target is unusable.
 */
export function movedTargetHost(currentHost: string, detailsHost: unknown): string | null {
  if (typeof detailsHost !== "string" || detailsHost.length === 0 || detailsHost.length > 253) return null;
  const target = splitHost(detailsHost);
  const current = splitHost(currentHost);
  if (!HOSTNAME_PATTERN.test(target.hostname)) return null;
  const targetParent = tenantHostOf(target.hostname);
  const currentParent = tenantHostOf(current.hostname);
  if (!targetParent || !currentParent || targetParent !== currentParent) return null;
  if (target.hostname === current.hostname) return null;
  const port = target.port || current.port;
  return port ? `${target.hostname}:${port}` : target.hostname;
}

/**
 * Maps an API error (status/code/details) on the current host to what the
 * site must do: show the storefront not-found page, or 308 to the current
 * storefront host. `null` for every other error (handled by the caller as
 * usual). A MOVED answer whose target fails validation is treated as
 * not-found - never as the tenant catalog.
 */
export function classifyStorefrontError(
  status: number,
  code: string | null | undefined,
  details: Record<string, unknown> | null | undefined,
  currentHost: string
): StorefrontHostOutcome | null {
  if (status !== 404) return null;
  if (code === STOREFRONT_NOT_FOUND) return { kind: "not-found" };
  if (code === STOREFRONT_MOVED) {
    const host = movedTargetHost(currentHost, details?.host);
    return host ? { kind: "moved", host } : { kind: "not-found" };
  }
  return null;
}

/** Same path, query and hash on the new host (same protocol). */
export function movedUrl(current: { protocol: string; pathname: string; search: string; hash?: string }, host: string): string {
  const pathname = current.pathname.startsWith("/") ? current.pathname : `/${current.pathname}`;
  return `${current.protocol}//${host}${pathname}${current.search}${current.hash ?? ""}`;
}

/**
 * Narrows an untrusted `site-context` payload. Anything not clearly a
 * storefront is treated as the tenant site (older backends have no such
 * endpoint; tenant hosts must stay unchanged).
 */
export function normalizeSiteContext(data: unknown): SiteContext {
  if (!data || typeof data !== "object") return { kind: "TENANT", storefront: null };
  const record = data as { kind?: unknown; storefront?: unknown };
  const storefront = record.storefront as Partial<SiteStorefront> | null | undefined;
  if (
    record.kind === "STOREFRONT" &&
    storefront &&
    typeof storefront === "object" &&
    typeof storefront.teacherId === "string" &&
    typeof storefront.displayName === "string"
  ) {
    return {
      kind: "STOREFRONT",
      storefront: {
        slug: typeof storefront.slug === "string" ? storefront.slug : "",
        teacherId: storefront.teacherId,
        displayName: storefront.displayName,
        photoUrl: typeof storefront.photoUrl === "string" && storefront.photoUrl ? storefront.photoUrl : null,
        bio: typeof storefront.bio === "string" && storefront.bio.trim() ? storefront.bio : null,
        host: typeof storefront.host === "string" ? storefront.host : "",
      },
    };
  }
  return { kind: "TENANT", storefront: null };
}

/**
 * Admin preview of a storefront address for a new slug, built from a URL the
 * server returned (`previewUrl`/`publishedUrl`) by swapping its first host
 * label. Display only - the saved address is whatever the server returns.
 */
export function storefrontUrlWithSlug(serverUrl: string | null | undefined, slug: string): string | null {
  if (!serverUrl || !slug) return null;
  let url: URL;
  try {
    url = new URL(serverUrl);
  } catch {
    return null;
  }
  const labels = url.hostname.split(".");
  if (labels.length < 3) return null;
  labels[0] = slug;
  url.hostname = labels.join(".");
  return url.toString();
}
