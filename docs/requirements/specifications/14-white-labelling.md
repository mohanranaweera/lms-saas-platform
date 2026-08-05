# White Labelling

**Domain:** `tenant-management` (Module 2) · **Portal(s):** Tenant Admin (config), all portals (consume)

## 1. Business purpose

Let each tenant institute present the LMS under its own brand identity (name, logo, colors,
templates), since this is a white-label SaaS platform.

Source: `docs/requirements/source-requirements.md` Module 2.

## 2. Actors

- **Tenant Admin / Institute Owner** — configures (`user-roles-and-permissions.md` §2 "Branding & settings" row: `V/C/E`)
- **Platform Admin** — plan-level white-label toggle
- **Student / Teacher / Public** — consume branding
- **Read-only Auditor** — view only

## 3. Preconditions

Tenant profile/plan already provisioned (Module 1, MVP); Feature Flag & Plan Limit Engine gating
whether white-label is enabled for the tenant's plan (ownership unratified — see Open Decisions);
branding preview must render through the same theming pipeline as the live site.

## 4. Normal flow

1. Tenant Admin opens `Branding Settings`.
2. Sets LMS name, logo, color theme, favicon, student/teacher portal branding, email templates, certificate/invoice branding.
3. Previews via `Branding Preview Panel`, rendered through the production theming pipeline.
4. On save, tenant brand colors are contrast-checked server-side (WCAG AA).
5. Branding resolves at runtime from tenant-scoped config for login page, storefront, and portals.

## 5. Alternative flows

- Plan lacks white-label entitlement: save/access blocked server-side.
- Brand color fails contrast check: flagged at configuration time, not silently accepted.
- Tenant cannot be unambiguously resolved from the incoming request: falls back to neutral platform-default branding, never a previously-cached or another tenant's branding.
- Client-side branding cache not keyed by `tenant_id`: cross-tenant branding leak risk in a browser session navigating between tenant subdomains or during impersonation — must be prevented by design.

## 6. Authorization rules

Only Institute Owner has `V/C/E` on "Branding & settings"; all staff sub-roles `—` except
Read-only Auditor `V`.

## 7. Tenant rules

All branding config is tenant-owned, resolved at runtime from tenant-scoped storage — never
hardcoded per build/environment. No cross-tenant aspect; platform-default branding is the only
non-tenant fallback state.

## 8. Acceptance criteria

- [ ] Branding config is tenant-scoped and resolvable at request/session time from subdomain/custom domain.
- [ ] Preview panel and live site share one theming pipeline (no separate preview-only render path).
- [ ] Server-side contrast validation runs at save time for tenant-supplied colors; failing colors are flagged, not silently stored.
- [ ] Cross-tenant negative test: Tenant A admin cannot read/write Tenant B's branding config.
- [ ] Plan without white-label entitlement gets a server-side 403 on branding-mutation endpoints, not just a hidden UI panel.
- [ ] Theme applies as CSS variable overrides on existing design tokens, not a full reskin/component fork.
- [ ] Light and dark mode both have a defined/derived variant for tenant brand colors.

## 9. Audit requirements

**Open Decision** — branding changes are not named in `.claude/rules/security.md`'s mandatory
audit-log action list; whether branding/theme changes require an audit entry is unresolved.

## 10. MVP or later-phase classification

**Phase 2.** `functional-requirements.md` FR-TM-5/FR-TM-6; `module-catalog.md` "Phase 2 (Module 2
branding/custom domain)"; `source-requirements.md` line 654.

## UI-state and portal notes

- **Portal placement**: Tenant Admin `Branding > Branding Settings`, `Branding Preview Panel`.
- Loading treatment while branding config fetches (flash-of-platform-default vs. blocking spinner) is unspecified.

## Open decisions

- Whether branding/theme changes require an audit-log entry.
- Module D (Feature Flag & Plan Limit Engine) ownership is unratified, and this feature's plan-gating depends on it.
- Exact loading-state treatment for branding config fetch.
