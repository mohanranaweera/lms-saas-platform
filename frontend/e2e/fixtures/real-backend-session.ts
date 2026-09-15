import { test as base, type BrowserContext, type Page } from "@playwright/test";
import { BACKEND_ORIGIN, credentialEnvVarNames, type PortalRole, type SeedTenant } from "./seed/tenants";

/**
 * `test.extend`-based, real-backend, two-tenant authenticated-session fixture
 * layer (`docs/plans/MVP-021 Integration and Staging Review.md` §9.3/§18.4).
 *
 * Two things this file deliberately does NOT do, and why:
 *
 * 1. It never uses `page.route()` to fulfill/mock an auth response. Every
 *    request this fixture drives is a real request to `BACKEND_ORIGIN`
 *    (default `http://localhost:8080`) — see `hostHeaderRoute` below, which
 *    only ever *rewrites a request header*, never fabricates a response.
 * 2. It never stores/injects a bearer access token into browser storage as a
 *    shortcut. `frontend/src/lib/auth/auth-context.tsx` deliberately keeps
 *    the access token in-memory-only (React state, never `localStorage`/a
 *    JS-readable cookie) as an XSS hardening measure — there is no client
 *    storage location to seed even if we wanted to. The only legitimate way
 *    to establish a session is to actually drive the real `/login` UI form,
 *    which is exactly what `loginAsRole` below does.
 *
 * ENVIRONMENT STATUS (read `frontend/e2e/README.md` for the full write-up):
 * this fixture is fully wired and was verified, piece by piece, against a
 * real locally-running backend during this module's implementation:
 *   - The `Host`-header rewrite technique below (`context.route` +
 *     `route.continue({ headers })`) was confirmed, at the wire/protocol
 *     level, to correctly drive `TenantResolutionFilter`'s per-request tenant
 *     resolution (verified via a direct Playwright `APIRequestContext` call
 *     with a rewritten `Host` header, which produced a real, tenant-specific
 *     `TENANT_UNAVAILABLE` response rather than a malformed-request error).
 *   - However, actually calling it from *inside a rendered browser page* — as
 *     `loginAsRole` below does, by design — currently fails 100% of the time
 *     for a reason unrelated to the Host-header mechanism itself: the backend
 *     has no CORS configuration at all (`docs/api/identity-access-service.md`
 *     "CORS caveat"), so *every* cross-origin request from the Next.js dev
 *     server (`localhost:3000`) to the natively-run backend
 *     (`localhost:8080`) — credentialed or not, "simple" or preflighted — is
 *     unconditionally blocked by the browser before any response is
 *     observable, regardless of any header rewrite. This was independently
 *     verified during this module's implementation (see README).
 *   - Independently, no seeded credential exists for any of the 4 portal
 *     roles yet — see `fixtures/seed/two-tenant-seed.mjs`'s blocker report.
 *
 * Both gaps require a `backend/`/infrastructure change outside this task's
 * test-only scope. Until then, `loginAsRole` throws a clear, descriptive
 * error identifying which of the two problems stopped it, and any spec built
 * on this fixture must react by failing loudly / skipping with a visible
 * reason (see `frontend/e2e/cross-tenant/*.spec.ts` and this task's
 * governing brief) — never by falling back to a `page.route()` mock.
 */

export interface TenantSessionOptions {
  role: PortalRole;
  tenant: SeedTenant | null; // null only for PLATFORM_ADMIN
}

/**
 * Rewrites the `Host` header on every request this browser context makes to
 * `BACKEND_ORIGIN` to `tenant.subdomain`, for the lifetime of the context.
 * `route.continue({ headers })` operates beneath the Fetch spec's
 * browser-enforced forbidden-request-header restriction (a plain
 * `fetch(url, { headers: { Host: ... } })` call from page JS would be
 * silently stripped/rejected) — this is the one legitimate way to exercise
 * `TenantResolutionFilter`'s Host-header-subdomain resolution from a
 * synthetic local subdomain without any `/etc/hosts` or DNS entry, per this
 * module's brief.
 */
