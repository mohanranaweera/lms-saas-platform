import { NextResponse, type NextRequest } from "next/server";
import {
  STOREFRONT_MOVED,
  STOREFRONT_NOT_FOUND,
  mayBeStorefrontHost,
  movedTargetHost,
  movedUrl,
} from "@/lib/storefront/site-context";

/**
 * STORE-01 / ADR-037 decision 3: per page request, ask the backend what this
 * host is (`GET /api/v1/public/site-context`, sent with the site's host in
 * `X-Forwarded-Host`, exactly like the `/api` rewrite in `next.config.ts`).
 *
 * - `404 STOREFRONT_NOT_FOUND` -> render the storefront not-found page (404),
 *   never the tenant catalog.
 * - `404 STOREFRONT_MOVED` -> 308 to the same path on `details.host` (only a
 *   storefront host of the same tenant is accepted).
 * - anything else (tenant host, success, backend down, older backend) -> the
 *   page renders as before; the client `StorefrontGate` still reacts to the
 *   same codes on any later API call.
 *
 * Never cached: `cache: "no-store"`, one request per page request, so an
 * answer for one host is never reused for another. Hosts that cannot be a
 * storefront (localhost, IPs, fewer than three labels) skip the round trip.
 * Nothing here authorizes anything - the backend scopes every API call itself.
 */
const BACKEND_ORIGIN = (process.env.BACKEND_ORIGIN ?? "http://localhost:8080").replace(/\/$/, "");
const SITE_CONTEXT_TIMEOUT_MS = 2_000;

interface ErrorEnvelope {
  error?: { code?: unknown; details?: { host?: unknown } | null } | null;
}

export async function proxy(request: NextRequest) {
  const host = request.headers.get("host");
  if (!host || !mayBeStorefrontHost(host)) return NextResponse.next();

  let response: Response;
  try {
    response = await fetch(`${BACKEND_ORIGIN}/api/v1/public/site-context`, {
      headers: {
        Accept: "application/json",
        "X-Forwarded-Host": host,
        "X-Forwarded-Proto": request.nextUrl.protocol.replace(/:$/, ""),
      },
      cache: "no-store",
      redirect: "manual",
      signal: AbortSignal.timeout(SITE_CONTEXT_TIMEOUT_MS),
    });
  } catch {
    return NextResponse.next();
  }
  if (response.status !== 404) return NextResponse.next();

  let body: ErrorEnvelope | null = null;
  try {
    body = (await response.json()) as ErrorEnvelope;
  } catch {
    return NextResponse.next();
  }
  const code = body?.error?.code;
  if (code !== STOREFRONT_NOT_FOUND && code !== STOREFRONT_MOVED) return NextResponse.next();

  if (code === STOREFRONT_MOVED) {
    const target = movedTargetHost(host, body?.error?.details?.host);
    if (target) {
      const url = movedUrl(
        { protocol: request.nextUrl.protocol, pathname: request.nextUrl.pathname, search: request.nextUrl.search },
        target
      );
      return NextResponse.redirect(url, 308);
    }
  }
  const notFound = request.nextUrl.clone();
  notFound.pathname = "/storefront-not-found";
  notFound.search = "";
  return NextResponse.rewrite(notFound);
}

export const config = {
  matcher: [
    // Pages only: not /api (the backend answers those itself), Next internals,
    // the not-found page, or files with an extension.
    "/((?!api|_next/static|_next/image|_next/data|storefront-not-found|embed|favicon\\.ico|.*\\..*).*)",
  ],
};
