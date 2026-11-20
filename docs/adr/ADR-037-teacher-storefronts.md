# ADR-037: Teacher Storefronts

## Status

**Accepted (2026-11-20)** by the project owner ("Approved, full-stack implementation approved,
use explicit product table"), with plan `docs/plans/STORE-01 Teacher Storefronts.md`.

Change-controlled areas touched (root `CLAUDE.md`):
- **multi-tenancy strategy** - a new host shape `<teacher>.<tenant>.<platform-domain>` is
  resolved (extends ADR-021 decision 3);
- **authentication architecture** - sign-in/registration run on the new host shape, with
  host-only sessions;
- **approved API contracts** - additive storefront scoping on the public catalog and checkout;
- **production deployment strategy** - documented TLS prerequisite only (nothing deployed);
- **orders** - a nullable attribution column on `student_order_line` (not a ledger change).

## Decision

1. **A storefront is a view of the tenant, not a tenant and not a role.** It belongs to one
   teacher (a `tenant_user`), is off by default, and is enabled, renamed and stocked only by
   holders of the new `TEACHER_STOREFRONT_MANAGE` capability (Tenant Admin by default;
   configurable for Operator/Manager; never granted to Teacher by default). A teacher editing
   their own profile (photo, bio) gains no storefront rights.
2. **Host shape and resolution.** Only `<slug>.<tenant-subdomain>.<PLATFORM_DOMAIN>` - never
   under a custom domain. `TenantResolutionFilter` resolves the tenant from the second label
   first (unchanged rules, status checks, fail-closed), then the storefront from the first label
   within that tenant, and sets a request-scoped `StorefrontContextHolder`. Tenant identity
   still comes only from host + token; the storefront never selects a tenant. The host comes
   from `getServerName()` (trusted-proxy `X-Forwarded-Host` only), as in ADR-021.
3. **Unknown, disabled or retired hosts never fall back to the tenant catalog.** A storefront whose teacher is suspended or unapproved counts as disabled. Every `/api`
   call on such a host gets `404 STOREFRONT_NOT_FOUND`, except a retired slug of an enabled
   storefront, which gets `404 STOREFRONT_MOVED` with the current host so the site can 308.
4. **Slug rules.** `[a-z0-9-]`, 3-40 chars, no leading/trailing/double hyphen, not reserved
   (`www, api, admin, app, mail, login, static, cdn, assets, platform, auth, student, teacher,
   tenant, support, help, status, docs, blog`). Unique per tenant **forever**: every slug used is
   kept in `teacher_storefront_slug` and is never reassigned to another teacher (retirement
   rule). The two-label host cannot collide with a tenant subdomain (one label) or an ACTIVE
   custom domain (custom domains under the platform domain are refused by ADR-021).
5. **Explicit product list.** `teacher_storefront_product` lists the courses a storefront sells.
   Its composite FK to `course_teacher_assignment` restricts listing to courses the teacher is
   assigned to; a shared course is listed per storefront explicitly. Ownership is never inferred
   from subject or title.
6. **Server-side scoping.** With a storefront context, public course list/search/counts/
   pagination/detail and checkout quote/order accept only published listed courses that are
   also visible on the tenant catalog (tenant merchandising rules still apply). Out-of-scope
   detail = 404; out-of-scope quote/order = 422 `STOREFRONT_PRODUCT_OUT_OF_SCOPE`. The signed-in
   student portal follows the same rule on a storefront host: Browse (`/enrollments/my/catalog`,
   view AVAILABLE) lists only the storefront's courses and a new free self-enrollment outside it
   is refused (422). What a student already joined or bought (ENROLLED view, My courses,
   re-enrolling) is never filtered. The tenant host still shows the whole institute.
7. **Sessions.** The API is same-origin per host, and the refresh cookie stays host-only, so a
   storefront host has its own session against the same tenant account. No cookie widening,
   no cross-host handoff. `returnTo` accepts only a same-host relative path.
8. **Checkout and payments are unchanged.** Order lines record `storefront_id` (attribution
   only: no effect on course ownership, commission, settlement or ledger). The PayHere return
   URL uses the storefront origin when the order's storefront is still enabled, otherwise the
   tenant origin. Callback validation and idempotency are untouched.
9. **Entitlements survive.** Disabling a storefront or unlisting a course never touches
   enrollments or entitlements; students keep access through the tenant site.
10. **Caching.** Host -> (tenant, storefront) resolution is read fresh (or cached keyed by full
    host and evicted on every storefront change); public catalog responses vary by host.

## TLS / DNS prerequisites (not deployed)

- Local: one hosts-file line per storefront (`127.0.0.1 alice.abc.lms.test`); hosts files have
  no wildcards. Browse `http://alice.abc.lms.test:3000`.
- Production: `*.<platform-domain>` does not cover two labels. Needs a wildcard DNS record
  `*.<tenant>.<platform-domain>` plus a certificate per tenant (`*.<tenant>.<platform-domain>`,
  ACME DNS-01), or ADR-021 phase-2 on-demand TLS with the ask endpoint extended to enabled
  storefront hosts. Nginx `server_name` must accept the two-label shape. Decided with the
  hosting provider.

## Alternatives rejected

- Path-based storefronts (`tenant/t/alice`) - simpler TLS, but not the requested host shape.
- Inferring the catalog from every assigned course - silently publishes shared courses.
- Cross-host session handoff - unnecessary given host-only same-origin sessions.
