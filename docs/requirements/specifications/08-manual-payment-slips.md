# Manual Payment Slips

**Domain:** `payment-management` (Module 12/13) · **Portal(s):** Student, Tenant Admin

## 1. Business purpose

Support out-of-band payment methods (e.g. bank transfer) as a materially different trust model
from gateway confirmation — human-reviewed evidence rather than gateway signature verification —
while still gating enrollment activation on the same rigor.

Sources: `docs/architecture/payment-ledger.md` §2.1; `docs/requirements/source-requirements.md`.

## 2. Actors

- **Student** — uploads slip
- **Finance Staff, Institute Owner / Tenant Admin** — reviewer, both hold `A` (approve)
- **Student Support** — `V` only
- **Read-only Auditor** — `V`

## 3. Preconditions

An `Order` exists for the student/course; student chooses manual-slip payment method at checkout.

## 4. Normal flow

State machine: `SUBMITTED -> UNDER_REVIEW -> APPROVED | REJECTED` (one-directional; no
`APPROVED -> SUBMITTED` rollback).

1. Student opens Payment Slip Upload, enters reference number, uploads slip image/PDF.
2. Backend validates upload server-side (MIME/content sniffing, size, ownership).
3. Slip enters `SUBMITTED`; reflected in Payment History as "Submitted — under review," distinct from "paid"; course access remains locked.
4. Backend runs OCR reference extraction, duplicate-reference check, duplicate-image-hash check server-side — see [25-duplicate-payment-slip-detection.md](./25-duplicate-payment-slip-detection.md) for the full mandatory-gate specification.
5. Slip appears in the Manual Slip Review Queue (`UNDER_REVIEW`).
6. Reviewer opens Slip Detail, sees backend-supplied duplicate/suspicious flag results.
7. Reviewer approves (`APPROVED`, recorded with reviewer identity + timestamp) or rejects (`REJECTED`).
8. Approval and enrollment activation happen in **one transaction**.
9. Student's Payment History updates via re-fetch; course access unlocks only on backend-confirmed `APPROVED`.

## 5. Alternative flows

- Flagged duplicate/suspicious slip: reviewer must supply an override reason before "Approve anyway" is enabled; UI must never allow approval without a reason present.
- Rejection: transitions to `REJECTED`, notifies student asynchronously; enrollment stays inactive.
- `SUBMITTED` slip alone never triggers activation by itself.
- Reversal of an already-`APPROVED` slip: modeled as a new ledger correction entry, **not** a state rollback.

## 6. Authorization rules

Only Finance Staff or Institute Owner may transition `UNDER_REVIEW -> APPROVED|REJECTED`
(`user-roles-and-permissions.md` §2, "Payments/slips" row — `A` is a distinct higher-trust action
from create/edit). Activation only follows an authorized reviewer/staff action, never a student
self-service action.

## 7. Tenant rules

Slip records carry `tenant_id`. Duplicate checks are scoped **per tenant** — a reference number
colliding across two different tenants is explicitly **not** a duplicate; the query must use
structural tenant filtering, not an incidental `WHERE` clause.

## 8. Acceptance criteria

- [ ] No approval code path exists that skips the duplicate-reference and duplicate-image-hash checks.
- [ ] Approving twice (idempotency) does not double-activate enrollment or double-write ledger entries.
- [ ] Duplicate-slip detection test: same reference number or same image hash is rejected/flagged.
- [ ] Slip state machine is one-directional; no code path allows `APPROVED -> SUBMITTED`.
- [ ] Upload endpoint rejects on MIME/size/ownership failure with no partial write to storage.
- [ ] Slip file is never reachable via direct predictable URL — every fetch passes an authorization check confirming tenant/enrollment/role.
- [ ] Empty state distinguishes "no pending slips" from "no slips match your filter."

## 9. Audit requirements

**Mandatory and specific.** Approve/reject actions require a minimum audit entry (reviewer
identity, timestamp). Any manual override of a duplicate/suspicious flag **must** additionally
capture: reviewer identity, tenant, slip/reference ID, the flag(s) overridden, a reason, and a
timestamp. An override with no recorded reason is not valid — the system must reject it, not
merely discourage it. This is the single most explicitly, repeatedly-stated audit requirement in
the ruleset — treat as non-negotiable.

## 10. MVP or later-phase classification

**MVP / Phase 1.** `source-requirements.md` line 296, 633; `functional-requirements.md` FR-PM-2
"MVP"; `module-catalog.md` "MVP (Phase 1 payment scope, manual slip approval, exact-match
duplicate checks)."

## UI-state and portal notes

- **Portal placement**: Student `Payments > Payment Slip Upload`; Tenant Admin `Payments > Manual Slip Review Queue`, `Slip Detail / Duplicate & Suspicious Flags`.
- File input needs a clear accessible label with accepted formats/size.
- The override-with-reason control must be keyboard operable and the reason field required; the "Approve anyway" action must be disabled until a reason is present, enforced client-side as UX convenience only (backend independently rejects a reasonless override).
- The frontend performs **no** duplicate-detection logic itself — it only surfaces backend-supplied OCR/duplicate/suspicious-flag results.

## Open decisions

- Whether Finance Staff or Institute Owner (or both, with what precedence) is the correct approver when both are eligible reviewers — unresolved.
