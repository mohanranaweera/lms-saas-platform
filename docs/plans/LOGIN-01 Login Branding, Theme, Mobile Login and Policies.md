# LOGIN-01 — Login branding, light/dark theme, mobile login and institute policies (Klass parity)

Status: **Implemented** (2026-10-30) — phases A, B, C. Deviation: teacher/staff sign-in mobiles are
set through dedicated `…/{id}/login-mobile` endpoints and a "Sign-in mobile" card/sheet rather than
fields inside the existing create/edit forms (avoids widening the teacher account record); admins set
their own number via `/auth/me/login-mobile` (Settings → General → Login options). Reference: `docs/references/Klass-Login/Login Page.png`.
ADR: `docs/adr/ADR-031-mobile-number-login.md` (**Accepted** 2026-10-29).

Owner decisions (2026-10-29): login mobile **unique per institute**; **every institute user** may have
one; tenant switch for **mobile login only** (email can never be switched off); policies are **plain
text**.

## Delivery phases (one logical commit each, backend before frontend inside a phase)

| Phase | Scope | Backend | Change-controlled |
|---|---|---|---|
| A | Institute logo on login/register; light/dark theme switch | none | no |
| B | Terms of Service, Refund Policy, Privacy Policy | config keys + public endpoint | no |
| C | Mobile-number login | migration, identity, user-management | **yes — ADR-031** |

## 1. Business goal

Make sign-in look and work like Klass: the institute's own logo instead of "LMS Platform", a
light/dark switch, an EMAIL / MOBILE login choice, and links to the institute's terms, refund and
privacy policies, which the institute can now write itself.

## 2. User roles

| Role | Interaction |
|---|---|
| Anonymous visitor | Sees the institute logo and theme switch on login/register; reads policy pages. |
| Student | Signs in by email or mobile; registration mobile becomes the sign-in mobile. |
| Teacher / staff | Sign in by email, or mobile once an admin sets their sign-in mobile. |
| Tenant Admin | Writes the three policies; switches mobile login on/off; sets teacher/staff sign-in mobiles. |
| Read-only Auditor | Views policies and login options read-only. |
| Platform Admin | Unchanged: platform-branded, email-only login. Gets the theme switch. |

## 3. Acceptance criteria

**A — logo and theme (frontend only)**

1. Tenant login, register, forgot/reset password and account setup show the institute logo (uploaded
   site logo, else legacy logo URL), linking home, with the institute name as alt text; with no logo,
   the institute name as text. "LMS Platform" never appears on a tenant auth page. Login subtitle:
   "Access your {institute} account".
