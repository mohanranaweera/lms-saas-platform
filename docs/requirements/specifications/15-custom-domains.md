# Custom Domains

**Domain:** `tenant-management`, resolution mechanism jointly owned with `identity-access-service` · **Portal(s):** Tenant Admin (config), Public (resolution)

## 1. Business purpose

Allow a tenant to serve the LMS under its own domain (BYOD) rather than only a platform
subdomain.

Source: `docs/requirements/source-requirements.md` line 34.

## 2. Actors

- **Tenant Admin** — configures domain
- **Platform Admin** — no defined approval role (see Open Decisions)
- **Public visitors / Students** — tenant resolved via the domain at request time

## 3. Preconditions

Tenant profile with domain/subdomain field (Module 1); tenant-identity resolution at the
auth/edge layer must support custom-domain resolution the same way it supports subdomain
resolution; DNS/TLS certificate provisioning approach is an explicit unresolved open question.

## 4. Normal flow

1. Tenant Admin enters desired custom domain in Branding Settings.
2. System provides domain-ownership verification instructions (mechanism unspecified).
3. Once verified, backend attaches the domain to the tenant and provisions TLS (automation mechanism unspecified).
4. Requests to that domain resolve tenant context at the edge exactly as subdomain resolution does.
5. Tenant-specific login/public pages resolve branding strictly from the domain-matched tenant.

## 5. Alternative flows

- Verification fails/times out: tenant stays on default subdomain, no partial activation.
- Domain already claimed by another tenant: rejected.
- TLS provisioning failure: domain not activated.
- Tenant cannot be unambiguously resolved from an incoming domain: falls back to neutral platform-default branding, never cached/other-tenant branding.
- Plan lacks custom-domain entitlement: blocked.

## 6. Authorization rules

Same as White Labelling — Institute Owner only, per "Branding & settings" row.

## 7. Tenant rules

Custom domain → tenant resolution must go through the same trusted, single resolution point as
subdomain resolution. This is a **change-controlled mechanism** if deviated from — the resolution
*point* stays the same (edge layer), but the mechanism for matching a custom domain to a tenant
should be explicitly confirmed as staying inside that same foundational layer before
implementation.

## 8. Acceptance criteria

- [ ] A custom domain, once verified and active, resolves tenant context server-side identically to subdomain resolution — no separate/parallel tenant-resolution code path.
- [ ] Domain claimed by one tenant cannot be attached to a second tenant.
- [ ] No content/login is served under a custom domain before verification completes.
- [ ] Cross-tenant negative test: requesting Tenant B's data while resolved as Tenant A (or vice versa) via domain mismatch is rejected.
- [ ] Fallback-to-default-branding behavior is tested when domain-to-tenant resolution is ambiguous/fails.

## 9. Audit requirements

**Open Decision** — whether enabling/disabling a custom domain or changing DNS/TLS config is
audit-logged is unresolved.

## 10. MVP or later-phase classification

**Phase 2.** FR-TM-5; `source-requirements.md` line 655.

## Change control flag

Recommend a lightweight ADR or explicit sign-off confirming custom-domain matching is added to
the existing edge-resolution mechanism, not a new/parallel one — no ADR currently names this
specific mechanism.

## UI-state and portal notes

- **Portal placement**: same screen as White Labelling — `Branding Settings`.
- No domain-verification UI flow is documented anywhere.

## Open decisions

- Concrete DNS/TLS automation mechanism and domain-ownership verification approach — not decided anywhere (`docs/architecture/deployment-architecture.md` §6).
- Whether Platform Admin must approve/verify a tenant's custom domain.
- Whether enabling/disabling a custom domain is audit-logged.
