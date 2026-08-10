import type { Page, Route } from "@playwright/test";

/**
 * Shared fixtures/helpers for mocking identity-access-service's `/v1/auth/**`
 * and `/v1/platform-admin/auth/**` endpoints in Playwright specs, per the
 * envelope contract documented in `docs/api/identity-access-service.md`.
 *
 * No real backend runs in this environment (see `playwright.config.ts` — the
 * only `webServer` entry starts the Next.js dev server, nothing else), and the
 * tenant-subdomain Host-header requirement documented in that contract's
 * "Tenant resolution caveat" section means a real end-to-end login isn't
 * reachable from this dev/CI setup regardless. Every login/refresh/logout spec
 * therefore drives the UI against `page.route()`-intercepted responses shaped
 * exactly like the documented `ApiResponse<T>` envelope, following the same
 * "no backend in this environment" posture already noted for
 * `shared-states.spec.ts`.
 */

export interface FieldError {
  field: string;
  message: string;
}

function nowIso(): string {
  return new Date().toISOString();
}

function traceId(): string {
  return `trace-${Math.random().toString(36).slice(2)}`;
}

/** `ApiResponse<T>` success envelope. */
export function apiSuccess<T>(data: T) {
  return { success: true, data, error: null, timestamp: nowIso(), traceId: traceId() };
}

/** `ApiResponse<T>` error envelope — `data` is always `null` on error. */
export function apiError(code: string, message: string, fieldErrors: FieldError[] = []) {
  return {
    success: false,
    data: null,
    error: { code, message, fieldErrors },
    timestamp: nowIso(),
    traceId: traceId(),
  };
}

function base64url(input: Record<string, unknown>): string {
  return Buffer.from(JSON.stringify(input))
    .toString("base64")
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "");
}

/**
 * Builds a structurally valid (three dot-separated segments) but fake-signed
 * JWT. `lib/auth/jwt.ts` only ever base64-decodes the payload for
 * display/routing convenience and explicitly never verifies the signature
 * client-side (the backend is the sole authority) — so a dummy signature
 * segment is sufficient here.
 */
export function fakeJwt(payload: Record<string, unknown> = {}): string {
  const header = base64url({ alg: "HS256", typ: "JWT" });
  const body = base64url({
    sub: "user-1",
    tenant_id: "tenant-1",
    session_id: "session-1",
    iat: Math.floor(Date.now() / 1000),
    exp: Math.floor(Date.now() / 1000) + 900,
    ...payload,
  });
  return `${header}.${body}.fake-signature`;
}

export function loginResponseBody(
  accessToken: string,
  overrides: Partial<{ expiresInSeconds: number; sessionId: string; mustChangePassword: boolean }> = {}
) {
  return {
    accessToken,
    expiresInSeconds: overrides.expiresInSeconds ?? 900,
    sessionId: overrides.sessionId ?? "session-1",
    mustChangePassword: overrides.mustChangePassword ?? false,
  };
}

export function refreshResponseBody(accessToken: string, expiresInSeconds = 900) {
  return { accessToken, expiresInSeconds };
}

/** Fulfills a route with a JSON `ApiResponse<T>` envelope body. */
export async function fulfillJson(route: Route, status: number, body: unknown): Promise<void> {
  await route.fulfill({
    status,
    contentType: "application/json",
    body: JSON.stringify(body),
  });
}

/**
 * Registers a fixed-response mock for every request matching `urlGlob` for
 * the lifetime of the page (or until `page.unroute` is called). Use for
 * endpoints that are only ever expected to be called with one outcome in a
 * given test.
 */
export async function mockJson(page: Page, urlGlob: string, status: number, body: unknown): Promise<void> {
  await page.route(urlGlob, async (route) => {
    await fulfillJson(route, status, body);
  });
}
