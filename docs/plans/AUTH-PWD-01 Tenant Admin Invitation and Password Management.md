# AUTH-PWD-01 — First Tenant Admin Invitation, Forgot Password, Change Password

**Status:** Approved and implemented (2026-09-30, "ADR-020 approved"); see ADR-020's implementation notes for how the build differs. This plan changes the **authentication
architecture**, a change-controlled area in root `CLAUDE.md`: a new, unauthenticated way to set a
password, a new token type, and a new access-token claim, recorded in **ADR-020** (accepted).

**Owner decision (2026-09-30):** scope = invite the first Tenant Admin **+ forgot password + change
password (with `mustChangePassword` enforced)**.

Builds on MVP-002 (which explicitly deferred forgot/reset password, leaving the placeholder
`app/(auth)/forgot-password/page.tsx`) and respects MVP-018's rule that a raw reset token must never
be stored in `notification_outbox.payload`.

---

## 1. Business goal

Three gaps block real use of the platform:

1. **A newly approved tenant has no way in.** Registration and approval work, but nothing creates the
   tenant's first Tenant Admin. Every other account-creating screen needs an existing Tenant Admin.
   Today this is done by hand in the database.
2. **Nobody can recover a forgotten password.** There's no "Forgot password?" flow.
3. **Nobody can change their password.** Staff, teachers and students created with a temporary
   password get `mustChangePassword = true`, but no endpoint lets them change it and nothing forces
   them to. Temporary passwords given out by admins stay in use forever.

After this module:
- Approving a tenant creates its Tenant Admin from the registration's contact email and emails a
  one-time "set your password" link.
- Any tenant user can reset a forgotten password by email.
- Signed-in users can change their password.
- Accounts flagged `mustChangePassword` must change it before doing anything else.

## 2. User roles

| Role | Interaction |
|---|---|
| Prospective institute owner | Registers the institute (unchanged). After approval, receives the invitation email, sets a password, becomes the **Tenant Admin**. |
| Platform Admin | Approves the tenant (now also invites its admin). Sees the first admin's status on the tenant page (Invited / Active / None). Can **resend** the invitation, or **correct the email** while it's still pending. Can invite an admin for an older tenant that has none. |
| Tenant Admin, Teacher, Student, staff roles | "Forgot password?" on their institute's login page. "Change password" in their account. Forced to change a temporary password on first sign-in. |
| Platform Admin (own account) | **Out of scope.** Platform Admin accounts keep their current handling; forgot/change password for them is a follow-up. |

## 3. Acceptance criteria

### A. First Tenant Admin invitation
1. Approving a `pending_approval` tenant, in **the same transaction**, sets it to `trial` and creates
   a `tenant_user` with:
   - role `TENANT_ADMIN`, status **`invited`**, email = the registration's `contact_email`;
   - an unusable random password hash (nobody can log in with it).
   If creating the user fails, the approval rolls back.
2. After commit, an **account-setup email** is queued through the notification outbox and sent (local:
   visible in Mailpit). It contains a link `{tenant origin}/account-setup?token=…`, valid for **72
   hours**, usable **once**.
3. An `invited` account cannot log in. The login response is identical to "wrong email or password"
   (no account-state disclosure).
4. The Platform Admin tenant page shows the first admin's status: **Invited** (with sent time and
   expiry), **Active**, or **None**.
5. **Resend** (`invited` only) issues a new link, invalidates every earlier unused one, and queues a
   new email. It is limited to one per minute per tenant (`429`).
6. **Correct the email** (`invited` only) updates the invited account's email, invalidates old links,
   and sends a new one. It's not allowed once the account is `active` (`409`), and the email must be
   unique in the tenant (`409`).
7. **Invite an admin for a tenant that has none** (a `trial`/`active` tenant with no `TENANT_ADMIN`,
   e.g. created before this module) works like approval, using the registration contact email or a
   supplied one. It's not allowed if any `TENANT_ADMIN` already exists (`409`).
8. Every invitation, resend and email correction is audit-logged under the tenant, with the Platform
   Admin as actor (`auditLogApi.recordForTenant`). Emails appear only masked in the metadata.

