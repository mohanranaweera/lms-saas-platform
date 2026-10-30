# ADR-031: Mobile-number login for institute users

- Status: **Accepted** — approved by the project owner on 2026-10-29 (Klass login parity; plan
  `docs/plans/LOGIN-01 Login Branding, Theme, Mobile Login and Policies.md`).
- Change-controlled areas touched: **authentication architecture** (a second login identifier),
  **database migration history** (new column + backfill; additive only, no edit of applied
  migrations).

## Context

Klass lets a user sign in with a mobile number or an email address (EMAIL / MOBILE tabs on the login
page). Here, `POST /v1/auth/login` accepts only `email` (`tenant_user.email`, unique per tenant).
Student mobile numbers live in `student_profile.mobile` (user-management): free text,
`VARCHAR(50)`, **not unique** — siblings often share a parent's number. identity-access-service is a
foundational module and must not read user-management tables. Login is currently not throttled at
all (no app or Nginx rate limit on `/v1/auth/login`).

Owner decisions (2026-10-29): a login mobile is **unique per institute**; **every institute user**
may have one (Platform Admin stays email-only); a tenant can switch **mobile login off** (default on);
**email login can never be switched off** (so no admin can be locked out).

## Decision

1. **Identifier lives in identity.** New nullable `tenant_user.login_mobile` holding the number in
   E.164 form (`+94771234567`), with a partial unique index `(tenant_id, login_mobile) WHERE
   login_mobile IS NOT NULL`. `tenant_id` leads the index. No cross-tenant meaning: the same number
   can belong to one user in each of several institutes.
2. **Normalisation** uses Google `libphonenumber` (new Maven dependency) with the tenant's default
   region (new `GENERAL.phone_region`, default `LK`) for numbers written without a country code
   (`0771234567`, `771234567`); `+…` numbers keep their own country. Only numbers that parse as valid
   mobile/fixed-line-or-mobile numbers are stored. The same function normalises at write time and at
   login time.
3. **Login contract (additive).** `LoginRequest` gains an optional `mobile`; exactly one of `email` or
   `mobile` must be present (else `400`). Mobile login looks up `login_mobile` in the resolved tenant
   only, runs the same constant-time password check against a dummy hash when no user matches, and
   fails with the **same** `INVALID_CREDENTIALS` response as a wrong password (no account
   enumeration). Everything after the user is resolved (INVITED/SUSPENDED handling, device binding
   and limits, suspicious-login logging, tokens) is unchanged — one code path for both identifiers.
4. **Tenant switch.** `GENERAL.mobile_login_enabled` (default `true`). When `false`, a mobile login
   is refused with `400 MOBILE_LOGIN_DISABLED` (no account information). Email login has no switch.
5. **Throttling (both identifiers).** Failed logins are counted in Redis via the existing
   `AuthRateLimiter`: per (tenant, normalised identifier) 10 failures / 15 min and per (tenant, client
   IP) 50 failures / 15 min; over either limit → `429 TOO_MANY_REQUESTS` before any password check.
   A successful login resets the identifier counter. This also changes email login (it had none).
6. **Who sets the login mobile.** Through a new `UserProvisioningApi.setLoginMobile(userId, raw)`
   (identity owns the write, the uniqueness check and the audit entry):
   - student self-registration and admin student create/edit: the profile `mobile` is also the login
     mobile; a number already used by another account in the institute → `409
     MOBILE_ALREADY_REGISTERED` (the profile is not saved);
   - a student editing their own mobile: same rule;
   - teachers and staff: a "Sign-in mobile" field on the admin teacher/staff forms.
   Every set/change/clear writes an audit entry (`identity.login_mobile_changed`, actor, target user,
   before/after masked to the last 3 digits).
7. **Backfill.** A one-off migration normalises existing `student_profile.mobile` values and sets
   `login_mobile` only where the result is valid **and** unique within the tenant. Duplicates and
   unparseable values are skipped (counted in the migration log, numbers not logged); those students
   keep email login until an admin sets a unique number.
8. **Unchanged:** password reset stays email-based; Platform Admin login stays email-only; tokens,
   refresh, device policy and tenant resolution are untouched.

## Consequences

- Students can sign in the way they do on Klass; institutes control it with one switch.
- A new class of conflict (`MOBILE_ALREADY_REGISTERED`) appears in registration and profile edits;
  siblings sharing a number must use different numbers or email login.
- Registration's 409 reveals that a number is registered in that institute, matching the existing
  behaviour for an already-registered email. Login itself never reveals it.
- New dependency (`libphonenumber`, Apache-2.0, no network access).
- Login gains throttling, which also hardens email login; Redis being unavailable must not lock users
  out — the limiter fails open (logged), as the existing `AuthRateLimiter` does.
- OTP/SMS verification of the number is **not** part of this ADR; a number is trusted as entered by
  the student or an admin (as `student_profile.mobile` is today). Mobile OTP or password reset by SMS
  would need its own ADR.