export async function installHostHeaderRewrite(context: BrowserContext, subdomain: string): Promise<void> {
  const backendHost = new URL(BACKEND_ORIGIN).host; // e.g. "localhost:8080"
  await context.route(`${BACKEND_ORIGIN}/**`, async (route) => {
    const request = route.request();
    const headers = { ...(await request.allHeaders()), host: subdomain };
    await route.continue({ headers });
  });
  // Documented, not asserted here: the rewritten Host header must not equal
  // the raw backendHost, or nothing would actually be rewritten.
  if (subdomain === backendHost) {
    throw new Error(`installHostHeaderRewrite: subdomain '${subdomain}' must differ from the backend host.`);
  }
}

export class RealBackendSessionUnavailableError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "RealBackendSessionUnavailableError";
  }
}

/**
 * Drives the real `/login` (or `/platform-admin/login`) UI form with a
 * credential read from the environment (populated by an operator once the
 * account-provisioning gap in `two-tenant-seed.mjs` is closed — never
 * hardcoded here). Throws `RealBackendSessionUnavailableError` with a
 * specific, actionable reason if the credential is missing or the login
 * request never completes (e.g. blocked by CORS) — callers (fixtures/specs)
 * must let this propagate into a loud failure or an explicit
 * `test.skip(true, reason)`, never swallow it.
 */
export async function loginAsRole(page: Page, options: TenantSessionOptions): Promise<void> {
  const { role, tenant } = options;
  const { emailVar, passwordVar } = credentialEnvVarNames(role, tenant);
  const email = process.env[emailVar];
  const password = process.env[passwordVar];

  if (!email || !password) {
    throw new RealBackendSessionUnavailableError(
      `No seeded credential for role=${role} tenant=${tenant?.subdomain ?? "(platform)"}: ` +
        `expected env vars ${emailVar}/${passwordVar} to be set. As of this module's ` +
        "implementation pass, no API path exists to provision this account " +
        "(see frontend/e2e/README.md 'Blocked: account provisioning'), so these " +
        "env vars are never set today — this is expected until that gap closes."
    );
  }

  const loginPath = role === "PLATFORM_ADMIN" ? "/platform-admin/login" : "/login";
  await page.goto(loginPath);
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password", { exact: true }).fill(password);

  const [response] = await Promise.all([
    page
      .waitForResponse(
        (res) => res.url().includes("/auth/login") && res.request().method() === "POST",
        { timeout: 10_000 }
      )
      .catch(() => null),
    page.getByRole("button", { name: "Sign in" }).click(),
  ]);

  if (!response) {
    throw new RealBackendSessionUnavailableError(
      "The browser never completed a POST .../auth/login request within 10s. In this " +
        "local dev topology (frontend on localhost:3000, backend on " +
        `${BACKEND_ORIGIN}), the most likely cause is the backend's missing CORS ` +
        "configuration (docs/api/identity-access-service.md 'CORS caveat') blocking " +
        "the cross-origin request before any response is observable — verified during " +
        "this module's implementation. See frontend/e2e/README.md."
    );
  }
  if (!response.ok()) {
    throw new RealBackendSessionUnavailableError(
      `Login POST for role=${role} tenant=${tenant?.subdomain ?? "(platform)"} returned ` +
        `HTTP ${response.status()} — see response body for the backend's error.code.`
    );
  }
}

interface RealBackendFixtures {
  tenantSession: (options: TenantSessionOptions) => Promise<void>;
}

/**
 * `test.extend` fixture exposing `tenantSession(options)` — call it at the
 * top of a test body (after installing any per-tenant Host-header rewrite)
 * to obtain a real, backend-authenticated session for `options.role` in
 * `options.tenant`. Parameterized by role × tenant per this module's brief
 * (4 roles × ≥2 tenants), not by a fixed `test.use()` option, since a single
 * cross-tenant spec typically needs two different sessions (tenant A actor,
 * tenant B resource) in one test body.
 */
export const test = base.extend<RealBackendFixtures>({
  // Playwright's fixture callback parameter is named `use`; it is not React's `use()` hook.
  tenantSession: async ({ page, context }, use) => {
    // eslint-disable-next-line react-hooks/rules-of-hooks
    await use(async (options: TenantSessionOptions) => {
      if (options.tenant) {
        await installHostHeaderRewrite(context, options.tenant.subdomain);
      }
      await loginAsRole(page, options);
    });
  },
});

export { expect } from "@playwright/test";