2. While the institute is loading, a neutral skeleton is shown (never another tenant's or the
   platform's branding); if it can't be resolved, neutral platform text is used.
3. Platform Admin login keeps "LMS Platform — Platform Admin".
4. A sun/moon button (top-right on auth pages, in the public site header and in the signed-in app
   header) switches light/dark. The choice persists per browser (`localStorage`); default follows the
   OS. No flash of the wrong theme on load. Button has an `aria-label` naming the target theme and
   `aria-pressed`.
5. Dark mode works on dashboards (existing `.dark` tokens) and on the tenant public site: the tenant's
   primary/accent colours are kept, but its custom background/surface/text colours are replaced by dark
   tokens, and the primary is lightened if it falls below 3:1 against the dark background.

**B — policies**

6. New Settings tab **Policies** with three text areas (Terms of Service, Refund Policy, Privacy
   Policy), each ≤ 50,000 characters, blank = not published. Tenant Admin edits; Auditor reads.
7. Public pages `/terms`, `/refund-policy`, `/privacy` render the text as plain text (paragraphs and
   line breaks kept, never HTML). An unset policy shows "This institute hasn't published its … yet."
8. Login and register show "By using this service you agree to the Terms of Service and Privacy
   Policy" with links only for the policies that are set (sentence omitted if neither is). The public
   footer lists every published policy.

**C — mobile login (after ADR-031 approval)**

9. Login has EMAIL / MOBILE tabs (keyboard-accessible tablist); MOBILE is hidden when the tenant
   switched mobile login off. Mobile input uses `type="tel"`, `autocomplete="tel"`.
10. Mobile login accepts `0771234567`, `771234567`, `+94 77 123 4567`; wrong number or password →
    the same "Invalid email/mobile or password" message.
11. Over 10 failed attempts for one identifier, or 50 from one IP, within 15 min → "Too many attempts,
    try again later" (429). Applies to email too.
12. Registration: the mobile becomes the sign-in mobile; a number already used in the institute →
    field error "This mobile number is already registered". Same on student self-edit and admin
    student create/edit.
13. Teacher and staff admin forms have an optional "Sign-in mobile" with the same uniqueness rule.
14. Settings → General gains **Login options**: "Allow sign-in with mobile number" (default on) and
    "Phone number country" (default Sri Lanka).
15. Existing students get a sign-in mobile automatically where their profile mobile is valid and unique
    in the institute; others keep email login.

## 4. Database impact

- **A:** none.
- **B:** none — `GENERAL.terms_of_service`, `GENERAL.refund_policy`, `GENERAL.privacy_policy` in the
  existing `tenant_config_entry` (JSONB value).
- **C:** new migration `ALTER TABLE tenant_user ADD COLUMN login_mobile VARCHAR(16) NULL` + partial
  unique index `(tenant_id, login_mobile) WHERE login_mobile IS NOT NULL` (leading `tenant_id`); a
  one-off backfill from `student_profile.mobile` (valid + unique per tenant only). Config keys
  `GENERAL.mobile_login_enabled` (BOOLEAN, default true) and `GENERAL.phone_region` (STRING, ISO
  region, default `LK`). Designed with the `database-migration` skill; no applied migration is edited.

## 5. Backend impact

- **B:** `ConfigPropertyRegistry` (3 long-text keys, ≤ 50,000 chars); `PublicHomeResponse` gains
  `policies { termsOfService, refundPolicy, privacyPolicy }` as booleans (published or not); new
  anonymous `GET /api/v1/public/tenant-config/policies/{kind}` (`terms|refund|privacy`) returning
  `{ kind, text }` for the host tenant (text `null` when unset).
- **C:** identity — `LoginRequest` (optional `mobile`, exactly one identifier), `AuthenticationService`
  (lookup by `login_mobile`, throttling, `MOBILE_LOGIN_DISABLED`), `PhoneNumbers` normaliser
  (`libphonenumber`), `TenantUser.loginMobile`, repository finder, `UserProvisioningApi
  .setLoginMobile` (+ audit, `409 MOBILE_ALREADY_REGISTERED`). user-management — student registration,
  student self/admin edit, teacher and staff create/edit call `setLoginMobile` in the same transaction
  as the profile write. `PublicHomeResponse.loginOptions { mobileEnabled }`.

## 6. API contract (all additive)

| Endpoint | Change |
|---|---|
| `GET /api/v1/public/tenant-config/home` | + `policies` (3 booleans); + `loginOptions.mobileEnabled` (C) |
| `GET /api/v1/public/tenant-config/policies/{kind}` | new, anonymous, host-tenant only; `404` for an unknown kind |
| `GET/PUT /api/v1/tenant-config/GENERAL` | + 3 policy keys (B); + `mobile_login_enabled`, `phone_region` (C) |
| `POST /api/v1/auth/login` | body `{ email? , mobile?, password }`, exactly one identifier; new `400 MOBILE_LOGIN_DISABLED`, `429 TOO_MANY_REQUESTS` |
| student registration / profile / staff / teacher create-edit | mobile conflict → `409 MOBILE_ALREADY_REGISTERED` with field error on the mobile field |

## 7. Frontend impact

- **A:** `CenteredAuthShell` gains a `tenantBranding` mode (uses `usePublicHome` → `PublicLogo`) and a
  top-right `ThemeToggle`; `(auth)/layout.tsx` uses it, Platform Admin keeps the plain mode. New
  `lib/theme/theme-provider.tsx` (no dependency: class on `<html>`, `localStorage`, pre-paint inline
  script in `app/layout.tsx`), `components/theme/theme-toggle.tsx`; toggle added to `public-chrome`
  header and `dashboard-shell` header. `PublicThemeScope` / `lib/theme/color.ts` get a dark variant.
- **B:** Settings tab "Policies" (`settings/policies/page.tsx`); public pages `(public)/terms`,
  `(public)/refund-policy`, `(public)/privacy`; consent line component on login/register; footer
  links.
- **C:** `LoginForm` EMAIL/MOBILE tabs; register, student profile, admin student/teacher/staff forms map
  `MOBILE_ALREADY_REGISTERED`; General tab "Login options" section.

## 8. Security impact

- Policies: plain text only (React text rendering, no `dangerouslySetInnerHTML`); anonymous endpoint
  returns only those three public texts; edit is `BRANDING_SETTINGS/CREATE_EDIT` and audit-logged by
  the existing config mechanism.
- Theme: client-only preference, no data.
- Logo: from the existing allowlisted public projection.
- Mobile login (ADR-031): no enumeration (same error and timing as email), login throttling added for
  both identifiers (fail-open on Redis outage, logged), identity owns uniqueness and the audit entry,
  device limits and suspicious-login checks unchanged, number not OTP-verified (stated limitation).

## 9. Tenant impact

- Policies and login options are tenant config rows keyed by the resolved tenant; the public endpoints
  resolve the tenant from the host only and accept no tenant id.
- `login_mobile` is unique per `(tenant_id, …)`; lookup at login is scoped to the host tenant, so the
  same number in another institute is never matched. `setLoginMobile` checks only the caller's tenant.
- Rate-limit keys include the tenant id.

## 10. Tests

- **B backend:** registry validation; public policy endpoint per kind, unset → null, unknown kind 404;
  **cross-tenant**: tenant B's host never returns tenant A's text; Auditor PUT 403.
- **C backend:** normaliser unit tests (LK local forms, `+` foreign, invalid); login by mobile success,
  wrong password, unknown number (same error), INVITED/SUSPENDED, mobile disabled, throttling 429 and
  reset, email unaffected; **cross-tenant**: tenant A's number can't log in on tenant B's host and the
  same number may exist once per tenant; uniqueness 409 on registration/edit/staff; audit entry with
  masked numbers; backfill migration (valid+unique set, duplicates skipped); device limit still applies
  on mobile login.
- **Playwright:** logo/no-logo/loading on login and register; theme toggle persists across reload and
  pages, no hydration warning; dark public site; Policies tab save; policy pages and consent links;
  EMAIL/MOBILE tabs, hidden when disabled, 409 mapping on register, 429 message.

## 11. Documentation updates

`docs/adr/ADR-031-mobile-number-login.md` (status → Accepted on approval);
`docs/api/identity-access-service.md` (login contract, errors, throttling);
`docs/api/tenant-configuration-management.md` (new keys, policy endpoint);
`docs/specs/tenant-appearance-public-home-parity.md` (dark mode, policies, logo on auth pages);
`docs/parity/klass-parity-matrix.md`; this plan's status.
