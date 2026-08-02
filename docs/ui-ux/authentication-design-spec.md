# Authentication Design Specification

Status: Draft — written spec (Figma canvas build blocked, see §7)
Related: `docs/ui-ux/information-architecture.md`, `docs/ui-ux/screen-map.md`,
`docs/ui-ux/user-journeys.md`, `docs/ui-ux/design-system.md`,
`docs/architecture/authentication-authorization.md`, `.claude/rules/security.md`,
`.claude/rules/ui-ux.md`

## 0. Why this document exists right now

Checkpoint 2 of the UI/UX design-foundation work covers Authentication wireframes,
states, and a prototype flow. The Figma file (`yFAIkC2PK32viHYg6vNzhP`) is currently
blocked on an MCP tool-call quota (Starter/Free-tier plan) that has not cleared across
multiple retries. Per this project's Figma execution rules ("when direct Figma canvas
creation is unavailable, create complete frame specifications and documentation instead
of pretending the frames were created"), this document is the authoritative spec for
every Authentication screen, state, and flow connection. It is written so that building
the actual Figma frames — once access resumes — is mechanical (apply this spec to
frames) rather than a design decision.

Frame naming convention (unchanged from the approved plan):
`[Portal] / [Feature] / [Screen] / [Viewport] / [State]`

All Authentication frames use `Authentication` or `Error` as the Feature segment. Portal
prefixes used below: `Public` (tenant-branded, pre-auth), `Platform Admin` (platform-level
login), `Shared` (screens with identical content regardless of which portal triggered
them — matches the "Shared / Cross-Role Screens" section of `screen-map.md`).

