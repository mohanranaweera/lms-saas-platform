# Key User Journeys

Status: Draft
Related rules: root `CLAUDE.md` (payment roadmap, enrollment activation), `.claude/rules/payments.md`, `.claude/rules/security.md`, `.claude/rules/ui-ux.md`

## Purpose

Numbered end-to-end step flows for the highest-value journeys in the product. Each
journey calls out the screens involved, the state (loading/empty/error/
permission-denied) expected at each step, and — where relevant — the backend
authority boundary that the frontend must never cross.

---

## Journey 1: Student Enrollment → Payment → Access

**Critical constraint** (from root `CLAUDE.md` and `.claude/rules/payments.md`):
enrollment activates only after a verified, persisted backend payment confirmation
or an approved manual payment slip — never from a frontend "payment success" page,
and never from `Order`/request-payload state alone.

1. Student browses `Public > Storefront > Course Listing` (or `Student > Courses >
   Catalog`) and opens `Course Detail`.
2. Student selects "Enroll" — if unauthenticated, routed through `Public > Auth >
   Student Registration` / `Login` first.
3. Student is presented `Student > Payments > Checkout` (order creation): order is
   created tenant-aware, server-side, from the authenticated session — the frontend
   never supplies `tenant_id`.
4. Student chooses payment method:
   - **Gateway payment**: redirected to/embeds payment gateway. On return, the
     frontend shows a "payment processing / awaiting confirmation" **loading state**
     (`aria-busy`, `aria-live="polite"`) — it does **not** mark enrollment active on
     this return, even if the gateway redirect indicates success. The frontend polls
     or subscribes for the backend-confirmed payment/enrollment status.
   - **Manual payment slip**: routed into `Student > Payments > Payment Slip Upload`
     (see Journey 3).
5. Backend verifies payment (webhook/callback or manual approval) and, only after a
   persisted confirmed payment/approved slip exists, activates enrollment.
6. Frontend reflects the confirmed state by re-fetching (React Query) the
   enrollment/order status — course access unlocks in `Student > Courses > My
   Courses` only once the backend returns an active enrollment record.
7. If payment fails/is rejected, `Student > Payments > Payment History` shows the
   failed/rejected state with a clear **error state** (`role="alert"`) and a retry
   path back to step 3 — course access remains locked.
8. If payment is still pending after a reasonable interval, the UI shows a
   distinct "pending confirmation" state (not the same copy as failure), with
   guidance and a link to Support.

Frontend responsibility boundary: the frontend's only job across steps 4–8 is to
render whatever state the backend reports; it must not compute or infer "paid" from
client-visible order status.

---

## Journey 2: Student Device-Limit-Exceeded Login

Per `.claude/rules/security.md`, device limit enforcement is a backend
authorization decision (evaluated in override order: student > course > tenant >
plan), never a frontend computation.

1. Student attempts login on a new device via `Public > Auth > Tenant Login`.
2. Backend evaluates device registration/limit server-side and returns a
   401/403-style rejection with a machine-readable reason (e.g.
   `DEVICE_LIMIT_EXCEEDED`) — the frontend never independently counts devices or
   decides this client-side.
3. Frontend renders a **permission-denied-style state** on the login screen: "You've
   reached the device limit for this account" — sourced entirely from the backend
   response, not inferred from local storage/cookies.
4. UI offers next steps consistent with backend capability: (a) contact tenant
   support to request a device reset (routes to `Student > Support > My Tickets` /
   new ticket), or (b) if the student has visibility into their own device list
   (`Student > Devices > My Devices`) after another valid session, they can request
   removal of an old device there — but any actual reset is a Tenant Admin action
   subject to its own authorization + cooldown (handled in Tenant Admin flows, not
   shown here).
5. Once an admin performs the reset (server-side, cooldown-enforced), the student
   retries login; backend re-evaluates and allows the new device registration.
6. No client-side "try again" bypass exists — the frontend cannot locally clear or
   simulate a device slot being freed.

---

## Journey 3: Manual Payment Slip Submission → Approval (Staff Side)

State machine per `.claude/rules/payments.md`: `SUBMITTED → UNDER_REVIEW →
APPROVED | REJECTED`, one-directional; duplicate/suspicious checks are mandatory
gates before `APPROVED`.

**Student side:**
1. Student opens `Student > Payments > Payment Slip Upload`, fills reference
   number and uploads slip image/PDF (accessible file input with visible label and
   help text on accepted formats/size).
2. On submit, UI shows a submitting **loading state** (`aria-busy`); on success, slip
   enters `SUBMITTED` and student sees it reflected in `Student > Payments >
   Payment History` as "Submitted — under review" (a distinct status, not "paid").
3. Course access remains locked; the UI does not imply activation at this step.

**Staff/Tenant Admin side:**
4. Staff (Finance Staff or Institute Owner, per role permission) opens `Tenant
   Admin > Payments > Manual Slip Review Queue`. Empty state here distinguishes "no
   pending slips" from "no slips match your filter."
5. Staff opens `Slip Detail`; backend has already run OCR reference extraction,
   duplicate-reference check, and duplicate-image-hash check server-side. The UI
   surfaces the results (including any suspicious/duplicate flag) as
   backend-supplied data — the frontend performs no duplicate-detection logic
   itself.
6. If flagged, the UI requires the reviewer to provide an override reason before an
   "Approve anyway" action is enabled; submitting writes to the backend, which
   records the audit log entry (reviewer identity, flags overridden, reason,
   timestamp) — the frontend must never allow approval to proceed without a reason
   present, and must never fabricate or hide the audit trail.
