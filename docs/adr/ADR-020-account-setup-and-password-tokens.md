# ADR-020: One-Time Password Tokens — Tenant Admin Invitation, Forgot Password, Change Password

## Status

**Accepted (2026-09-30)** by the project owner ("ADR-020 approved"), together with the plan
`docs/plans/AUTH-PWD-01 Tenant Admin Invitation and Password Management.md`. The owner chose the
scope "invite + forgot + change password".

This ADR changes the **authentication architecture**, a change-controlled area in root `CLAUDE.md`.
It also amends **ADR-007 decision 1**: the access token gains one claim.

## Context

- A tenant's first Tenant Admin cannot be created through the product: approval creates no user, and
  every account-creating endpoint needs an existing Tenant Admin. (See the write-up in
  `frontend/e2e/fixtures/seed/two-tenant-seed.mjs`.)
- MVP-002 deferred forgot/reset password. There is no way to recover a password.
- `must_change_password` is stored and returned at login, but there is no change-password endpoint and
  nothing enforces it, so temporary passwords handed out by admins stay in use.
- MVP-018 requires that a raw reset token never be stored in `notification_outbox.payload`.

## Decision

1. **One token mechanism for account setup and password reset.** A new tenant-owned table
   `password_token` (V62). Each token:
   - is 256-bit random from `SecureRandom`, stored only as a SHA-256 hash;
   - has a purpose, `ACCOUNT_SETUP` (72 h) or `PASSWORD_RESET` (60 min);
   - is single-use, enforced by an atomic conditional update;
   - is invalidated when a newer token of the same purpose is issued or the password changes;
   - is valid only on its own tenant's host.
2. **Tokens are issued at send time.** An outbox row holds only `{purpose, userId}`. The notification
   dispatcher asks identity-access-service for a fresh token immediately before sending, so the raw
   token exists only in memory and in the email (this honours MVP-018).
3. **First Tenant Admin on approval.** Approving a tenant creates, in the same transaction, a
   `TENANT_ADMIN` with the registration contact email and a new status **`invited`**. An invited
   account has an unusable password and cannot log in. Completing the account-setup link sets the
   password and makes the account `active`. A Platform Admin can resend the invitation, correct the
   email while it's pending, or invite an admin for a tenant that has none.
4. **Forgot password** on each tenant host: always the same response (no enumeration), rate-limited
   per IP and per email. Only `active` accounts get a reset link; `invited` accounts get a new setup
   link.
5. **Change password** for signed-in tenant users: requires the current password, has a lockout
   after repeated failures, and revokes the user's other sessions.
6. **Enforced `mustChangePassword` (amends ADR-007 decision 1).** When set, the access token carries
   `pwd_change: true`. A backend filter answers every endpoint except change password, logout and
   refresh with `403 PASSWORD_CHANGE_REQUIRED`. The claim holds no permission data; it only narrows
   what the token may do.
7. **Session hygiene.** Completing a setup or reset revokes all of the user's sessions. Change password
   revokes all except the current one. There's no automatic sign-in after a setup or reset.
8. **Scope limits.** Tenant users only. Platform Admin accounts, 2FA and SMS/WhatsApp delivery are out
   of scope.

## Consequences

**Positive**
- New tenants can onboard without database work. Users can recover and change passwords. Temporary
  passwords can no longer stay in use indefinitely.
- No raw token is stored anywhere. One token model serves every purpose, so there's less to secure.

**Negative**
- A new public, unauthenticated endpoint that sets credentials. Mitigated by the token entropy,
  single use, short lifetime, tenant binding, rate limits and generic errors, and it gets a full
  security review.
- Email becomes the recovery channel: control of a mailbox means control of that user's account (a
  standard trade-off; 2FA is a later decision).
- **Existing users flagged `mustChangePassword` are forced to change their password at next sign-in**,
  a visible behaviour change.
- Each email retry issues a new token and invalidates the previous one, so an older email's link stops
  working. That's acceptable and intended.

## Alternatives considered

1. **Platform Admin sets the first password and shares it out of band.** Rejected: an admin would
   know another person's password, and it doesn't solve forgot/change password.
2. **Store the token encrypted in the outbox payload.** Rejected: it's still a usable credential at
   rest. Issuing at send time avoids storing it at all.
3. **Stateless signed tokens (JWT) instead of a table.** Rejected: single use and invalidation need
   server-side state anyway.
4. **Enforce `mustChangePassword` only in the frontend.** Rejected: `.claude/rules` require the backend
   to enforce anything that matters.

## Required follow-up if accepted

Implement per the AUTH-PWD-01 plan: backend first, then frontend, then reviews and docs. Update
ADR-007's decision 1 with a note pointing here.

## Related

- ADR-007 (authentication tokens and devices)
- ADR-019 (production secrets; `TENANT_URL_TEMPLATE` is a non-secret setting)
- MVP-002 (authentication foundation), MVP-018 (email notifications, outbox)

## Implementation notes (2026-09-30)

Built on `feat/account-setup-and-password-management` (V62). Differences from the decision above:

1. **Forced change is enforced from the account row, not a token claim (changes Decision 6).**
   - The JWT filter already loads the user on every request, so it reads `must_change_password` there.
   - It takes effect immediately, including when an admin resets a password mid-session.
   - **ADR-007's token payload is unchanged**, so the amendment to ADR-007 decision 1 is not needed.
2. **The in-app copy of these emails contains no link.** The notification dispatcher saves every sent email as an in-app notification. For account-setup and reset emails it saves a link-free message instead, so the raw token exists only in the email.
3. **`token_hash` is `CHAR(64)`** and is mapped with `@JdbcTypeCode(SqlTypes.CHAR)`. V62 was already applied to a dev database, so the migration wasn't edited.

Also:
- An invited Tenant Admin's audit entry is written after the tenant context is cleared, because `AuditLogApi#recordForTenant` requires that.
- Local CORS now allows `http://*.lms.test:3000`, so every tenant subdomain works locally, not just `demo`.
- The email link host comes from the new non-secret setting `TENANT_URL_TEMPLATE` (`app.public.tenant-url-template`). While it's unset, these emails fail closed.
- **Consequence noted in the ADR:** accounts created through the staff/student/teacher APIs, which start with `must_change_password = true`, must now change their password at first sign-in. Business-flow tests mark such accounts as onboarded (`markOnboarded`).

### Security review fixes (2026-09-30)

- **M-1:** the `prod` profile sets `server.forward-headers-strategy: native`, so the per-IP rate limits use the real client IP from `X-Forwarded-For` (trusted only from Tomcat's internal-proxy ranges), not the proxy's.
- **M-2:** links carry the token in the URL **fragment** (`/account-setup#token=…`). Browsers never send a fragment to a server, so it can't reach proxy or web-server logs; the page reads it once and removes it.
- **L-1:** the outbox insert for these emails runs asynchronously, so forgot password takes the same time whether or not the account exists.
- **L-3:** the JWT filter reuses the already-loaded account for the `must_change_password` check (one query, not two).
- **L-2 (accepted):** anyone who knows an email can use up its forgot-password allowance (3 per hour). That's the usual trade-off of a per-email limit; the user can still sign in, and the window is short.
- **UI:** `/change-password` has a "Back" link to the user's dashboard.
