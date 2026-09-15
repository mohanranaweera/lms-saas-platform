#!/usr/bin/env node
/**
 * Two-tenant seed script for the MVP-021 (INTG-1) real-backend Playwright
 * layer. Plain Node ESM (no ts-node/tsx dependency added, deliberately, per
 * this task's "test files only" boundary — adding a TS-execution
 * devDependency would mean editing `frontend/package.json`).
 *
 * Talks to the **real, locally-running backend** over plain `fetch` (Node's
 * global fetch — not a browser — so none of the CORS restrictions documented
 * in `frontend/e2e/README.md` apply here; this script's own HTTP calls are
 * unaffected by the backend's missing CORS config).
 *
 * Usage (see README for full instructions):
 *   node frontend/e2e/fixtures/seed/two-tenant-seed.mjs
 *
 * WHAT THIS SCRIPT ACTUALLY DOES, AND WHY IT STOPS WHERE IT DOES
 * ----------------------------------------------------------------
 * Per `docs/plans/MVP-021 Integration and Staging Review.md`'s brief: "the
 * two-tenant seed-data mechanism must work entirely through the backend's
 * existing, already-shipped REST APIs... If you find that some domain's
 * data genuinely cannot be provisioned through any existing public/
 * authenticated API — ... stop, list exactly what's blocked and why."
 *
 * This script:
 *   1. Registers two tenants via the one genuinely public, anonymous,
 *      already-shipped endpoint: `POST /api/v1/tenant-registrations`
 *      (`com.lms.tenantmanagement.web.TenantRegistrationController`, see
 *      `docs/api/tenant-management.md`'s intro + the controller's own
 *      Javadoc: "the platform's one legitimate public/anonymous endpoint").
 *      This step is REAL and WORKING — verified against a real locally-run
 *      backend during this module's implementation (see README).
 *   2. STOPS there and prints a clear, itemized blocker report instead of
 *      silently continuing, because every further step this module's brief
 *      requires (approving a tenant, creating a Tenant Admin/Teacher/Student
 *      account in each tenant, then logging in as each of the 4 portal
 *      roles) has **no reachable API path**:
 *        - Tenant approval (`POST /api/v1/platform-admin/tenants/{id}/approve`)
 *          requires an authenticated Platform Admin bearer token.
 *        - There is no endpoint anywhere in the shipped backend that creates
 *          a `platform_admin_user` row. `V4__create_platform_admin_user.sql`
 *          creates the table with zero seed rows; no `CommandLineRunner`/
 *          `ApplicationRunner`/dev-seed bean exists in
 *          `backend/src/main/java` (confirmed by repository-wide search).
 *        - Staff/Teacher/Student creation (`POST /api/v1/staff`,
 *          `POST /api/v1/teachers`, `POST /api/v1/students`) each require an
 *          authenticated Tenant Admin (or a narrower staff permission) bearer
 *          token.
 *        - `StaffCreateRequest.roleCode` (`docs/api's user-management.md`'s
 *          undocumented Staff Management sibling,
 *          `com.lms.usermanagement.staff.web.dto.StaffCreateRequest`)
 *          explicitly EXCLUDES `TENANT_ADMIN` from its allowed values by
 *          design ("those accounts are provisioned by other flows, not this
 *          one" — but no such other flow exists in the shipped backend).
 *      In short: there is no REST API path anywhere in this codebase, public
 *      or authenticated, that creates the very first Tenant Admin account
 *      for a newly-approved tenant, or a Platform Admin account at all. Every
 *      other role-provisioning endpoint transitively depends on one of
 *      those two existing first. This is a full stop for the "two-tenant
 *      seed-data mechanism" as scoped, not a per-feature-area gap — see
 *      `frontend/e2e/README.md` for the full write-up and what would unblock
 *      it (out of scope for this test-only task: a backend/devops change).
 */

const BACKEND_ORIGIN = process.env.E2E_BACKEND_ORIGIN ?? "http://localhost:8080";