7. Reviewer approves or rejects. Approval triggers backend-side transition to
   `APPROVED` and (only then) enrollment activation; rejection transitions to
   `REJECTED` and notifies the student (async, via notification-management).
8. Student's `Payment History` updates (via re-fetch) to reflect
   Approved/Rejected — course access unlocks only on Approved, confirmed by the
   backend record, not by the staff-side UI action completing.

---

## Journey 4: Tenant Onboarding / Approval (Platform Admin)

1. Prospective institute submits a tenant-registration entry point with institute
   profile, contact info, and requested plan.
2. New tenant enters a `PENDING_APPROVAL`-equivalent status. Platform Admin sees it
   in `Platform Admin > Tenants > Tenant List` (filterable by status); an empty
   state here distinguishes "no tenants awaiting approval" from "no tenants match
   filters."
3. Platform Admin opens `Tenant Approval` screen: reviews profile, contact info,
   requested plan/feature limits.
4. Platform Admin approves or rejects, server-side. The tenant's status
   (trial/active/suspended/cancelled) is backend-authoritative; the frontend
   reflects it, never sets it locally before confirmation.
5. On approval, backend provisions tenant-scoped configuration (branding defaults,
   default plan feature limits); Tenant Admin (Institute Owner) receives
   notification and can now log in via the tenant-specific login page.
6. Platform Admin can subsequently adjust plan/feature limits from `Platform Admin
   > Plans > Plan & Feature Flag Editor`, and view tenant usage vs. limits from
   `Tenant Detail`.
7. Every approval/rejection/status-change action is audit-logged server-side
   (actor, tenant, before/after status) and visible in `Platform Admin > Audit Log`.

---

## Journey 5: Teacher Course-Creation-to-Publish

1. Teacher (or Tenant Admin acting on teacher's behalf, per permission) opens
   `Teacher > Courses > My Courses` and selects "New Course."
2. Multi-step `Course Builder` form (React Hook Form + Zod): category, subject/
   stream/grade/year, pricing, enrollment rules, access duration, visibility
   (draft/private/public), prerequisites. Each step is keyboard-navigable; Zod
   validation gives immediate client-side feedback, but final submit still handles
   a backend 422 (e.g. tenant-scoped slug/name uniqueness) even if Zod passed.
3. Teacher adds Modules & Lessons, then uploads Materials (`Teacher > Courses >
   Materials Manager`): drag-and-drop lesson/material ordering must have a
   keyboard-operable alternative (e.g. explicit "move up/down" controls) per
   accessibility requirements.
4. Course remains in `DRAFT`/unpublished status — not visible on the public
   storefront. Teacher can preview via the branding-consistent preview pipeline.
5. Teacher (or Tenant Admin, depending on tenant's approval policy) submits for
   review/publish. If tenant policy requires Tenant Admin approval, course enters
   an under-review state visible to Tenant Admin (`Tenant Admin > Courses > Course
   Detail / Approval`).
6. On publish, course becomes visible on `Public > Storefront > Course Listing` /
   `Course Detail`, subject to tenant/course visibility settings.
7. Teacher can later clone, archive, or update pricing — price changes on a
   published course must be audit-logged server-side (per
   `.claude/rules/security.md`), and the UI must not offer a price-change action
   that bypasses that logging (i.e., there is exactly one code path/endpoint for
   price changes).

---

## Journey 6: Payment Expiry → Reactivation

1. Backend expiry process (course/session/material/video/payment-based expiry, per
   module 18) marks a student's access as expired — the frontend never itself
   decides expiry; it renders whatever access-window/expiry state the backend
   returns for a course.
2. Prior to expiry, student may see a proactive alert (`Student > Dashboard` /
   notification center) — "auto reminder before expiry" (module 18 recommended
   addition), sourced from notification-management, not computed client-side from a
   locally cached expiry date.
3. On accessing an expired course, `Student > Courses > Course Workspace` shows a
   distinct **access-expired state** (not a generic error, not a permission-denied
   state) with a clear "Reactivate" CTA — this is a contextual empty/blocked state
   per `ui-ux.md` §3, distinct from "never enrolled."
4. Student initiates `Student > Payments > Reactivation`: this creates a **new**
   order/payment (never an extension/mutation of the original payment record, per
   `.claude/rules/payments.md` §6).
5. Reactivation payment follows the same payment journey as Journey 1
   (gateway or manual slip) — course access unlocks only after backend-confirmed
   payment/approval, with the same loading/pending/failure states.
6. If a Tenant Admin approval step is required for reactivation (module 18:
   "Admin approval"), the request appears in `Tenant Admin > Access & Expiry >
   Reactivation Approvals` before activation — the frontend must not offer a path
   that skips this approval when the tenant's configuration requires it.
7. On confirmed reactivation, `Student > Courses > My Courses` and payment history
   both reflect the new payment record, linked back to the original
   enrollment/course context; the prior expired payment record is left untouched
   in history (append-only).

## Open questions

- Whether tenant self-registration (Journey 4, step 1) is a public unauthenticated
  form or an invite-only flow initiated by Platform Admin outreach is not specified
  in source requirements — flagging for product decision before wireframing that
  screen.
- Whether reactivation always requires Tenant Admin approval or only when a tenant
  configures it that way is not fully specified (module 18 lists "Admin approval"
  as required, but doesn't state if it's conditional per tenant) — flagging for
  clarification.