### B. Completing a link (account setup and password reset share this)
9. `/account-setup?token=…` and `/reset-password?token=…` first **verify** the token.
   - Valid: the page shows the masked email and the expiry.
   - Invalid, expired, used, or belonging to another tenant: one generic message ("This link is
     invalid or has expired") with a way to request a new one.
10. Submitting a new password (8–128 characters, not equal to the email, confirmation must match):
    - sets the Argon2 hash;
    - `invited` → `active` (account setup);
    - clears `mustChangePassword`;
    - records `password_changed_at`;
    - marks the token used and invalidates the user's other unused tokens;
    - **revokes all of the user's existing sessions**;
    - writes an audit entry (`auth.account_setup_completed` / `auth.password_reset_completed`).

    The user is then sent to the login page. There is **no automatic sign-in**.
11. A token can be completed **once**. A second attempt, including two concurrent requests, gets the
    generic invalid-link response. Enforced with a conditional update, not just a check.
12. The raw token appears **nowhere** except the email: not in the database (SHA-256 only), not in
    logs, not in audit entries, not in the outbox payload. The frontend removes it from the address bar
    right after reading it, and the pages send no `Referer`.

### C. Forgot password
13. "Forgot password?" on the tenant login page → email form → **always the same response** ("If an
    account exists for this email, we've sent a link"), with the same status and similar timing,
    whether or not the account exists (no enumeration).
14. Only an **`active`** account in **this tenant** gets an email: a reset link valid for **60
    minutes**. An `invited` account gets a fresh **account-setup** link instead. A `suspended`
    account gets nothing.
15. Rate limits (Redis, per tenant): 5 requests/hour per IP and 3/hour per email. Over the limit →
    still the same generic response, with no email sent and a security-log entry.
16. A new reset request invalidates the user's earlier unused reset links.

### D. Change password (signed in)
17. "Change password" requires the **current password** and a new one (same rules as #10, and not
    equal to the current one).
    - Wrong current password → `400` on `currentPassword`. Five failures in 15 minutes lock
      the action for 15 minutes (`429`).
    - Success: new hash, `mustChangePassword = false`, `password_changed_at`, **all other sessions
      revoked** (the current one stays), audit entry `auth.password_changed`, and a new access token
      is returned.

### E. Enforcing `mustChangePassword`
18. When `mustChangePassword = true`, the access token carries a claim `pwd_change: true`. While it's
    present, every API call except `POST /auth/password`, `POST /auth/logout` and `POST /auth/refresh`
    returns **`403 PASSWORD_CHANGE_REQUIRED`**. This is enforced in the backend filter chain, not only
    in the UI.
19. The frontend sends such a user straight to "Change password" after login, and back there from any
    page.
20. After a successful change, the new token has no claim and normal access resumes.
21. Existing accounts with `mustChangePassword = true` are covered from the next login or token
    refresh. **Heads-up:** today's staff/student accounts created with temporary passwords will be
    asked to change them.

### Empty and error states
22. The verify/complete, forgot and change pages each have loading, error, generic invalid-link,
    expired, and success states. The Platform Admin panel has None / Invited / Active states and an
    error state.

## 4. Database impact

**New migration `V62__account_setup_and_password_tokens.sql`.** V1–V61 are untouched.

```sql
-- tenant_user: invited status + password timestamp
ALTER TABLE tenant_user DROP CONSTRAINT <existing status check>;   -- name looked up in V-history
ALTER TABLE tenant_user ADD CONSTRAINT ck_tenant_user_status
    CHECK (status IN ('active', 'suspended', 'invited'));
ALTER TABLE tenant_user ADD COLUMN password_changed_at TIMESTAMPTZ;

-- one-time tokens (account setup + password reset)
CREATE TABLE password_token (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL REFERENCES tenant (id),
    user_id         UUID NOT NULL,
    purpose         VARCHAR(20) NOT NULL,          -- ACCOUNT_SETUP | PASSWORD_RESET
    token_hash      CHAR(64) NOT NULL,             -- SHA-256 hex of a 256-bit random token
    expires_at      TIMESTAMPTZ NOT NULL,
    used_at         TIMESTAMPTZ,
    invalidated_at  TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL,
    created_by      UUID,                          -- Platform Admin for invites; NULL for self-service
    CONSTRAINT uq_password_token_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_password_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_password_token_user FOREIGN KEY (tenant_id, user_id) REFERENCES tenant_user (tenant_id, id),
    CONSTRAINT ck_password_token_purpose CHECK (purpose IN ('ACCOUNT_SETUP', 'PASSWORD_RESET')),
    CONSTRAINT ck_password_token_single_end CHECK (used_at IS NULL OR invalidated_at IS NULL)
);
CREATE INDEX idx_password_token_tenant_user ON password_token (tenant_id, user_id, purpose, created_at);
```

- **Tenant-owned:** `tenant_id NOT NULL` + FK. A composite FK keeps the user in the same tenant. The
  index leads with `tenant_id`. `uq_password_token_hash` is global because the hash of a 256-bit
  random value is unique by construction; lookups still filter by the request's tenant.
- The tenant-status check constraint name is confirmed from the migration history during
  implementation (a **new** migration re-creates it; the old one is not edited).
- Token rows are kept as a security record (not deleted). A retention job is a later task.
- The notification outbox and templates need no schema change: the event-type column is a string,
  confirmed during implementation (a CHECK constraint, if any, is widened in V62).

## 5. Backend impact

**identity-access-service** (owns credentials; all new logic lives here):
- `TenantUser`: `invited` status, `completeSetup(hash, now)`, `changePassword(hash, now)`.
  `AccountStatus` gains `INVITED`, and login treats it as failed credentials.
- `PasswordToken` entity plus a `TenantAwareRepository`. `PasswordTokenService`:
  - `issue(userId, purpose, ttl)` → raw token, exposed only to the notification dispatcher;
  - `verify(rawToken)`;
  - `complete(rawToken, newPassword)`, with the atomic single use through `UPDATE … WHERE used_at IS
    NULL AND invalidated_at IS NULL AND expires_at > now()`;
  - `invalidateOpen(userId, purpose)`.
- `PasswordPolicy`: 8–128 characters, not the email, not the current password.
- `PasswordResetService`: request (rate limits in Redis, no enumeration), complete.
- `PasswordChangeService`: verify current password (lockout in Redis), change, revoke other sessions,
  issue a fresh access token.
- `RefreshTokenService.revokeAllTenantSessions(userId, exceptSessionId)`, plus clearing the cached
  session entries.
- `TokenService`: `pwd_change` claim. `JwtAuthenticationFilter` / a new `PasswordChangeRequiredFilter`
  returns `403 PASSWORD_CHANGE_REQUIRED` outside the allow-list.
- **New api** `TenantAdminInvitationApi` (for tenant-management):
  - `inviteFirstAdmin(tenantId, email)`;
  - `resend(tenantId)`;
  - `correctEmail(tenantId, email)`;
  - `firstAdminStatus(tenantId)`.

  It runs under an explicitly set `TenantContext` for the target tenant (Platform Admin requests have
  none) and never takes a tenant id from a client.
- **New api** `PasswordTokenIssuingApi.issueForDispatch(tenantId, userId, purpose)` → the raw token
  and its expiry. It's called by notification-management at send time only (see Security).
- Audit: `recordForTenant` for Platform-Admin-initiated actions. For user-initiated ones, the normal
  `record` with the user as actor.

**tenant-management:**
- `TenantApprovalService.approve` calls `TenantAdminInvitationApi.inviteFirstAdmin` inside its
  transaction.
- New Platform Admin endpoints (§6) delegate to the invitation api.
- `TenantDetailResponse` gains `firstAdmin`.

Dependency direction: tenant-management → identity-access-service `api`. Both are foundational, so no
business domain becomes a dependency of either (`.claude/rules/architecture.md`).

**notification-management:**
- `NotificationEventType` gains `ACCOUNT_SETUP_INVITE` and `PASSWORD_RESET`.
- Listeners enqueue an outbox row holding **only** `{purpose, userId}`, with no token, per MVP-018's
  rule.
- The dispatcher, for these two types:
  - calls `PasswordTokenIssuingApi` right before sending;
  - builds the link from a new `app.public.tenant-url-template` (`TENANT_URL_TEMPLATE`, e.g.
    `https://{subdomain}.example.com`), with the subdomain from the tenant record;
  - renders the template and sends.

  The raw token lives only in memory for that send. Each retry issues a fresh token and invalidates
  the previous one.
- Templates are seeded for new tenants (`TenantRegisteredEvent`) and **seed-if-absent at dispatch**
  for existing tenants.

**Config:** `app.public.tenant-url-template` (`TENANT_URL_TEMPLATE`); in `application-local.yml`,
`http://{subdomain}.lms.test:3000`. It's added to `prod-secrets.reference` as a non-secret setting
and to `docker-compose.prod.yml`.

## 6. API contract

Public, with the tenant from the host and anonymous (`permitAll`), rate-limited:

| Endpoint | Body | Response |
|---|---|---|
| `POST /api/v1/auth/password-tokens/verify` | `{ token }` | `200 { purpose: "ACCOUNT_SETUP"\|"PASSWORD_RESET", emailMasked, expiresAt }`; `400 TOKEN_INVALID` (invalid/expired/used/other tenant, one code) |
| `POST /api/v1/auth/password-tokens/complete` | `{ token, newPassword }` | `204`; `400 VALIDATION_ERROR` (password rules, fieldErrors `newPassword`); `400 TOKEN_INVALID` |
| `POST /api/v1/auth/password-reset/request` | `{ email }` | always `202 { message }` |

Authenticated (any tenant user):

| Endpoint | Body | Response |
|---|---|---|
| `POST /api/v1/auth/password` | `{ currentPassword, newPassword }` | `200 { accessToken, expiresIn }` (same shape as refresh); `400 VALIDATION_ERROR` (`currentPassword` wrong or `newPassword` rules); `429` locked |

Any other authenticated endpoint with `pwd_change: true` in the token → `403 PASSWORD_CHANGE_REQUIRED`
(new error code). The login response already includes `mustChangePassword`; unchanged.

Platform Admin (`hasRole('PLATFORM_ADMIN')`, re-checked in the service):

| Endpoint | Body | Response |
|---|---|---|
| `GET /api/v1/platform-admin/tenants/{id}` | – | adds `firstAdmin: { status: "NONE"\|"INVITED"\|"ACTIVE", emailMasked, invitedAt, inviteExpiresAt }` |
| `POST /api/v1/platform-admin/tenants/{id}/admin-invitation` | `{ email? }` | `202 firstAdmin`: invite (NONE), resend (INVITED), or correct the email (INVITED + a different `email`); `409` ACTIVE or tenant not trial/active; `429` resend within 1 minute; `404` unknown tenant |

Standard `ApiResponse` envelope. New error codes: `TOKEN_INVALID`, `PASSWORD_CHANGE_REQUIRED`.

## 7. Frontend impact

- **Public tenant pages** (`app/(auth)/`):
  - `/account-setup` and `/reset-password`: read `?token`, then `history.replaceState` to remove it;
    verify; set-password form (new + confirm, rules shown, show/hide); success leads to login;
    generic invalid/expired state with "Request a new link".
  - `/forgot-password`: **replaces the existing placeholder page** with the email form and a generic
    confirmation.
  - A "Forgot password?" link on the tenant login page.
  - `<meta name="referrer" content="no-referrer">` on the token pages.
- **Signed-in:** a **Change password** page for every tenant role (reachable from the account menu).
  When `mustChangePassword` is true, the route guard sends every page there and shows an explanation;
  a `403 PASSWORD_CHANGE_REQUIRED` anywhere redirects there too.
- **Platform Admin → Tenants → tenant detail:** a "Tenant Admin" panel with the status badge,
  masked email, invited/expiry times, **Resend invitation**, **Change email** (dialog; only while
  invited), and **Invite admin** (status None).
- Typed API client, Zod schemas (the same password rules as the backend; the backend stays
  authoritative), shared state components.

## 8. Security impact (full review required)

- **New unauthenticated credential-setting path.** Tokens are 256-bit random (`SecureRandom`), stored
  only as SHA-256, single-use (atomic update), short-lived (72 h setup, 60 min reset), scoped to one
  tenant, and invalidated when superseded or when the password changes. Every failure gets one
  generic error.
- **No raw token at rest.** It's issued by the dispatcher at send time and never written to the outbox
  payload (MVP-018's rule), logs or audit. Log masking covers `token=` in URLs.
- **No account enumeration:** forgot-password always gives the same response; login gives the same
  error for invited, unknown and wrong-password accounts.
- **Rate limits:** forgot-password per IP and per email; token verify/complete per IP (20/10 minutes);
  current-password lockout; resend throttle.
- **Session hygiene:** reset and account setup revoke all sessions; change password revokes all
  others.
- **`mustChangePassword` enforced server-side** through a token claim and a filter, not only in the UI.
- **Email as the recovery channel:** an attacker who controls a user's mailbox can reset that user's
  password. This is standard; noted for the privacy/security policy. There's no 2FA in this module.
- **Change control:** authentication architecture (new token type, new public credential endpoints, a
  new JWT claim, a new account status). Recorded in **ADR-020**, which needs approval. Multi-tenancy
  strategy, payments and deployment are unchanged.

## 9. Tenant impact

- `password_token` is tenant-owned: `tenant_id` + composite FK to `tenant_user`, tenant-leading index,
  `TenantAwareRepository`.
- Public token endpoints resolve the tenant from the **host**. A token from tenant A used on tenant B's
  host is treated as invalid (the generic error; it's never looked up across tenants).
- Forgot-password looks the email up only inside the host's tenant. The same email in another tenant
  is never touched.
- Platform Admin actions name the tenant only in the path and run under an explicitly set
  `TenantContext`. The invitation api takes no tenant id from a client.
- Outbox rows carry `tenant_id`, and the dispatcher builds links from that tenant's own subdomain.

## 10. Tests

**Backend (JUnit + Spring Boot integration + Testcontainers; email through the existing fake
messaging provider):**
- Approval:
  - creates an invited Tenant Admin in the same transaction;
  - a failure rolls the approval back;
  - an outbox row is queued with no token in its payload;
  - the dispatcher sends one email whose link contains a token that verifies.
- Resend, email correction and invite-when-none:
  - the old links stop working;
  - the one-minute throttle returns `429`;
  - `409` once the account is active or another admin exists;
  - audit entries are written.
- Invited login: the same `401` body as a wrong password.
- Verify/complete:
  - success activates the account and revokes its sessions;
  - a second use gets `TOKEN_INVALID`;
  - concurrent completes: exactly one succeeds;
  - expired and invalidated tokens are rejected;
  - password rules are applied;
  - the raw token isn't present in any table, and the log output is checked for it.
- Forgot password:
  - identical responses for existing, unknown, suspended and invited accounts;
  - only an active account gets a reset email, and an invited one gets a setup email;
  - rate limits;
  - earlier links are invalidated.
- Change password:
  - a wrong current password gives `400` and lockout;
  - success revokes other sessions but keeps the current one;
  - the new token has no claim.
- Enforcement: a `pwd_change` token gets `403 PASSWORD_CHANGE_REQUIRED` on a sample of endpoints, and
  the allow-list works.
- **Cross-tenant (mandatory):**
  - a tenant A token used on tenant B's host → `TOKEN_INVALID`, and nothing changes;
  - forgot-password on tenant B's host for a tenant A email → no email;
  - the Platform Admin invitation for tenant A never touches tenant B's users;
  - repository queries are tenant-scoped.
- Migration test: V62 constraints (status CHECK, purpose CHECK, used/invalidated exclusivity,
  cross-tenant FK rejected).

**Frontend (Playwright):**
- Account setup: the token is removed from the URL; the invalid/expired/used states; password rules;
  success leads to login.
- Forgot password: always the generic confirmation.
- Change password: a wrong current password; success.
- Forced change: after login with `mustChangePassword`, every page redirects to change password, and a
  `403 PASSWORD_CHANGE_REQUIRED` redirects there too.
- Platform Admin panel: the None/Invited/Active states, resend (including the throttle message) and
  change email.
- Accessibility: labelled password fields; announced errors.

**Manual end-to-end locally:** register an institute → approve → open the email in Mailpit → set a
password → log in.

## 11. Documentation updates

- `docs/adr/ADR-020-account-setup-and-password-tokens.md` (new; approval required).
- `docs/api/identity-access-service.md`: the new auth endpoints, error codes, the `pwd_change` claim.
- `docs/api/tenant-management.md`: `firstAdmin` and the admin-invitation endpoint.
- `docs/architecture/database-architecture.md`: `password_token`, the `tenant_user` changes.
- `docs/ui-ux/screen-map.md`: the new public and signed-in pages, and the Platform Admin panel.
- `docs/operations/production-secrets-runbook.md` / `infrastructure/prod-secrets.reference`: the
  `TENANT_URL_TEMPLATE` setting (non-secret).
- `docs/plans/MVP-002 Authentication Foundation.md`: a note that the deferred forgot/reset password
  is delivered here.
- `frontend/e2e/fixtures/seed/two-tenant-seed.mjs` write-up: the "no API path for the first Tenant
  Admin" blocker is resolved.

## 12. Change-control items requiring explicit approval

1. **Authentication architecture (ADR-020):**
   - one-time password tokens with a public verify/complete;
   - forgot password;
   - change password;
   - a `pwd_change` access-token claim and a `403 PASSWORD_CHANGE_REQUIRED` gate;
   - a new `invited` account status.
2. **Behaviour change for existing users:** accounts already flagged `mustChangePassword` will be
   forced to change their password at next sign-in.

No payment, enrollment, multi-tenancy or deployment rule changes. The migration history only gains V62.

## 13. Delivery

Backend first (all of §5, tests, `mvnw verify`), then the frontend, then the security,
tenant-isolation and UI/UX reviews, then docs. This is a large module. If you prefer, it can ship in
two commits on this branch: **(1) invitation + token completion** (closes the onboarding gap);
**(2) forgot + change + enforcement**.
