# Duplicate Payment Slip Detection

**Domain:** `payment-management` — the Payment Slip Intelligence sub-module · **Portal(s):** Student (uploads), Tenant Admin (reviews)

## 1. Business purpose

Reduce fraud/duplicate risk in the manual slip path before a human reviewer has to catch it
manually, via OCR reference extraction plus duplicate reference-number and duplicate image-hash
checks.

Source: `docs/requirements/source-requirements.md` lines 338-349, "Payment Slip Intelligence Module."

## 2. Actors

- **System/backend** — runs checks automatically, server-side
- **Finance Staff / Institute Owner** — reviewer who sees flags and may override
- **Read-only Auditor** — view only

## 3. Preconditions

A slip has been uploaded and is `SUBMITTED`/`UNDER_REVIEW` (see
[08-manual-payment-slips.md](./08-manual-payment-slips.md)); OCR/duplicate-check infrastructure
exists per tenant scope.

## 4. Normal flow

1. On slip submission, backend runs OCR reference extraction (exact-match extraction is **MVP** scope; full OCR-based intelligence is **Phase 3** — see classification below).
2. Duplicate reference-number check runs, scoped to the requesting tenant's slips only (structural tenant filtering, not incidental `WHERE`).
3. Duplicate image-hash check runs, same tenant scoping.
4. Both checks must **pass** (or be validly, audit-logged overridden) before a slip can transition to `APPROVED` — no approval code path may skip either check.
5. If no flag: reviewer proceeds to normal approve/reject flow.
6. If flagged: slip is auto-flagged (allowed) but never auto-rejected (not allowed) — a human reviewer must remain in the loop.

## 5. Alternative flows

- Reviewer overrides a flag and approves anyway: system requires a recorded reason before enabling "Approve anyway"; submitting writes an audit log entry.
- Checks re-run later (e.g. after a detection-logic update): must **add** a new flag/result record — never clear or overwrite a prior flag; full flag history stays queryable.
- Reference number collides across two different tenants: explicitly **not** a duplicate — the check must query within the requesting tenant's slips only.

## 6. Authorization rules

Only an authorized reviewer (Finance Staff or Institute Owner, per `A` permission) may perform an
override. The override write path must be the same code path that produces the mandatory audit
log entry — no UI/API affordance may allow approval-with-flag without going through the
reason-capture step.

## 7. Tenant rules

Duplicate-reference and duplicate-image-hash checks are explicitly tenant-scoped — a reference
number colliding across two different tenants is not a duplicate. This is a named, explicit rule
(not inferred), and any global/cross-tenant duplicate query would be a bug.

## 8. Acceptance criteria

- [ ] No code path transitions a slip to `APPROVED` without both duplicate checks having run.
- [ ] Duplicate check queries are structurally tenant-filtered, not an incidental `WHERE tenant_id = ...` a developer could omit.
- [ ] Test proving a reference number/image hash repeated across two tenants is **not** flagged as duplicate (cross-tenant negative-of-a-negative test).
- [ ] Test proving a reference number/image hash repeated within the same tenant **is** flagged.
- [ ] Override with no reason is rejected by the system.
- [ ] Test proving a flagged slip is never auto-rejected without human action.
- [ ] Test proving re-running checks adds a new flag row and does not delete/overwrite a prior flag.

## 9. Audit requirements

Every override of a duplicate/suspicious flag **must** write an audit log entry containing, at
minimum: reviewer identity, tenant, slip/reference ID, the flag(s) overridden, a reason, and a
timestamp. An override with no recorded reason is not a valid override — the system must reject
it. This is the single most explicit, twice-independently-stated audit requirement in the
ruleset — treat as non-negotiable.

## 10. MVP or later-phase classification

**Split by mechanism — correction to the "later-phase" framing:**
- **MVP/Phase 1**: exact-match duplicate reference-number check and duplicate image-hash check. `module-catalog.md` "MVP (Phase 1 payment scope, manual slip approval, exact-match duplicate checks)"; FR-PM-3 "MVP (exact-match), Phase 3 (OCR-based)."
- **Phase 3**: OCR-based reference extraction / "smart" slip intelligence. `module-catalog.md` "Phase 3 (tenant-specific payment accounts/routing, OCR-based slip intelligence)"; `source-requirements.md` line 664 "Smart payment slip OCR" under Phase 3.
- The manual override + audit logging mechanism itself (the gate/reject-on-no-reason behavior) is **MVP**, since it must exist wherever the exact-match checks exist (FR-PM-4).

**Documentation inconsistency to flag**: `docs/ui-ux/user-journeys.md` Journey 3 describes "backend
has already run OCR reference extraction, duplicate-reference check, and duplicate-image-hash
check server-side" as part of the near-term staff review flow, which could be read as OCR being
available earlier than Phase 3. `functional-requirements.md`'s explicit phase split (FR-PM-3)
should be treated as authoritative over the journey narrative, per `.claude/rules/documentation.md`'s
guidance that FR tables are the living spec — but this should be verified with the requirements
owner, not resolved by assumption.

## UI-state and portal notes

- **Portal placement**: Tenant Admin `Payments > Manual Slip Review Queue`, `Slip Detail / Duplicate & Suspicious Flags`.
- Duplicate/suspicious flag uses the "Duplicate Flagged" Status Chip (warning, alert-triangle icon) — already enumerated in `docs/ui-ux/component-library-spec.md` §2.10.
- The frontend performs no duplicate-detection logic itself — never display cross-tenant "possible duplicate" hints.

## Open decisions

- Whether `user-journeys.md`'s narrative (OCR already running today) reflects an intended MVP scope broader than FR-PM-3 states — needs verification with the requirements owner.
