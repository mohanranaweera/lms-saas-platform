# TEN-DOM-01 — Tenant Custom Domains

**Status:** Approved (2026-09-30), with **ADR-021** (accepted). It changes the **multi-tenancy
strategy** (how the tenant is resolved) and, in phase 2, the **production deployment strategy**,
both change-controlled.

## 1. Business goal
An institute (or a tutor registered as their own tenant) can serve the LMS on its own domain,
for example `learn.abcinstitute.lk`, instead of `abc.<platform-domain>`: its own brand, and one
address for students. The platform subdomain keeps working as a fallback.

## 2. User roles
| Role | Interaction |
|---|---|
| Tenant Admin | Adds a domain, sees the DNS records to create, clicks **Verify**, chooses the primary domain, removes a domain. |
| Platform Admin | Turns the feature on for a tenant or plan, sees every tenant's domains and statuses, can suspend a domain. |
| Teacher, Student | Use the custom address; nothing else changes. A teacher inside an institute has no own domain. |

## 3. Acceptance criteria
1. A Tenant Admin (with the feature enabled) adds a hostname. It must be a valid lowercase DNS
   name, not under the platform domain, and not already claimed by any tenant (globally unique).
   It's stored as `PENDING_VERIFICATION` with a random verification code.
2. The page shows two records to create: `CNAME <hostname> → <tenant-target>` and
   `TXT _lms-verify.<hostname> = <code>`.
3. **Verify** checks DNS through the backend. Both records must match; if they don't, the page
   says which one failed. On success: `ACTIVE`, with `verified_at` recorded. The platform
   re-checks active domains daily; a domain whose records are gone becomes `DNS_BROKEN` and stops
   resolving (the subdomain still works).
4. Tenant resolution:
   - a host under the platform domain resolves by subdomain (unchanged);
   - any other host resolves only if it is an `ACTIVE` custom domain;
   - anything else is refused as an unknown tenant.

   Lookups are cached in Redis and invalidated on any status change.
5. One active domain can be marked **primary**. Email links (invitation, reset), PayHere return
   links, OAuth (Zoom/YouTube/Vimeo) return links and CORS origins use the primary domain,
   falling back to the subdomain.
6. Removing or suspending a domain stops it resolving immediately. Rows are kept (history).
   Every add, verify, primary change, remove and suspend is audit-logged.
7. Up to 3 domains per tenant (config). The feature is off unless a Platform Admin or the plan
   enables it.
8. Phase 2: HTTPS certificates are issued automatically for `ACTIVE` domains only (see ADR-021).

## 4. Database impact
New migration `V63__create_tenant_custom_domain.sql`:
`tenant_custom_domain` — id, `tenant_id` NOT NULL FK, `hostname` (unique across all tenants),
`status` (`PENDING_VERIFICATION`/`ACTIVE`/`DNS_BROKEN`/`SUSPENDED`/`REMOVED`),
`verification_code`, `is_primary`, `verified_at`, `last_checked_at`, `last_error`, audit columns.
Also:
- a partial unique index: one primary per tenant;
- an index `(tenant_id, status)`;
- a global unique index on `hostname` among rows that aren't removed (a hostname belongs to one
  tenant at a time);
- `tenant.custom_domains_enabled BOOLEAN NOT NULL DEFAULT false`.

## 5. Backend impact (tenant-management owns it)
- **`CustomDomainService`:** add, verify, set primary, remove; Platform Admin enable and suspend.
- **`DnsVerifier`:** Java JNDI DNS lookups with a timeout, behind an interface so tests can use a
  fake.
- **Scheduled re-check** of active domains.
- **`TenantLookupApi`:**
  - `resolveByHost(host)`, used by `TenantResolutionFilter` (still owned by
    identity-access-service; it only switches from subdomain parsing to this call);
  - `primaryOrigin(tenantId)`, replacing the three `{subdomain}` URL templates (notifications,
    PayHere, media OAuth).
- **CORS in production:** a dynamic `CorsConfigurationSource` that allows the platform domain plus
  every `ACTIVE` hostname (cached). Serving the API on the same origin (`/api` behind the proxy)
  is recommended, so cookies stay first-party.
