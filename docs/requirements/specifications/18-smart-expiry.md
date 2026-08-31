# Smart Expiry

**Domain:** `enrollment-management`, jointly with `content-management`/`video-access-management` for material/video-level enforcement (Module 18) · **Portal(s):** Student, Tenant Admin

## 1. Business purpose

Automatically lapse student access to course/session/material/video content based on payment
coverage or explicit rules, with a reactivation workflow.

Source: `docs/requirements/source-requirements.md` Module 18.

## 2. Actors

- **Student** — experiences expiry, requests reactivation
- **`enrollment-management`** backend
- **`content-management` / `video-access-management`** — enforce material/video-level expiry at fetch time
- **Tenant Admin / Finance Staff** — approve reactivation, configure rules/grace periods/bulk extension

## 3. Preconditions

`enrollment-management` activation (MVP) and payment/ledger state must exist — payment-based
expiry is a **read** of payment/ledger data via `api` interfaces, never a duplication.
`content-management`/`video-access-management` must exist for material/video-level enforcement.

## 4. Normal flow

1. Course-level expiry window configured at course/pricing-plan level.
2. On each access-relevant request, the owning domain checks expiry state.
3. Near expiry, an auto-reminder notification fires (event-driven, not client-computed).
4. On expiry, `enrollment-management` records an access-state transition/expiry **event** — never mutates the original payment record.
5. Student sees a distinct "access expired" state with a Reactivate CTA.
6. Reactivation always creates a **new** order/payment.
7. Admin approval is unconditionally required before reactivation can proceed
   (shipped MVP-012 behavior — no tenant-configurable skip-approval option exists);
   the request appears in `Reactivation Approvals`, where only Tenant Admin (the
   sole role holding `ACCESS_EXPIRY`/`APPROVE`) may approve or reject it.
8. New confirmed payment/approved slip reactivates access atomically, per the same rule as initial activation.

## 5. Alternative flows

- Grace period active: access retained/degraded rather than cut instantly (exact duration is an open business decision).
- Bulk expiry extension: audit-logged (actor, tenant, scope, before/after).
- Student-specific override: audit-logged; exact precedence order for the expiry rules engine is explicitly **not confirmed** to mirror the device-limit precedence pattern.
- Reactivation submitted but never approved: access stays expired, no partial activation.
- Reactivation payment fails/rejected: same failure/pending states as initial enrollment.

## 6. Authorization rules

Per "Access & expiry / reactivation" row: Institute Owner `V/C/E/A`; Finance Staff `V (approve if
finance-adjacent)`; Student Support `V`; Read-only Auditor `V`. Bulk expiry extension and
student-specific override require the same authorization as any admin mutation — not available
to Read-only Auditor or non-privileged staff.

## 7. Tenant rules

Expiry is tenant/course/session/material/video-scoped access state, evaluated per enrollment;
must never mutate payment/ledger history. Bulk expiry extension and student-specific override
are tenant-scoped admin actions requiring audit logging.

## 8. Acceptance criteria

- [ ] Activation/reactivation test: enrollment/access activates only from a persisted, verified payment/approval record, never a request payload alone.
- [ ] Expired access renders a distinct "access expired" state, not a generic error or a "never enrolled" empty state.
- [ ] Reactivation always produces a new order/payment/ledger entry; original expired payment record is untouched (append-only test).
- [ ] Bulk extension and student-specific override are both audit-logged with actor/tenant/scope.
- [ ] Reminder notifications are event/schedule-driven, not computed on every page load.
- [ ] Cross-tenant negative test on expiry-rule configuration and reactivation-approval queues.
- [ ] A bulk expiry extension job scoped to Tenant A must not touch Tenant B's enrollments.

## 9. Audit requirements

**Mandatory.** "Access/expiry extensions" and "reactivation approvals" are both explicitly named
in `.claude/rules/security.md`'s mandatory audit-log action list.

## 10. MVP or later-phase classification

**SPLIT — not purely later-phase.** Course-level expiry + reactivation-request/admin-approval
**core** = **MVP, shipped (MVP-012)** (FR-EM-2 "MVP (course)", FR-EM-3 "MVP") — see
`docs/adr/ADR-013-enrollment-lineage-and-reactivation-order-gate.md` and
`docs/api/enrollment-management.md` for the shipped design/contract. Session/material/video
expiry + expiry rules engine/grace period/auto reminder/bulk extension/student override remain
**Phase 2, not yet built** (FR-EM-2 "Phase 2 (session/material/video)", FR-EM-4). Treating
"Smart expiry" as a single later-phase feature misrepresents that the core course-expiry +
reactivation flow is already shipped — see [09-enrollments.md](./09-enrollments.md) for the
MVP-scoped baseline.

## UI-state and portal notes

- **Portal placement**: Tenant Admin `Access & Expiry > Expiry Rules`, `Reactivation Approvals`; Student `Payments > Reactivation`, Dashboard alerts.
- Proactive alert before expiry is sourced from notification-management, not computed client-side from a locally cached expiry date.
- On accessing expired content: a distinct access-expired state, explicitly "not a generic error, not a permission-denied state," with a "Reactivate" CTA.

## Open decisions

- Exact grace period length(s) — still open; this MVP implements a hard cutover with no grace period at all (not a "grace period = 0" ratification, simply not built).
- Exact expiry-rules-engine precedence order — still open; do not assume it mirrors device-limit precedence without a separate confirming decision. Not built in this MVP.
- Whether reactivation always requires a full new payment or a prorated/partial payment is ever allowed — still open; this MVP always requires a full new order at the course's current price.
- Whether bulk expiry extension requires a second-approver step — moot for this MVP; bulk extension itself is out of scope.
- Exact reminder timing before expiry — moot for this MVP; no reminder feature exists.
- **Whether Finance Staff or Institute Owner (or both) approves reactivation requests — implementation note (MVP-012): deferred to the already-shipped RBAC matrix, not a new business ratification.** Only Tenant Admin holds `ACCESS_EXPIRY`/`APPROVE`, so only Tenant Admin can approve/reject in the shipped implementation — this follows existing, already-approved code rather than deciding the question independently. Whether Finance Staff should *also* gain this permission remains genuinely open; see `docs/requirements/open-decisions.md` §18.
- **Whether reactivation always requires Tenant Admin approval or only when the tenant configures it that way — implementation note (MVP-012): approval is unconditionally required for every tenant.** No tenant-configurable "skip approval" toggle exists in the shipped code. Whether it should ever become tenant-configurable remains genuinely open; see `docs/requirements/open-decisions.md` §18.