Component references assume the Design System components already scoped for Stage 3
(`design-system.md` §4, brief's "Required reusable components"): Text Input, Password
Input, Button, Card, Alert, Validation Message, Form Field Wrapper, Checkbox, Loading
Skeleton/Spinner, Toast. Colors/spacing/radius referenced by token name
(`color/primary`, `color/destructive`, `spacing/md`, `radius/lg`, etc.) are the tokens
already created in the Figma file's `Color / Light`, `Color / Dark`, `Spacing`, and
`Radius` collections (see `docs/ui-ux/design-system.md`) — this spec does not invent new
values.

---

## 1. Screen inventory

| # | Screen | Portal prefix(es) | Viewports | Key states |
|---|---|---|---|---|
| 1 | Tenant-Branded Login | Public (Student / Teacher / Tenant Admin instances) | Desktop, Mobile | Default, Loading, Invalid Credentials, Validation Error |
| 2 | Platform Admin Login | Platform Admin | Desktop, Mobile | Default, Loading, Invalid Credentials |
| 3 | Forgot Password | Shared | Desktop, Mobile | Default, Loading, Submitted, Error |
| 4 | Reset Password | Shared | Desktop, Mobile | Default, Loading, Validation Error, Success |
| 5 | Password Reset Success | Shared | Desktop, Mobile | Default |
| 6 | Invalid or Expired Reset Link | Shared | Desktop, Mobile | Default |
| 7 | First-Login Password Change | Shared | Desktop, Mobile | Default, Loading, Validation Error, Success |
| 8 | Account Locked | Shared | Desktop, Mobile | Default |
| 9 | Suspended Tenant | Shared | Desktop, Mobile | Default |
| 10 | Suspended User | Shared | Desktop, Mobile | Default |
| 11 | Session Expired | Shared | Desktop, Mobile | Default |
| 12 | Unauthorized Access (401) | Shared | Desktop, Mobile | Default |
| 13 | Permission Denied (403) | Shared | Desktop, Mobile | Default |
| 14 | Logout Confirmation | Shared | Desktop, Mobile | Dialog — Default, Loading |

Screens 1–2 are the two distinct login entry points required by source requirements
("Login portals: Student, Teacher, Admin" plus the platform-level admin split confirmed
in `authentication-authorization.md` §3). Per that same doc: *"Every role authenticates
through the same `identity-access-service` login path; there is no separate/parallel auth
stack per portal. Portal separation is a frontend routing concern, not a separate backend
authentication mechanism."* Screen 1 is therefore **one shared component**, instantiated
as three frames (Student, Teacher — also representing Staff sub-roles, Tenant Admin) only
so each portal's route group has its own documented entry-point frame for developer
handoff — not because the UI itself forks per role. Screen 2 (Platform Admin) is a
genuinely separate, unbranded screen since it is never tenant-resolved.

---

## 2. Shared layout pattern ("Auth Shell")

Every screen in this document (except the Logout Confirmation dialog) uses one of two
layout shells. Documenting once here rather than repeating per screen.

**Desktop Auth Shell** (`lg`/`xl`, ≥1024px):
- Two-column split: left panel (40% width, min 420px) = branding/context panel; right
  panel (remaining width, max content width 440px, centered) = the form/message card.
- Branding panel background: `color/sidebar` (Public/tenant screens use the tenant's
  resolved logo + `color/primary` tenant-overridden token; Platform Admin and Shared
  screens use platform-default branding only — see §6 tenant-resolution note).
- Form/message panel background: `color/background`; content in a `Card` component
  (`radius/xl`, `spacing/xl` padding) when the screen is a form; plain centered content
  (no card chrome) for full-page blocking/interstitial states (Suspended, Locked,
  Session Expired, Unauthorized, Permission Denied) since those are not data-entry
  contexts.

**Mobile Auth Shell** (`sm` and below, <640px):
- Single column, full-bleed. Branding reduces to a compact header strip (logo + tenant
  name, `spacing/lg` padding, `color/sidebar` background, fixed height ~96px) above the
  form/message content, per `ui-ux.md` §1's mobile-first consumer-surface guidance
  (Student/Teacher are mobile-first; this shell also governs the pre-auth screens every
  role passes through, so it must not assume desktop).
- Form/message content: full-width `Card` with `spacing/lg` padding, no side margins
  beyond `spacing/md`.
- Full-screen sheet pattern is not needed here (auth screens are already full-page, not
  a modal/drawer) — this is the one exception to `ui-ux.md` §5's modal/drawer mobile rule,
  because there is no underlying page these screens sit on top of.

**Both shells:**
- Every form field uses the Form Field Wrapper component (label + input + help text +
  `aria-describedby`-linked Validation Message per `accessibility.md` §6).
- Primary action button is full-width on mobile, fixed-width (min 200px) on desktop.
- Loading/submitting state: button shows a spinner and `aria-busy="true"`, label changes
  to a present-participle form (e.g. "Signing in…"); the surrounding form region is
  wrapped in `aria-live="polite"` per `accessibility.md` §2.
- Any error resulting from a submit (invalid credentials, validation failure, expired
  link, server error) is announced via `role="alert"`/`aria-live="assertive"`, never a
  purely visual color change (`accessibility.md` §2, §5).

---

## 3. Per-screen specifications

### 3.1 Tenant-Branded Login

- **Frame names:** `Student / Authentication / Login / Desktop / Default`,
  `Student / Authentication / Login / Mobile / Default`,
  `Teacher / Authentication / Login / Desktop / Default`,
  `Teacher / Authentication / Login / Mobile / Default`,
  `Tenant Admin / Authentication / Login / Desktop / Default`,
  `Tenant Admin / Authentication / Login / Mobile / Default`,
  each with `/ Loading`, `/ Invalid Credentials`, `/ Validation Error` state variants.
- **Route suggestion:** `app/(public)/login` (tenant resolved via subdomain/custom
  domain per `multi-tenancy.md` §1); post-login redirect target is role-determined by the
  backend-issued session, not chosen by the frontend.
- **Intended role:** Student, Teacher, Teacher Assistant, Institute Owner/Tenant Admin,
  all Staff sub-roles. Anonymous until submit.
- **Business purpose:** single authenticated entry point into the tenant-scoped
  application; the point where tenant branding is first visibly resolved for the user.
- **Primary actions:** submit email/username + password; navigate to Forgot Password;
  navigate to Student Registration (Student-context frame only — Teacher/Tenant Admin
  frames omit the registration link, since those accounts are provisioned by an admin,
  not self-registered per `source-requirements.md` module 4/5).
- **Visible data:** tenant name/logo (resolved branding), email/username field, password
  field with show/hide toggle, "Remember me" checkbox (session persistence — backend
  policy TBD, see Open Questions), submit button, forgot-password link.
- **Component inventory:** Card, Text Input, Password Input, Checkbox, Button (primary,
  full-width on mobile), Validation Message, Alert (for the Invalid Credentials state).
- **Responsive behavior:** per Auth Shell (§2). No content differs between Student/
  Teacher/Tenant Admin frames beyond the tenant-branding resolution and the
  registration-link visibility noted above.
- **States:**
  - *Default* — empty form, focus on email field.
  - *Loading* — see Auth Shell submitting pattern.
  - *Invalid Credentials* — inline `Alert` (destructive variant, `color/destructive`)
    above the form: "Incorrect email or password." Per `authentication-authorization.md`
    §9, this message must not reveal which field was wrong (no "email not found" vs.
    "wrong password" distinction — that's a user-enumeration risk).
  - *Validation Error* — per-field `Validation Message` (Zod client-side check: valid
    email format, password non-empty) — client-side only, never a substitute for the
    backend's own validation on submit (`.claude/rules/frontend.md`).
- **Validation rules:** email format (client, Zod) + required; password required,
  no client-side complexity check (complexity is a backend/account-creation concern, not
  re-validated at login).
- **API dependency:** `identity-access-service` login endpoint (exact contract not yet
  defined in `docs/api` — flag as a dependency to confirm via `review-api-contract` before
  frontend implementation starts).
- **Security considerations:** tenant identity resolved server-side from
  subdomain/custom domain, never client-supplied (`multi-tenancy.md` §1); this screen
  must never itself decide device-limit or suspicious-login outcomes — it only renders
  whatever the backend response says (`.claude/rules/security.md`, Device Authentication).
  Generic "Incorrect email or password" copy (see Invalid Credentials state) is a
  deliberate anti-enumeration control.
- **Accessibility notes:** labels programmatically associated (not placeholder-only);
  password show/hide toggle has an `aria-label` reflecting current state ("Show
  password"/"Hide password"); focus visible on all interactive elements
  (`accessibility.md` §6, §4).
- **Open questions:** "Remember me" session-persistence policy (duration, whether it
  interacts with device-limit counting) is not specified in current architecture docs —
  flag before wiring the checkbox to real behavior.

### 3.2 Platform Admin Login

- **Frame names:** `Platform Admin / Authentication / Login / Desktop / Default`,
  `/ Mobile / Default`, `/ Desktop / Loading`, `/ Desktop / Invalid Credentials`.
- **Route suggestion:** `app/(platform-admin)/login` — a fixed, non-tenant-resolved
  route (no subdomain branding logic applies here at all).
- **Intended role:** Platform Admin / platform operations staff only.
- **Business purpose:** entry point for platform-level operations, deliberately
  visually distinct from any tenant's branded login so an operator never confuses which
  system they're signing into.
- **Primary actions:** submit email/username + password. No "forgot password" link is
  out of scope — Platform Admin accounts still need a reset path; link is present but
  routes to the same Shared Forgot Password screen.
- **Visible data:** neutral platform logo/wordmark (never a tenant's branding — this is
  the "falls back to neutral platform branding" case referenced in `ui-ux.md` §2, except
  here it's not a fallback, it's the fixed identity of this route), email field, password
  field, submit button.
- **Component inventory:** Card, Text Input, Password Input, Button, Alert, Validation
  Message.
- **Responsive behavior:** per Auth Shell (§2), using platform-default branding tokens
  only — no tenant override is ever applied on this route.
- **States:** Default, Loading, Invalid Credentials (same anti-enumeration copy as §3.1).
- **Security considerations:** this route must never be reachable via a tenant
  subdomain, and must never render any tenant-supplied branding — visually distinct by
  design so a phishing page cloning a tenant's login can't be mistaken for this one.
  Given the elevated blast radius of a Platform Admin credential compromise, flag for a
  future decision on whether this route requires MFA before general availability (see §6
  placeholders).
- **Accessibility notes:** same as §3.1.
- **Open questions:** none beyond the MFA placeholder already tracked in §6.

### 3.3 Forgot Password

- **Frame names:** `Shared / Authentication / Forgot Password / Desktop / Default`,
  `/ Mobile / Default`, `/ Desktop / Loading`, `/ Desktop / Submitted`,
  `/ Desktop / Error`.
- **Route suggestion:** `app/(public)/forgot-password` (tenant-branded when reached from
  a tenant login; platform-default branding when reached from Platform Admin login).
- **Intended role:** any authenticatable role.
- **Business purpose:** self-service password-reset initiation.
- **Primary actions:** submit email; return to Login.
- **Visible data:** email input, submit button.
- **Component inventory:** Card, Text Input, Button, Validation Message, Alert (success
  variant for Submitted state).
- **States:**
  - *Default* — empty email field.
  - *Loading* — submitting.
  - *Submitted* — confirmation message: "If an account exists for this email, a reset
    link has been sent." This deliberately does not confirm/deny whether the email
    exists (anti-enumeration, same principle as §3.1's Invalid Credentials copy).
  - *Error* — generic server-error state (see shared Error state pattern,
    `design-system.md` §4.1's `ErrorState`), distinct from validation error.
- **Validation rules:** email format (client, Zod), required.
- **API dependency:** `identity-access-service` password-reset-request endpoint (contract
  TBD).
- **Security considerations:** the Submitted-state copy is itself a security control
  (prevents account enumeration via this form) — do not "improve" the copy later to be
  more specific without re-reviewing this tradeoff.
- **Accessibility notes:** Submitted confirmation announced via `aria-live="polite"`
  (it's a success outcome, not an alert).

### 3.4 Reset Password

- **Frame names:** `Shared / Authentication / Reset Password / Desktop / Default`,
  `/ Mobile / Default`, `/ Desktop / Loading`, `/ Desktop / Validation Error`,
  `/ Desktop / Success`.
- **Route suggestion:** `app/(public)/reset-password/[token]` — reached only via the
  emailed link; an invalid/expired token routes to §3.6 instead of rendering this screen.
- **Intended role:** any authenticatable role, mid-reset.
- **Primary actions:** submit new password + confirm password.
- **Visible data:** new-password field, confirm-password field, both with show/hide
  toggle, password-strength indicator (client-side hint only).
- **Component inventory:** Card, Password Input (×2), Button, Validation Message.
- **States:** Default, Loading, Validation Error (mismatch, complexity rule violation —
  exact complexity rule TBD, see Open Questions), Success (transitions to §3.5).
- **Validation rules:** client-side (Zod) checks password match + a placeholder minimum-
  length rule; actual complexity policy is a backend decision this frontend must also
  handle as a 422 even if client validation passes (`.claude/rules/frontend.md`).
- **Security considerations:** the reset token is single-use and time-limited
  server-side; this screen must handle a token that expires *during* the user's edit
  (submit-time rejection → routes to §3.6, not a silent failure).
- **Open questions:** password complexity policy is not specified anywhere in current
  architecture docs (`authentication-authorization.md` §12 lists "password hashing
  algorithm/parameters" as an open technology decision, which implies complexity policy
  is equally undecided) — flag before finalizing the Validation Message copy.

### 3.5 Password Reset Success

- **Frame names:** `Shared / Authentication / Password Reset Success / Desktop /
  Default`, `/ Mobile / Default`.
- **Route suggestion:** shown as a terminal state after §3.4's submit succeeds (not
  necessarily a distinct route — may be a state swap on the same page).
- **Business purpose:** confirms the reset succeeded and hands the user back to Login.
- **Visible data:** success icon/message ("Your password has been reset."), "Continue to
  Login" button.
- **Component inventory:** Card, Alert (success), Button.
- **Accessibility notes:** announced via `aria-live="polite"` on arrival.

### 3.6 Invalid or Expired Reset Link

- **Frame names:** `Shared / Authentication / Invalid Reset Link / Desktop / Default`,
  `/ Mobile / Default`.
- **Route suggestion:** rendered in place of §3.4 when the token fails server-side
  validation (missing, expired, already used).
- **Business purpose:** clear dead-end recovery — never render a broken form.
- **Visible data:** explanation message ("This password reset link is invalid or has
  expired."), "Request a new link" button (→ §3.3).
- **Component inventory:** Card, Alert (warning/destructive), Button.
- **Security considerations:** must not reveal *why* the token is invalid (expired vs.
  already-used vs. never-existed) beyond the generic message — same anti-enumeration
  family as §3.1/§3.3.

### 3.7 First-Login Password Change

- **Frame names:** `Shared / Authentication / First-Login Password Change / Desktop /
  Default`, `/ Mobile / Default`, `/ Desktop / Loading`, `/ Desktop / Validation Error`,
  `/ Desktop / Success`.
- **Route suggestion:** `app/(public)/first-login-password-change` (or an interstitial
  step before landing on the role's dashboard) — reached when a backend-issued
  "must change password" flag is present on an account (e.g., admin-created Teacher/
  Staff/Student accounts with a temporary password, per module 3/4/5 "manual creation by
  admin" flows).
- **Intended role:** any role whose account was provisioned with a temporary password.
- **Business purpose:** forces a credential change before any further app access —
  functionally similar to Reset Password (§3.4) but distinct because it's a mandatory
  gate, not a self-initiated recovery, and there is no "skip" action.
- **Primary actions:** submit new password + confirm; no "cancel"/back option (this is a
  gate, not a dismissible flow) — signing out is the only alternative.
- **Component inventory:** same as §3.4.
- **Security considerations:** the temporary-password state itself must be a
  server-verified flag on the session/profile payload, never inferred client-side (same
  principle as `authentication-authorization.md` §9's permission-denied rule).
- **Open questions:** same password-complexity open question as §3.4.

### 3.8 Account Locked

- **Frame names:** `Shared / Authentication / Account Locked / Desktop / Default`,
  `/ Mobile / Default`.
- **Route suggestion:** rendered in place of the Login form when the backend rejects a
  login attempt with a lockout-specific reason code.
- **Business purpose:** communicate a temporary/administrative lockout distinctly from a
  simple wrong-password error, with actionable next steps.
- **Visible data:** explanation message ("This account has been temporarily locked
  after multiple failed sign-in attempts." or an admin-lockout variant), guidance
  (retry-after time if the backend supplies one, or a "Contact support" link routing
  into the relevant portal's Support ticket flow per `screen-map.md`).
- **Component inventory:** Card, Alert (destructive), Button (secondary, "Contact
  support").
- **Security considerations:** this state's existence, precedence, and exact trigger
  condition (N failed attempts, admin-initiated lock, or both) is not specified in
  `authentication-authorization.md` — the doc only confirms device-limit and
  suspicious-login detection exist server-side, not a distinct "lockout" mechanism. Flag
  as an **open question requiring backend confirmation** before this screen's copy/logic
  is finalized (see §8).

### 3.9 Suspended Tenant

- **Frame names:** `Shared / Authentication / Suspended Tenant / Desktop / Default`,
  `/ Mobile / Default`.
- **Route suggestion:** rendered in place of the Tenant-Branded Login form (§3.1) when
  the resolved tenant's status is `suspended`/`cancelled` (per `module-catalog.md`,
  `tenant-management` owns tenant status lifecycle).
- **Business purpose:** communicate that the *institute*, not the individual user, is
  the reason access is blocked — must not be confused with Account Locked (§3.8) or
  Suspended User (§3.10).
- **Visible data:** neutral/platform-default branding (a suspended tenant's own branding
  should not necessarily be trusted to still render correctly, and per `ui-ux.md`'s
  branding-fallback pattern, falling back to neutral platform branding here is
  consistent), explanation message, no login form at all (there's nothing to attempt).
- **Component inventory:** Card, Alert (destructive), no form components.
- **Security considerations:** tenant status must be checked server-side as part of
  tenant resolution itself (before credentials are even evaluated) — this screen
  represents the case where the backend refuses to proceed past tenant resolution.
- **Open questions:** whether any contact/appeal path is offered here (e.g., a link to
  contact platform support) is not specified — flag for product decision.

### 3.10 Suspended User

- **Frame names:** `Shared / Authentication / Suspended User / Desktop / Default`,
  `/ Mobile / Default`.
- **Route suggestion:** rendered in place of the Login form when tenant status is fine
  but the specific user's account status is suspended (per `user-management`'s student/
  teacher/staff status field).
- **Business purpose:** distinguishes an individual-account suspension (e.g., a
  Tenant-Admin-initiated deactivation) from a tenant-wide suspension (§3.9).
- **Visible data:** tenant branding still resolves normally here (only the user is
  blocked, not the tenant); explanation message; "Contact your institute administrator"
  guidance rather than platform support (since a Tenant Admin, not Platform Admin, is
  the one who can lift this).
- **Component inventory:** Card, Alert (destructive).
- **Security considerations:** same principle as §3.9 — must not reveal *why* the
  account is suspended beyond a generic message, to avoid leaking internal
  moderation/HR-type detail to the blocked user.

### 3.11 Session Expired

- **Frame names:** `Shared / Authentication / Session Expired / Desktop / Default`,
  `/ Mobile / Default`.
- **Route suggestion:** rendered as an interstitial/redirect target when an
  already-authenticated user's session token is no longer valid mid-app (any protected
  route, any portal).
- **Business purpose:** distinguishes "you were logged in and it timed out" from "you
  were never logged in" (§3.12) — different user expectation, different copy.
- **Visible data:** explanation message ("Your session has expired. Please sign in
  again."), "Sign in" button routing back to the correct portal's Login screen (§3.1 or
  §3.2 depending on which portal the session belonged to) — this requires the frontend
  to remember which portal context it was in, not default to a generic login.
- **Component inventory:** Card, Alert, Button.
- **Accessibility notes:** if this interrupts an in-progress action (e.g., mid-exam,
  mid-payment-slip-upload), the message should acknowledge that, since silently dropping
  a user's in-progress work without explanation is a poor and potentially
  trust-damaging experience — exact per-flow copy is an implementation detail, but the
  base template must support an optional "your work may not have been saved" line.

### 3.12 Unauthorized Access (401)

- **Frame names:** `Shared / Error / Unauthorized / Desktop / Default`,
  `/ Mobile / Default`.
- **Route suggestion:** rendered when an unauthenticated request hits a protected route
  directly (e.g., a bookmarked admin URL with no session at all).
- **Business purpose:** distinguishes "you're not logged in" from "you're logged in but
  not allowed" (§3.13, Permission Denied) — these must not share copy, since the correct
  next action differs (sign in, vs. this isn't for you).
- **Visible data:** explanation message, "Sign in" button.
- **Component inventory:** Card, Alert, Button.
- **Security considerations:** per `frontend.md`, this state's *trigger* is always a
  server-verified 401 — the frontend must never compute "unauthorized" itself from
  absent local state, only render what the backend response says.

### 3.13 Permission Denied (403)

- **Frame names:** `Shared / Error / Permission Denied / Desktop / Default`,
  `/ Mobile / Default`.
- **Route suggestion:** rendered when an authenticated user hits a route/action their
  role doesn't permit (e.g., a Read-only Auditor hitting a mutating route, a Student
  hitting a Tenant Admin route, cross-tenant access attempts).
- **Business purpose:** the shared `PermissionDeniedState` component referenced
  throughout `design-system.md` and `ui-ux.md` — this is its full-page rendering (a
  smaller inline variant is also used within pages per the component library, out of
  scope for this Authentication-only spec).
- **Visible data:** explanation message, link back to the user's own dashboard (never a
  generic "go home" that might itself be inaccessible).
- **Component inventory:** Card, Alert, Button.
- **Security considerations:** per `design-system.md` §5 and `authentication-
  authorization.md` §9, this state is driven **only** by a server-verified 403 response
  or a role/permission value from the authenticated session/profile payload — never
  computed from a client-stored role string. This is the most safety-critical copy/logic
  pairing in this entire document; do not let a future redesign quietly change the
  trigger to a client-side check "for a snappier UI."
- **Accessibility notes:** `role="alert"` on arrival, since this is an unexpected
  interruption to the user's intended action.

### 3.14 Logout Confirmation

- **Frame names:** `Shared / Authentication / Logout Confirmation / Desktop / Default`,
  `/ Mobile / Default` (rendered as the `Confirmation Dialog` component, not a full
  page).
- **Route suggestion:** N/A — triggered from the account/profile menu in any portal.
- **Business purpose:** per the brief, shown "where appropriate" — scoped here to
  **Tenant Admin and Platform Admin only**, where an accidental logout mid-workflow
  (e.g., mid-payment-approval, mid-tenant-suspension) has higher cost than for Student/
  Teacher, which log out directly without a confirmation step. This mirrors the
  destructive-action-confirmation pattern already required elsewhere in `security.md`.
- **Primary actions:** Confirm logout / Cancel.
- **Component inventory:** Confirmation Dialog (focus-trapped per `accessibility.md`
  §1, returns focus to the triggering element on cancel).
- **Open questions:** whether Teacher should also get a confirmation (mid-attendance-
  marking, mid-exam-authoring are arguably also costly to lose) is a reasonable product
  question — flagging rather than deciding unilaterally, since the brief's own scoping
  ("where appropriate") implies a product judgment call.

---

## 4. Prototype flow (logical spec — to be wired in Figma once access resumes)

Per Checkpoint 2's required "Authentication prototype flow":

1. **Tenant-branded login → dashboard**: §3.1 Default → (submit, success) →
   role-appropriate portal dashboard (outside this spec's scope — first real screen of
   Checkpoint 3+).
2. **Failed login**: §3.1 Default → (submit, backend 401 generic) → §3.1 Invalid
   Credentials state (same frame, state swap) → user retries.
3. **Forgot/reset password**: §3.1 Default → (Forgot Password link) → §3.3 Default →
   (submit) → §3.3 Submitted → (user clicks emailed link, out-of-band) → §3.4 Default →
   (submit, success) → §3.5 → (Continue to Login) → §3.1 Default.
4. **Expired session / re-login**: any protected route (out of this spec's scope) →
   (token invalid) → §3.11 Default → (Sign in) → §3.1 or §3.2 Default depending on
   originating portal.
5. **Suspended account**: §3.1 Default → (submit, backend reports tenant suspended) →
   §3.9 Default. Separately: §3.1 Default → (submit, backend reports user suspended) →
   §3.10 Default.

Each arrow above is a Figma prototype connection to wire once frames exist: source frame
→ trigger (on click / after delay for loading states) → destination frame, using
"Smart Animate" for state swaps within the same frame (e.g., Default → Loading →
Invalid Credentials) and simple navigation for cross-screen transitions.

---

## 5. Desktop vs. Mobile — summary

Both viewports are required for every screen above (§1 table); layout differences are
fully captured by the Auth Shell pattern in §2 — no screen in this document has
viewport-specific *content* differences beyond that shell, only layout.

---

## 6. Future placeholders (not fully designed, per brief scope)

Reserved but not functionally designed in this pass — each gets a visible-but-disabled
or clearly-deferred treatment so the design system has a slot ready without pretending
the feature exists:

- **Social login** — a disabled/greyed "Sign in with Google" style button row is
  reserved below the primary form on §3.1/§3.2 (visually present, not wired, tooltip
  "Coming soon" if interacted with) — do not connect it to any real OAuth flow.
- **Multi-factor authentication** — a reserved step placeholder between successful
  password submit and dashboard redirect (not built) — `authentication-authorization.md`
  §12 explicitly leaves MFA scope as an open technology decision; do not assume which
  roles need it.
- **Device verification** — no dedicated screen; device registration itself is silent/
  server-side per `authentication-authorization.md` §5, so there is no user-facing
  "verify this device" step to design unless a future decision adds one.
- **Passwordless login** — not reserved as a visible UI element (would require a
  fundamentally different form, not a toggle on the existing one) — flag as a future
  redesign, not an incremental addition.
- **Tenant custom domains** — already accounted for architecturally (tenant resolution
  works the same whether via subdomain or custom domain, per `multi-tenancy.md` §1); no
  additional screen needed, just confirms §3.1's branding-resolution note isn't
  subdomain-specific.

---

## 7. Figma build status

As of this document's authoring, the Figma file has: 3-page structure (Starter-plan page
cap, resolved via Sections per approved workaround), 76 design tokens mirroring
`globals.css` (Color Light/Dark, Radius, Spacing), the `00 Cover` section, and the `03
Foundations` color-swatch documentation — all verified via screenshot. The Foundations
typography/spacing/radius block and all Authentication frames described in this document
are **not yet built in Figma** — blocked on an MCP tool-call quota that has not cleared
across repeated retries spanning several minutes. This document is the complete,
build-ready source for that work; no design decisions remain open for the frames
themselves (only the handful of explicit "Open questions" noted per-screen above, which
are backend/product decisions, not layout decisions).

## 8. Consolidated open questions from this pass

1. "Remember me" session-persistence policy (§3.1).
2. Whether Platform Admin login requires MFA before general availability (§3.2, ties to
   `authentication-authorization.md` §12).
3. Password complexity policy — not specified anywhere in current architecture docs
   (§3.4, §3.7).
4. Account Locked's exact trigger mechanism and precedence versus device-limit/
   suspicious-login detection — not confirmed as a distinct mechanism in
   `authentication-authorization.md` (§3.8).
5. Whether Suspended Tenant offers any contact/appeal path (§3.9).
6. Whether Logout Confirmation should also cover Teacher, not just Tenant Admin/Platform
   Admin (§3.14).