const TENANTS = [
  {
    subdomain: "tenant1",
    name: "E2E Tenant One",
    requestedPlan: "trial",
    contactName: "E2E Admin One",
    contactEmail: "admin@tenant1.example.com",
    contactPhone: "+10000000001",
  },
  {
    subdomain: "tenant2",
    name: "E2E Tenant Two",
    requestedPlan: "trial",
    contactName: "E2E Admin Two",
    contactEmail: "admin@tenant2.example.com",
    contactPhone: "+10000000002",
  },
];

async function registerTenant(tenant) {
  const res = await fetch(`${BACKEND_ORIGIN}/api/v1/tenant-registrations`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      name: tenant.name,
      subdomain: tenant.subdomain,
      requestedPlan: tenant.requestedPlan,
      contactName: tenant.contactName,
      contactEmail: tenant.contactEmail,
      contactPhone: tenant.contactPhone,
    }),
  });

  const payload = await res.json().catch(() => null);

  if (res.status === 201 && payload?.success) {
    return { outcome: "created", tenant, response: payload.data };
  }

  // 409 CONFLICT ("A tenant with this subdomain already exists") makes this
  // script idempotent/safe to re-run against a backend that already has
  // these two tenants registered from a prior run.
  if (res.status === 409) {
    return { outcome: "already-exists", tenant, error: payload?.error };
  }

  throw new Error(
    `Unexpected response registering tenant '${tenant.subdomain}': ${res.status} ${JSON.stringify(payload)}`
  );
}

async function main() {
  console.log(`Seeding tenants against ${BACKEND_ORIGIN} ...`);

  let healthOk = false;
  try {
    const health = await fetch(`${BACKEND_ORIGIN}/actuator/health`);
    healthOk = health.ok;
  } catch (err) {
    console.error(
      `\nCannot reach the backend at ${BACKEND_ORIGIN}/actuator/health (${err.message}).\n` +
        "Start it natively first — see frontend/e2e/README.md.\n"
    );
    process.exitCode = 1;
    return;
  }
  if (!healthOk) {
    console.error(`Backend at ${BACKEND_ORIGIN} responded, but /actuator/health did not report healthy.`);
    process.exitCode = 1;
    return;
  }

  const results = [];
  for (const tenant of TENANTS) {
    const result = await registerTenant(tenant);
    results.push(result);
    console.log(
      `  [${result.outcome}] subdomain=${tenant.subdomain} ` +
        (result.response ? `id=${result.response.id} status=${result.response.status}` : `(${result.error?.code})`)
    );
  }

  console.log("\n--- Seed step 1 of N complete: tenant self-registration (the only reachable step) ---\n");
  console.log(
    [
      "BLOCKED — cannot proceed further through any existing backend REST API:",
      "",
      "  1. Tenant approval requires an authenticated Platform Admin bearer token.",
      "     No API creates a platform_admin_user row anywhere in this backend",
      "     (V4__create_platform_admin_user.sql seeds zero rows; no",
      "     CommandLineRunner/ApplicationRunner dev-seed bean exists).",
      "",
      "  2. Even if a tenant were approved, creating the first Tenant Admin",
      "     account for it requires an already-authenticated Tenant Admin —",
      "     StaffCreateRequest.roleCode explicitly excludes TENANT_ADMIN by",
      "     design, and no other endpoint provisions one.",
      "",
      "  3. Teacher/Student account creation (POST /api/v1/teachers,",
      "     POST /api/v1/students) both require an authenticated Tenant Admin",
      "     (or narrower staff-permission) bearer token — same transitive block.",
      "",
      "This is a genuine backend/product gap (no bootstrap-admin API), not",
      "something this test-only seed script can work around without touching",
      "`backend/` — which is out of scope for this task. See",
      "frontend/e2e/README.md for the full write-up and suggested unblock",
      "paths (e.g. a dev-only bootstrap-admin endpoint or seed profile, gated",
      "out of production).",
    ].join("\n")
  );

  process.exitCode = 2; // Non-zero: seeding is INCOMPLETE, never claim success.
}

main();
