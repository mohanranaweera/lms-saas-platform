/**
 * Shared two-tenant constants for the MVP-021 (INTG-1) real-backend,
 * cross-tenant Playwright layer.
 *
 * These subdomains are **not** real DNS/hosts-file entries — per the module
 * plan (`docs/plans/MVP-021 Integration and Staging Review.md` §"Grounding
 * note") and `docs/api/identity-access-service.md`'s "Tenant resolution
 * caveat", `TenantResolutionFilter` resolves tenant identity purely from the
 * incoming request's `Host` header subdomain label. They are used only as
 * the literal `Host` header value rewritten onto outgoing requests via
 * Playwright's `context.route()` / `route.continue({ headers })` — see
 * `frontend/e2e/fixtures/real-backend-session.ts`.
 *
 * IMPORTANT — read `frontend/e2e/README.md` before using this file. As of
 * this module's implementation pass, the two-tenant seed mechanism can only
 * reach the **anonymous tenant self-registration** step
 * (`POST /api/v1/tenant-registrations`) through existing backend REST APIs.
 * No authenticated account (Platform Admin, Tenant Admin, Teacher, or
 * Student) can currently be provisioned through any shipped, public API —
 * see the README's "Blocked: account provisioning" section. Every value
 * below that implies a *logged-in* fixture (email/password pairs) is
 * therefore a placeholder shape, not a working credential, until that gap is
 * closed.
 */

export const BACKEND_ORIGIN = process.env.E2E_BACKEND_ORIGIN ?? "http://localhost:8080";

export interface SeedTenant {
  /** The `Host` header subdomain label used for every request scoped to this tenant. */
  subdomain: string;
  name: string;
  requestedPlan: string;
  contactName: string;
  contactEmail: string;
  contactPhone: string;
}

export const TENANT_A: SeedTenant = {
  subdomain: "tenant1.lms.local",
  name: "E2E Tenant One",
  requestedPlan: "trial",
  contactName: "E2E Admin One",
  contactEmail: "admin@tenant1.example.com",
  contactPhone: "+10000000001",
};

export const TENANT_B: SeedTenant = {
  subdomain: "tenant2.lms.local",
  name: "E2E Tenant Two",
  requestedPlan: "trial",
  contactName: "E2E Admin Two",
  contactEmail: "admin@tenant2.example.com",
  contactPhone: "+10000000002",
};

/**
 * The 4 top-level portal roles this module's fixture layer must support
 * (`docs/requirements/user-roles-and-permissions.md` §1;
 * `docs/plans/MVP-021 Integration and Staging Review.md` §2). Platform Admin
 * is not tenant-scoped — it never uses a `Host` header rewrite.
 */
export type PortalRole = "STUDENT" | "TEACHER" | "TENANT_ADMIN" | "PLATFORM_ADMIN";

/**
 * Env-var-driven credential lookup for a seeded fixture identity. Deliberately
 * reads from `process.env` rather than hardcoding a password: until the
 * account-provisioning gap documented in the README is closed, no value
 * populates these at all, and every fixture/spec that needs one must fail
 * loudly (see `real-backend-session.ts`) rather than silently using a
 * made-up credential that was never actually provisioned server-side.
 */
export function credentialEnvVarNames(role: PortalRole, tenant: SeedTenant | null) {
  const scope = tenant ? tenant.subdomain.split(".")[0].toUpperCase() : "PLATFORM";
  const prefix = `E2E_${scope}_${role}`;
  return { emailVar: `${prefix}_EMAIL`, passwordVar: `${prefix}_PASSWORD` };
}
