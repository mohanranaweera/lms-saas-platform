# Runbook: Teacher Storefront DNS and TLS (ADR-037)

Nothing here is applied automatically; DNS and certificates are changed by a person.

## Local development
Hosts files have no wildcards. Add one line per storefront you want to browse:

    127.0.0.1 alice.demo.lms.test

then open `http://alice.demo.lms.test:3000`. `next.config.ts` already allows `**.test` dev
origins. Playwright specs use `--host-resolver-rules=MAP *.lms.test 127.0.0.1` instead.

## Production prerequisites
- DNS: a wildcard record per tenant, `*.<tenant>.<PLATFORM_DOMAIN>`, pointing at the edge
  (a single `*.<PLATFORM_DOMAIN>` wildcard does not cover two labels).
- TLS: a certificate per tenant for `*.<tenant>.<PLATFORM_DOMAIN>` (ACME DNS-01), or the
  ADR-021 phase-2 on-demand TLS with its ask endpoint extended to answer 200 only for enabled
  storefront hosts. Choose with the hosting provider.
- Nginx: `server_name` must accept the two-label shape and keep forwarding the host in
  `X-Forwarded-Host`; the backend trusts it only from internal proxies.
- No cookie domain change: sessions stay host-only.

## Turning a storefront off
Disable it in Tenant Admin -> Teacher storefronts. Its host answers "Storefront not found" at
once; students keep their courses on the tenant site. The slug stays reserved for that teacher.
