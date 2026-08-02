# Tenant Onboarding

**Domain:** `tenant-management` (Module 1) · **Portal(s):** Platform Admin (approval), Public (registration entry point), Tenant Admin (post-approval)

## 1. Business purpose

Provision a new institute ("tenant") on the SaaS platform under Platform Admin control. The
product is multi-tenant and every downstream module depends on a resolved, approved tenant
identity existing first — this is the foundational flow the rest of the platform builds on.

Sources: `docs/requirements/source-requirements.md` Module 1; `docs/architecture/authentication-authorization.md` §2.

## 2. Actors

- **Prospective institute** — anonymous/public registrant (see Open Decision on registration model)
- **Platform Admin** — sole approver of tenant applications and status transitions
- **Tenant Admin / Institute Owner** — created upon approval; does not self-provision

## 3. Preconditions

- No conflicting existing tenant record for the registrant (uniqueness scope not formally specified — see Open Decisions)
- Platform Admin is authenticated with a platform-scoped role

## 4. Normal flow

1. Prospective institute submits registration (profile, contact info, requested plan) via a public entry point.
2. Tenant record is created in a pending-approval status.
3. Platform Admin views it in `Platform Admin > Tenants > Tenant List`, filterable by status.
4. Platform Admin opens `Tenant Approval`, reviews profile/contact/plan.
5. Platform Admin approves or rejects, server-side; tenant status (`trial`/`active`/`suspended`/`cancelled`) is backend-authoritative.
6. On approval, the backend provisions tenant-scoped config defaults (branding defaults, plan feature limits); Tenant Admin is notified and can log in via the tenant-specific login page.
7. Every approval/rejection/status-change action is audit-logged (actor, tenant, before/after status).

Sources: `docs/ui-ux/user-journeys.md` Journey 4; `docs/requirements/functional-requirements.md` FR-TM-1/2/3.

## 5. Alternative flows

- **Rejection**: tenant stays non-active; no login path is provisioned. Rejection-reason/re-application handling is unspecified (Open Decision).
- **Suspension/cancellation of an active tenant**: Platform-Admin-only, audit-logged, must immediately affect login/access (FR-TM-3).
- **Duplicate/conflicting registration** (same domain/subdomain requested twice): behavior unspecified (Open Decision).
- **Tenant-branding resolution failure** (e.g. unresolvable custom domain): storefront/login falls back to neutral platform-default branding, never a cached or another tenant's branding (`.claude/rules/ui-ux.md` §2).

## 6. Authorization rules

- Tenant approval/status-change is **Platform-Admin-only**; there is no tenant-scoped equivalent — this is a platform-level operation, not represented in the staff permission matrix at all.
- No other role may reach the approval queue or tenant list — platform-admin-only bypass paths require an explicit test proving only the Platform Admin role can reach them.

Sources: `docs/requirements/functional-requirements.md` FR-TM-3; `docs/requirements/module-catalog.md` (tenant-management row).

## 7. Tenant rules

- `tenant` is the root platform table that every tenant-owned table FKs to. It is itself platform-level, not tenant-owned.
- Listing pending tenants / the approval queue is inherently a cross-tenant, platform-level operation and needs an explicitly named bypass method per `.claude/rules/backend.md` (e.g. `findAllPendingTenantsForPlatformApproval`), not a normal `TenantAwareRepository` finder.
- Tenant resolution mechanism (subdomain/custom domain) is covered by the accepted ADR-002/ADR-006 baseline — no new ADR is needed if implementation follows it.

## 8. Acceptance criteria

- [ ] Given an unapproved tenant, when any user attempts to log in against that tenant's subdomain, then login is rejected server-side (not just hidden in UI).
- [ ] Given a Platform Admin approves a tenant, then tenant status flips atomically with provisioning of default branding/plan config, and exactly one audit row records the transition.
- [ ] Given a Tenant Admin of tenant A, when they attempt to read/modify tenant B's profile/config/plan/status, then the request is rejected 403/404.
- [ ] Given a non-Platform-Admin actor, when they attempt to reach the tenant approval queue or tenant list endpoint, then access is rejected server-side.
- [ ] Empty state: "no tenants awaiting approval" is distinguishable from "no tenants match filter."
- [ ] Every tenant-owned table (once a tenant exists) has `tenant_id NOT NULL` with a tenant-leading composite index.
- [ ] Tenant List rows each show tenant name/identifier; no approve/suspend action is submittable without the target tenant visibly named next to it.
- [ ] Cross-tenant negative test on tenant-list/approval endpoints (`docs/requirements/module-catalog.md` line 253).

## 9. Audit requirements

Every approval/rejection/status-change action is audit-logged: actor, tenant, before/after status.
Note: tenant approval/status-change is **not** itself named in `.claude/rules/security.md`'s canonical
mandatory-audit-action list (price changes, payment approvals, device resets, access/expiry
extensions, reactivation approvals, content deletions, settlement changes, impersonation) — this
audit obligation is sourced from `functional-requirements.md`/`user-journeys.md` instead. Flagged
so it isn't dropped during implementation.

## 10. MVP or later-phase classification

**MVP.** `docs/requirements/source-requirements.md` §5 MVP list includes "Tenant management";
`functional-requirements.md` FR-TM-1/2/3 are tagged MVP. FR-TM-4 (plan-limit enforcement) is
"MVP core, Phase 2 enforcement UI." Branding/custom domain (FR-TM-5/6) are Phase 2 — see
[14-white-labelling.md](./14-white-labelling.md) and [15-custom-domains.md](./15-custom-domains.md).
Architecturally correct as first-built: `tenant-management` is a foundational module per
`.claude/rules/architecture.md` — every other domain depends on it.

## UI-state and portal notes

- **Portal placement**: Platform Admin `Tenants > Tenant List` / `Tenant Approval`; Tenant Admin gains access to `Public > Auth > Tenant Login` only after approval.
- **Empty states**: "no tenants awaiting approval" vs. "no tenants match filters" must use distinct copy.
- **Accessibility**: Tenant List is an admin-heavy data table — needs card-view fallback or sticky-column horizontal scroll below `md`. Approve/reject icon controls need specific `aria-label`s.

## Open decisions (see `docs/requirements/open-decisions.md` for full tracking)

- Whether tenant self-registration is a public unauthenticated form or an invite-only flow initiated by Platform Admin outreach.
- No rejection-reason/notification workflow, duplicate-registration handling, or re-application flow is specified anywhere.
- Uniqueness scope for tenant subdomain/custom domain (implied global uniqueness, never explicitly stated as a constraint).