- **Phase 2:** `GET /internal/tls/allowed?domain=` for the TLS proxy (Caddy's "ask" hook). It
  answers `200` only for `ACTIVE` domains and is reachable only from the proxy network.

## 6. API contract (new)
- **`GET /api/v1/tenant-config/custom-domains`** → the tenant's domains:
  `{id, hostname, status, isPrimary, dnsRecords:[{type, name, value}], verifiedAt, lastError}`.
- **`POST /api/v1/tenant-config/custom-domains`** `{hostname}` → `201`.
  Errors: `400` invalid; `409` taken, or the limit is reached; `403` the feature is off.
- **`POST …/{id}/verify`** → `200`, with `status` and `lastError`.
- **`POST …/{id}/primary`**, **`DELETE …/{id}`** (a soft remove).
- **Platform Admin:** `PUT /api/v1/platform-admin/tenants/{id}/custom-domains-enabled`
  `{enabled}`, and `POST …/custom-domains/{domainId}/suspend` `{reason}`.

Permission: a new `DomainArea.CUSTOM_DOMAINS` (Tenant Admin CREATE_EDIT).

## 7. Frontend impact
- **Tenant Admin → Settings → Custom domain:** add form, a DNS records table with copy buttons,
  Verify, a status badge, primary and remove actions; empty, loading, error and permission-denied
  states.
- **Platform Admin tenant page:** an enable toggle and a domains list with suspend.
- **The frontend itself:** already host-agnostic (branding comes from the API), so nothing
  changes beyond these pages.

## 8. Security impact
- **Ownership:** proven by the TXT record, so nobody can claim someone else's domain. Hostnames
  are globally unique.
- **Resolution:** only `ACTIVE` domains resolve; everything else fails closed.
- **Certificates (phase 2):** issued only for verified domains, so random hosts pointed at the
  platform can't be used to exhaust certificate limits.
- **CORS and cookies:** origins come only from verified domains. Cookies stay host-only.
- **DNS lookups:** have timeouts and are rate-limited per tenant.
- **Audit:** all domain changes are logged.

## 9. Tenant impact
- `tenant_custom_domain` is tenant-owned (`TenantAwareRepository`, tenant-leading index).
- The resolution lookup by host is a deliberate, named cross-tenant read (it has to find which
  tenant owns a host), like the ADR-011 carve-out.
- A tenant can only list and manage its own domains; another tenant's domain id returns `404`.

## 10. Tests
- **Backend:**
  - hostname validation;
  - add / verify (fake DNS: success, missing CNAME, wrong TXT);
  - resolution: subdomain, active custom domain, pending/suspended/unknown host refused, cache
    invalidated;
  - primary-origin fallback used in email, PayHere and OAuth links;
  - daily re-check → `DNS_BROKEN`;
  - feature switched off → `403`;
  - **cross-tenant:** a hostname taken by another tenant → `409`; another tenant's domain id →
    `404`; a request on tenant A's custom domain never resolves tenant B;
  - migration constraints.
- **Frontend (Playwright):** the settings page states, the DNS records shown, verify
  success/failure, primary/remove; the Platform Admin toggle and suspend.
- **Local:** `127.0.0.1 learn.abc.test` in the hosts file with a fake DNS verifier.

## 11. Documentation updates
- ADR-021;
- `docs/architecture/multi-tenancy.md` (resolution by host);
- `deployment-architecture.md` (TLS option);
- `docs/api` (tenant-config, platform-admin);
- the runbook (DNS guide for tenants);
- `screen-map.md`.

## 12. Phasing
- **Phase 1:** everything except certificates. Locally over HTTP; in production behind a proxy
  whose TLS is handled per ADR-021's choice.
- **Phase 2:** automatic TLS once the hosting option is chosen.

## 13. Implementation notes: phase 1 backend (2026-09-30)

**What was built**
- Migration `V63__create_tenant_custom_domain.sql`: the table, the `tenant.custom_domains_enabled` column, and the constraints and indexes from §4. It also adds a `suspended_reason` column, which must be filled while a domain is SUSPENDED.
- The tenant is now resolved by `TenantLookupApi.resolveByHost(host)`, which `TenantResolutionFilter` calls:
  - a one-label subdomain of `PLATFORM_DOMAIN` resolves by subdomain;
  - any other host resolves only as an ACTIVE domain of a tenant with the feature switched on;
  - a multi-label subdomain of the platform domain resolves to nothing.
- If `PLATFORM_DOMAIN` is empty, the old parsing applies (first label of any host) and custom domains never resolve.
- The Redis cache stores hostname → tenant id, plus "no such domain" for 60 s. The tenant row itself is always read fresh, and every status change evicts the cache entry after its transaction commits.
- Links use `TenantLookupApi.tenantOrigin(tenantId, template)`. It swaps the host in the existing templates and keeps their scheme and port. Email links, the PayHere return link and the media OAuth return link now use the primary custom domain when there is one.
- The new permission area `CUSTOM_DOMAINS`: Tenant Admin can view and edit, Read-only Auditor can view.

**Differences from the plan**
- **CORS:** no backend change. Production has no CORS at all, because the API is same-origin behind the proxy, so a dynamic CORS source isn't needed.
  - Locally, the frontend currently calls a fixed `http://demo.lms.test:8080/api`.
  - The **frontend step must make API calls same-origin** (`/api` on the page's own host, proxied to the backend), or a custom domain can't reach its own tenant.
- **Response shapes:**
  - `GET /tenant-config/custom-domains` returns `{enabled, available, maxDomains, domains[]}`.
  - Platform Admin also gets `GET /platform-admin/tenants/{id}/custom-domains`.
  - `TenantDetailResponse` gains `customDomainsEnabled`.
- **Re-check:** a DNS lookup that times out changes nothing. A DNS_BROKEN domain becomes ACTIVE again when its records return.

**Configuration** (none of these are secrets)

| Setting | Environment variable | Local value | Test value |
|---|---|---|---|
| `app.custom-domains.platform-domain` | `PLATFORM_DOMAIN` | `lms.test` | `lms.test` |
| `app.custom-domains.cname-target` | `CUSTOM_DOMAIN_CNAME_TARGET` | `tenants.lms.test` | `tenants.lms.test` |
| `verification-mode` | (none) | `accept-all` (refused under `prod`) | `dns` with a stubbed lookup |

**Follow-ups**
- Add `PLATFORM_DOMAIN` and `CUSTOM_DOMAIN_CNAME_TARGET` to `infrastructure/docker-compose.prod.yml` and the ADR-019 reference (an infrastructure change).
- Add a Platform Admin "reinstate" action for a suspended domain. It isn't in the approved API.
- Sessions are host-only: after a primary domain change, users sign in again on the new address.
- The DNS lookup uses JNDI (`jdk.naming.dns`). A trimmed runtime image must include that module.

## 14. Implementation notes: frontend and reviews (2026-09-30)

**Frontend**
- **Tenant Admin → Settings → Custom domain** (`settings/domains`); the Platform Admin tenant
  detail page gains a Custom domains card.
- API calls default to `/api` on the site's own address (`NEXT_PUBLIC_API_BASE_URL`), and
  `next.config.ts` rewrites `/api` to `BACKEND_ORIGIN`. A developer's `.env.local` that still
  sets an absolute URL must change to `/api`.
- **Backend changes made in this step:**
  - `TenantResolutionFilter` reads `getServerName()`, which is proxy-aware (approved);
  - the local profile uses the `native` forward-headers strategy;
  - `DELETE /custom-domains/{id}` returns `200` with `data: null`, the project's DELETE
    convention, instead of `204`.
- **Tests:**
  - Playwright `e2e/custom-domains.spec.ts` (10);
  - `ForwardedHostResolutionIntegrationTest` (real Tomcat, 3);
  - `TenantResolutionFilterForwardedHostTest` (2).

**Reviews**
- **Security:**
  - The forwarded-host trust can't be abused from outside, because the backend is bound to
    `127.0.0.1` in production.
  - Fixed: prod Compose didn't pass `PLATFORM_DOMAIN` or `CUSTOM_DOMAIN_CNAME_TARGET` to the
    backend, so the feature could never work there. It was safe but inert.
  - Accepted (low):
    - `409 CUSTOM_DOMAIN_TAKEN` shows that a hostname is in use, which public DNS already shows;
    - a lapsed domain whose new owner restores the old CNAME and TXT records would serve the old
      tenant again (it only affects the new owner);
    - the Next rewrite defaults to `localhost:8080`, which doesn't matter behind Nginx.
- **Tenant isolation:**
  - Every tenant-scoped query goes through `TenantAwareRepository`. The cross-tenant reads are
    named `…AcrossTenants`, and their input comes from the host or from server-held ids.
  - Cross-tenant tests cover a hostname claimed by another tenant (`409`), another tenant's
    domain id (`404` for every action), and B's token on A's domain (refused).
  - The platform-wide unique index on `hostname` is deliberate.
- **UI:** fixed three small issues:
  - a failed verify repeated the error text twice;
  - a primary domain with missing records now says links use the platform address meanwhile;
  - the "Copied" timer is cleaned up on unmount.
