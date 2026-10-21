# ADR-021: Tenant Custom Domains

## Status

**Accepted (2026-09-30)** by the project owner ("ADR-021 approved"), together with the plan
`docs/plans/TEN-DOM-01 Tenant Custom Domains.md`. Phase 2's TLS option (decision 6) is
confirmed when the hosting provider is chosen.

This ADR changes two change-controlled areas in root `CLAUDE.md`:
- the **multi-tenancy strategy**, because the tenant can now be resolved from a hostname the
  tenant owns, not only from a platform subdomain;
- the **production deployment strategy**, because phase 2 adds per-tenant TLS certificates.

## Context

- **How the tenant is found today:** only from the Host subdomain, `<subdomain>.<platform-domain>`,
  in `TenantResolutionFilter` through `TenantLookupApi.resolveBySubdomain` (ADR-006).
- **What's requested:** the source requirements list "Custom domain support" and a per-plan
  "Custom domain enabled/disabled" switch (`docs/requirements/source-requirements.md`).
  Institutes want their own address, for example `learn.abcinstitute.lk`.
- **What's still open:** `docs/architecture/deployment-architecture.md` §6 leaves the domain/DNS
  provider and per-tenant TLS undecided.
- **What builds links from the subdomain today** (`{subdomain}` templates):
  - email links (AUTH-PWD-01);
  - PayHere return URLs;
  - media OAuth return URLs;
  - the CORS allow-list.

## Decision

1. **Custom domains belong to a tenant, not a teacher.** A tutor who wants their own domain
   registers as their own tenant. A tenant may have several domains (limit set in config,
   default 3) and one **primary**. The platform subdomain always keeps working.
2. **Proof of ownership by DNS.** The tenant creates:
   - `CNAME <host> → <platform tenant target>`;
   - `TXT _lms-verify.<host> = <random code>`.

   Only after the backend sees both is the domain `ACTIVE`. A daily re-check moves a domain whose
   records have gone to `DNS_BROKEN`. Hostnames are unique across the whole platform.
3. **Resolution by host.** The filter keeps one path per request:
   - a host under the platform domain → by subdomain (unchanged);
   - any other host → looked up among `ACTIVE` custom domains only;
   - anything else fails closed (unknown tenant).

   This lookup is the one named cross-tenant read, following the ADR-011 carve-out pattern.
   Results are cached in Redis (a cache only) and evicted on every status change. Tenant identity
   still comes only from the host plus the authenticated token; a token issued for tenant A is
   still rejected on tenant B's host, whichever domain is used.
4. **Links and origins follow the primary domain.** A new `TenantLookupApi.primaryOrigin(tenantId)`
   replaces the `{subdomain}` URL templates, falling back to the subdomain. In production, CORS
   allows the platform domain plus `ACTIVE` custom hosts, and the API is served on the same host
   under `/api`, so the refresh cookie stays first-party and host-only (no cross-domain cookie
   sharing).
5. **Platform control.** The feature is off by default. It's turned on per tenant by a Platform
   Admin (later per plan). A Platform Admin can suspend any domain. Every change is audit-logged.
   Rows are never deleted.
6. **TLS (phase 2).** Certificates are issued automatically **only for `ACTIVE` domains**. The
   recommended option for the current single-VPS Docker Compose deployment is
   **Caddy with on-demand TLS** at the edge, in front of Nginx or replacing it. Caddy asks
   `GET /internal/tls/allowed?domain=`, which answers `200` only for active domains and is
   reachable only on the internal network. The final choice is confirmed when the hosting
   provider is chosen; the alternatives are listed below. Phase 1 ships without automatic
   certificates (HTTP locally, a manual or proxy certificate elsewhere).

## Consequences

- **Positive:** white-label addresses; ownership proven; unknown hosts refused; no per-tenant
  deployment (it stays a modular monolith with shared containers).
- **New moving parts:** DNS lookups from the backend (with timeouts and rate limits), a scheduled
  re-check, a Redis host cache, a dynamic CORS source, and in phase 2 a TLS edge with
  certificate storage.
- **Risk:** a tenant who lets its DNS lapse loses the custom address until it re-verifies. Users
  still reach it through the subdomain.
- **Risk:** certificate issuance depends on Let's Encrypt rate limits. The ask endpoint blocks
  abuse by unverified hosts.
- The deployment docs and runbook need a DNS guide for tenants.

## Alternatives considered

- **Cloudflare for SaaS (custom hostnames).** Cloudflare handles certificates and DDoS
  protection. It's a paid dependency and ties the edge to Cloudflare. It's a good choice if the
  platform moves to Cloudflare anyway.
- **Cloud load balancer with managed certificates** (AWS ALB/ACM, GCP). This needs a certificate
  per domain through an API and fits a cloud deployment rather than the current VPS.
- **Wildcard certificate only.** It can't cover domains the platform doesn't own. Rejected.
- **Custom domain per teacher.** It conflicts with the tenant boundary (one host resolves to one
  tenant). Rejected; the tutor becomes a tenant instead.
- **Trusting any host that has a CNAME to us, without the TXT check.** Anyone could point a
  domain at the platform and have a certificate issued. Rejected.
- **A separate deployment per tenant.** Forbidden by root `CLAUDE.md`.

## Required follow-up if accepted

- Implement TEN-DOM-01 phase 1: migration V63, the tenant-management service and API, the filter
  change, link builders, CORS, the Tenant Admin and Platform Admin pages, and the tests,
  including cross-tenant tests.
- Update `docs/architecture/multi-tenancy.md` and `deployment-architecture.md` §6, plus
  `docs/api` and the runbook.
- Phase 2: confirm the TLS option, add the TLS edge to Compose, and add the internal ask endpoint
  with a network restriction.

## Related

- ADR-006 (tenant isolation repository mechanism), ADR-011 (webhook tenant-resolution carve-out),
  ADR-017 (PayHere return URLs), ADR-018 (media OAuth return URLs), ADR-019 (production
  secrets and deployment), ADR-020 (email links).
- `docs/plans/TEN-DOM-01 Tenant Custom Domains.md`.

## Implementation notes (phase 1, 2026-09-30)

Phase 1 is built (plan `TEN-DOM-01` §13 and §14). Where it differs from the decision text above:

- **Decision 4, CORS:** no dynamic CORS was needed. Production has no CORS because every browser
  call is same-origin. The frontend's API base defaults to `/api` on the site's own address.
- **Forwarded host:** locally the Next dev server's rewrite proxies `/api` to the backend, and in
  production Nginx does. Either way the site's address arrives in `X-Forwarded-Host`.
  `TenantResolutionFilter` reads `request.getServerName()`, which Tomcat takes from that header
  only for trusted internal proxies (`server.forward-headers-strategy: native`, local and prod).
  In production the backend listens on `127.0.0.1` only.
- **Re-check:** a DNS lookup that times out changes nothing, and a `DNS_BROKEN` domain becomes
  `ACTIVE` again when its records return.
- **Configuration:** `PLATFORM_DOMAIN` and `CUSTOM_DOMAIN_CNAME_TARGET` are passed to the backend
  in `infrastructure/docker-compose.prod.yml`. `verification-mode: accept-all` exists for local
  development and is refused under `prod`.
- **Phase 2 (automatic TLS)** is not started.
