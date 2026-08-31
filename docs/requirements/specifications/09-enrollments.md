# Enrollments

**Domain:** `enrollment-management` (Modules 13/18) · **Portal(s):** Student, Tenant Admin

## 1. Business purpose

The single security/finance-critical junction where "money confirmed" becomes "access granted" —
must be provably tied to confirmed payment/approved evidence, never inferred or user-triggered.

Source: `docs/architecture/enrollment-access.md` §1-2.

## 2. Actors

- **Student** — initiates order, receives access
- **Finance Staff / Tenant Admin** — approve slips, which indirectly trigger activation
- **`enrollment-management`** (backend) — sole activation authority; no human role "activates" an enrollment directly

## 3. Preconditions

A confirmed `Payment` (gateway) or an `APPROVED` manual slip exists for the student's order.

## 4. Normal flow

1. Payment/ledger state reaches `CONFIRMED` (gateway) or slip reaches `APPROVED` (manual).
2. In the **same transaction** as that state change, `enrollment-management` activates the enrollment.
3. Activation carries a **FK/NOT NULL trail** back to the specific confirmed payment or approved slip row — never a bare boolean flag.
4. Student gains access to course/session/material/video per the enrollment's access window.
5. Course/session/material/video expiry is independently evaluated per type.

## 5. Alternative flows

- Reactivation after expiry: does not reactivate by itself — requires a **new** confirmed payment/approved slip tied to a **new** order, gated by an admin approval step that is unconditionally required for every tenant (shipped MVP-012 behavior — no tenant-configurable skip exists). See [18-smart-expiry.md](./18-smart-expiry.md).
- Bulk expiry extension / student-specific override: admin/staff action, audit-logged, does not touch payment/ledger history.
- Attempted activation from a frontend "success" payload alone: structurally impossible — no endpoint accepts client-reported payment success as evidence.

## 6. Authorization rules

No dedicated "Enrollments" row exists in `user-roles-and-permissions.md` §2; the closest
applicable row is "Access & expiry / reactivation": Institute Owner = `V/C/E/A`; Finance Staff =
`V` (approve if finance-adjacent); Student Support = `V`; Read-only Auditor = `V`; others = `—`.

## 7. Tenant rules

Enrollment table is tenant-owned. `enrollment.course_id -> course.id` must be same-tenant,
enforced via composite FK, not service-layer check alone. No platform-admin cross-tenant bypass
for enrollment records — aggregate views belong to `reporting-analytics`.

## 8. Acceptance criteria

- [ ] Given no persisted `CONFIRMED` payment or `APPROVED` slip exists, then no code path can activate enrollment, even given a plausible-looking request payload.
- [ ] Given a `CONFIRMED` payment is persisted, then enrollment activation and the payment confirmation commit together — no window where one exists without the other.
- [ ] Given an enrollment is active, then querying its activation source returns a specific, non-null FK to the confirmed payment or approved slip row that authorized it.
- [ ] Given a course's access window lapses, then the student sees a distinct "access expired" state (not a generic error, not permission-denied) with a "Reactivate" CTA.
- [ ] Given a reactivation, then it always produces a **new** order/payment/ledger entry; the original expired payment record is untouched.
- [ ] Given a bulk expiry extension by an admin, then it is audit-logged (actor, tenant, scope, before/after).
- [ ] Cross-tenant negative test on enrollment read/list.

## 9. Audit requirements

**Mandatory** — two distinct items from `.claude/rules/security.md`'s list apply: "access/expiry
extensions" and "reactivation approvals." Entries must capture actor, tenant, target
enrollment/student, timestamp, and before/after.

## 10. MVP or later-phase classification

**MVP, shipped (MVP-012)** for activation, course-level payment-based expiry, reactivation
core (FR-EM-1/2/3; change-controlled per root `CLAUDE.md`) — see
`docs/adr/ADR-013-enrollment-lineage-and-reactivation-order-gate.md` and
`docs/api/enrollment-management.md` for the shipped design/contract. Session/material/video
expiry and the full expiry rules engine (grace period, bulk extension, student override)
remain **Phase 2, not yet built** (FR-EM-4).

## Change control flag

Enrollment activation rules are a named change-controlled area in `CLAUDE.md`. Any new
activation trigger, or any code path that activates enrollment other than the two allowed paths
(verified backend payment confirmation, approved manual evidence), requires an ADR before
implementation, not after.

## UI-state and portal notes

- **Portal placement**: reflected in Student `Courses > My Courses` (access unlocks only once backend confirms) and `Courses > Catalog / Browse More Courses`; Tenant Admin `Access & Expiry > Reactivation Approvals`. No dedicated "Enrollments" screen exists in `screen-map.md` — enrollment is the *result* of the payment journey, not a standalone list.
- Empty state: "no active enrollments yet" with CTA to course catalog.

## Open decisions

- **Whether reactivation always requires Tenant Admin approval or only when a tenant configures
  it that way — implementation note (MVP-012): approval is unconditionally required for every
  tenant.** No tenant-configurable "skip approval" toggle exists in the shipped code. Whether
  it should ever become tenant-configurable remains genuinely open; see
  `docs/requirements/open-decisions.md` §18.
- **Whether Finance Staff or Institute Owner (or both) is the correct reactivation approver —
  implementation note (MVP-012): deferred to the already-shipped RBAC matrix, not a new
  business ratification.** Only Tenant Admin holds `ACCESS_EXPIRY`/`APPROVE`, so only Tenant
  Admin can approve/reject in the shipped implementation. Whether Finance Staff should *also*
  gain this permission remains genuinely open; see `docs/requirements/open-decisions.md` §18.
