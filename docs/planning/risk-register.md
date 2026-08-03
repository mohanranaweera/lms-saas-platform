# MVP Risk Register

Synthesized from the security-reviewer's dedicated risk analysis of all 61 stories, supplemented
with sequencing/architecture risks the other four agents surfaced independently. Ranked by
severity (Likelihood × Impact). Each risk names the specific stories it's tied to — cross-reference
`docs/planning/product-backlog.md` for the full acceptance-criteria/testing context.

**How to use this register:** review at the start of each wave in `docs/planning/mvp-release-plan.md`
that touches a risk's tied stories. A risk is not "closed" because the story shipped — it's closed
when the named mitigation's test/control is verified passing, ideally as part of that story's own
Definition of Ready/Done gates (see `docs/planning/definition-of-ready.md` /
`docs/planning/definition-of-done.md`).

---

## High likelihood × High impact

### R1 — Enrollment activated from unverified/client-reported payment state
A frontend success-page redirect or a request payload claiming "payment succeeded" activates
enrollment without a persisted, verified backend payment/slip record.
- **Tied to:** ENR-1, PAY-2, SLIP-3
- **Likelihood:** Medium (a natural shortcut under deadline pressure — "activate on redirect, let
  the webhook catch up").
- **Impact:** High — direct financial loss, and violates a named change-controlled rule in
  `CLAUDE.md`.
- **Mitigation:** Structurally prevent any activation code path except the two approved ones
  (confirmed webhook, approved slip). Integration test proving an unconfirmed order can never yield
  an active enrollment. Owned by Wave 3.

### R2 — Missing/incorrect tenant filter in a hand-rolled query bypassing TenantAwareRepository
`APP-4`'s base doesn't protect ad hoc JPQL/Criteria queries or lazy-loaded association traversal —
explicitly acknowledged as a gap in ADR-006 itself, not hypothetical.
- **Tied to:** APP-4 and every subsequent tenant-owned-repository story (STU-1/2/3, PAY-1/3,
  ENR-1, MAT-2/3, EXM-1–5, AUDIT-1–3 — effectively the whole backlog).
- **Likelihood:** High — a one-dev-on-one-PR class of mistake, and the gap is explicitly documented,
  not theoretical.
- **Impact:** High — direct cross-tenant data breach, one of the most severe possible outcomes.
- **Mitigation:** Mandatory cross-tenant negative test per new repository method (already required).
  CI-enforced review checklist confirming every new repository extends `TenantAwareRepository`.
  Consider Hibernate `@Filter` as the defense-in-depth second layer ADR-006 already anticipates if
  review finds a real gap.

### R3 — Protected content served via guessable ID without full per-request re-authorization
A resource ID being "an unguessable UUID" is treated as sufficient access control instead of
running the full tenant+enrollment+role check on every fetch.
- **Tied to:** MAT-3, SLIP-1, and video-adjacent access checks generally.
- **Likelihood:** Medium — "it's a UUID, it's fine" is a common shortcut.
- **Impact:** High — course material/payment-slip/student-document exposure across tenants or
  students; payment slips are financial evidence documents.
- **Mitigation:** Mandatory negative tests for cross-tenant and cross-student ID-guessing on every
  protected-content endpoint before merge.

### R4 — Payment-slip duplicate-check or override-audit gate bypassed via an alternate code path
A second/legacy/admin-tooling approval route transitions a slip to `APPROVED` without running
duplicate checks or capturing an override reason.
- **Tied to:** SLIP-2, SLIP-3, SLIP-4
- **Likelihood:** Medium — multi-entry-point admin tooling ("just fix it" routes) is a common
  source.
- **Impact:** High — fraud/duplicate-payment risk; an unrecorded override is the single most
  emphasized audit requirement in the entire ruleset, so its absence is both a security and
  compliance failure.
- **Mitigation:** Single, non-bypassable service method for the `APPROVED` transition, enforced at
  the service layer. Test asserting no other code path can set slip status to `APPROVED`.

### R5 — Webhook confirmation unverified, or duplicate webhook not idempotent
An unsigned or replayed webhook is trusted, or duplicate delivery double-activates enrollment /
double-writes ledger entries.
- **Tied to:** PAY-2, PAY-3
- **Likelihood:** Medium — signature verification and idempotency keys are easy to under-implement
  under time pressure, and the gateway isn't yet selected (open decision), so the concrete
  verification mechanism is still undefined.
- **Impact:** High — duplicate revenue recognition, or a forged webhook granting free access.
- **Mitigation:** Mandatory idempotency test (same webhook delivered twice → identical ledger/
  enrollment outcome) and signature/HMAC verification enforced before any state persists.

---

## Medium-High likelihood × High impact

### R6 — Tenant identity resolved from a client-controllable value somewhere in the request path
A header, query param, or hidden form field is trusted for `tenant_id` instead of the validated
session/subdomain — worse than a missing filter, because it's explicit trust of attacker-controlled
input.
- **Tied to:** TEN-3, AUTH-1/AUTH-2, and by extension every tenant-owned endpoint.
- **Likelihood:** Low-Medium — the architecture explicitly forbids this, but a convenience
  "tenant_id in the request DTO for testing" pattern is a realistic slip, especially before
  request-scoped context plumbing is fully wired in Wave 0.
- **Impact:** High — a single such endpoint is a full tenant-boundary bypass.
- **Mitigation:** Code-review checklist item — "no repository method that accepts a caller-supplied
  `tenant_id` parameter" — enforced for every new endpoint, not just repositories.

### R7 — Background/async job losing tenant context across the thread boundary
A queued job (notification dispatch, expiry processing) silently operates without an explicit
`tenant_id` in its payload, either failing to filter or picking up an unrelated thread's leftover
context in a pooled worker.
- **Tied to:** NOTIF-1, ENR-2, and any job groundwork touched incidentally elsewhere.
- **Likelihood:** Medium — this is a documented, explicitly-flagged architectural gotcha
  ("background work does not inherit request-scoped context automatically"), meaning it's a known
  trap but still commonly mis-implemented on the first job.
- **Impact:** Medium-High — cross-tenant notification leakage is a privacy incident; cross-tenant
  expiry-processing errors could wrongly revoke or extend access.
- **Mitigation:** Require every job/event payload to explicitly carry and apply `tenant_id` through
  the same structural mechanism as request-time code, verified by a test that runs a job with no
  ambient request context. Owned by Wave 1 (`NOTIF-1`) — get this right once, at the source.

### R8 — Read-only Auditor or low-privilege staff sub-role reaching a mutating endpoint
`RBAC-2`'s per-endpoint check is incomplete or a newly added endpoint isn't covered, as endpoint
count grows across 20+ modules.
- **Tied to:** RBAC-2 (all modules consuming it), STAFF-1, SLIP-3, ENR-3, AUDIT-1.
- **Likelihood:** Medium — endpoint-by-endpoint role coverage is easy to miss for a newly added
  action as the backlog grows.
- **Impact:** Medium-High — privilege escalation for a nominally read-only role; severity depends on
  the endpoint, but device reset/payment approval/audit-log-adjacent mutations would be severe.
- **Mitigation:** Negative-path authorization test required per new protected endpoint (already
  required). Periodic "every mutating endpoint has an explicit Read-only-Auditor-denied test" audit
  before go-live (`INTG-3`).

### R9 — Platform Admin implicitly granted tenant-operational access without an audited impersonation session
`PADASH-2` or any ad hoc admin-support pathway bolts on "just let Platform Admin query as if Tenant
Admin" without the formal dual-identity audit trail.
- **Tied to:** PADASH-2, and TADASH-1 if any support pathway is added ad hoc.
- **Likelihood:** Low-Medium — impersonation is easy to add informally for support convenience.
- **Impact:** High — unaudited impersonation is both a security-control failure and a
  customer-trust/compliance failure.
- **Mitigation:** No implicit tenant-operational access from Platform Admin role. Any impersonation
  must be a backend-issued session with start/end audit entries recording both identities
  distinctly.

---

## Medium likelihood × High impact

### R10 — Audit log table exposing an update/delete path
Most likely via a generic admin panel/database-management tool accidentally exposing the table, or
a "fix a bad audit row" one-off script under incident-response pressure.
- **Tied to:** AUDIT-1, AUDIT-2
- **Likelihood:** Low-Medium.
- **Impact:** High — once audit-trail tampering is possible, every other audit claim in the backlog
  becomes unverifiable, undermining the entire compliance/accountability story.
- **Mitigation:** No update/delete repository method for the audit entity at all (compile-time
  absence, not runtime rejection). Explicit test verifying this.

### R11 — Refund or ledger-correction applied directly to a terminal payment/ledger row
An incident-response "just fix the data" shortcut — explicitly the scenario `.claude/rules/payments.md`
warns against ("no 'fix the ledger row' scripts").
- **Tied to:** PAY-3, PAY-4
- **Likelihood:** Low-Medium — most likely under incident-response pressure.
- **Impact:** High — breaks the append-only financial audit trail, a direct compliance/dispute-
  resolution failure, and violates root `CLAUDE.md`'s "never delete financial history."
- **Mitigation:** No update/delete repository method exposed for terminal payment/ledger entities.
  Any correction requires a new row referencing the original, enforced structurally.

### R12 — Insufficient/incomplete cross-tenant integration suite treated as "done" on partial coverage
`INTG-1`'s test suite has gaps against a 20+-module backlog that look deceptively complete.
- **Tied to:** INTG-1, and transitively every prior tenant-owned-data story.
- **Likelihood:** Medium — full coverage against 61 stories is genuinely hard to verify by
  inspection alone.
- **Impact:** High — this is the last verification gate before go-live; a gap here means every other
  risk in this register ships unverified.
- **Mitigation:** Treat `INTG-1` as blocking for `INTG-3` sign-off, with an explicit checklist
  mapping each tenant-owned table/protected-content endpoint to at least one passing cross-tenant
  negative test — not a general "integration tests pass" statement.

---

## Medium likelihood × Medium-High impact

### R13 — Device-limit/session logic scope mismatch between MVP's AUTH module and the Phase-2 device-auth spec
`16-device-authentication.md` (full device-slot/limit/reset logic) is architecturally Phase 2, but
the MVP skeleton's `AUTH-1`/`AUTH-2` stories are foundational-module MVP — a scope boundary that
must be decided explicitly, not defaulted.
- **Tied to:** AUTH-1, AUTH-2
- **Likelihood:** Medium — the phase mismatch is real and easy to blur under "just build login."
- **Impact:** Medium — account-sharing/session-hijack risk if silently deferred with no decision
  recorded, or cross-tenant device-session leakage if built hastily and incompletely.
- **Mitigation:** `AUTH-1`/`AUTH-2` explicitly scope to login-activity logging only, not device-limit
  enforcement (already reflected in the backlog's PHASE-BOUNDARY FLAG). Confirm this scoping
  decision is recorded, not assumed.

### R14 — Course/session/material/video expiry independently mis-evaluated
Four independent expiry dimensions (course/session/material/video) is a lot of surface for one to
silently default to "always valid" or trigger a premature revocation.
- **Tied to:** ENR-2, MAT-3, and video-adjacent access checks (Phase 2+, but the MVP-scoped
  course-level piece is the immediate concern).
- **Likelihood:** Medium.
- **Impact:** Medium-High — continued access to paid content after revocation is a revenue-integrity
  issue; premature lockout is a customer-trust issue at lower severity.
- **Mitigation:** Explicit test matrix covering each in-scope expiry dimension independently, plus
  the "never issue based on checks that passed in the past" re-verification rule.

### R15 — Reporting/dashboard queries drift into live cross-schema joins instead of api-composed reads
`TADASH-1` and `PADASH-2` are both flagged by the architecture review as a concrete near-term
temptation to violate the `reporting-analytics` no-live-joins guidance under MVP time pressure.
- **Tied to:** TADASH-1, PADASH-2
- **Likelihood:** Medium — aggregation convenience is a classic shortcut.
- **Impact:** Medium — not a security breach per se, but a scalability/architecture-debt risk that
  compounds as tenant count grows, and for `PADASH-2` specifically risks blending tenant data in a
  single aggregate row if done carelessly (escalates toward R2's severity).
- **Mitigation:** BFF-style aggregation of narrow `api` reads per domain, not ad hoc joins — flag
  explicitly in implementation review for both stories.

---

## Process risks (not code defects, but backlog-execution risks)

### R16 — The `AUDIT-1`/`NOTIF-1` pull-forward is not actually honored during implementation
`docs/planning/mvp-release-plan.md` depends on `AUDIT-1`'s schema and `NOTIF-1`'s infrastructure
landing in Wave 1, ahead of their Module 19/18 numbering — if a team executes strictly in
module-number order instead, `SLIP-4` (and five other stories) cannot reach Definition of Done at
all.
- **Tied to:** The release-plan's Wave 1 vs. the backlog's literal module order.
- **Likelihood:** Medium — module numbering is the natural, intuitive execution order; the
  reordering rationale must be actively communicated, not assumed to be obvious.
- **Impact:** High — blocks the entire payment/slip cluster (Wave 3) from closing.
- **Mitigation:** Treat `docs/planning/mvp-release-plan.md`'s wave order, not the backlog's module
  numbering, as the sprint-planning source of truth. Reference `docs/planning/dependency-map.md`'s
  forward-reference table at sprint-planning time.

### R17 — Open decisions silently resolved by whichever developer hits them first
`docs/requirements/open-decisions.md` lists ~30 unresolved items; several map directly into this
backlog (audit-scope gaps, approver precedence, registration model). Left unmanaged, individual
developers will each make an ad hoc call, producing inconsistent behavior across stories that touch
the same open question.
- **Tied to:** STAFF-1/2, TCH-1, TEN-1, SLIP-3, ENR-3, AUDIT-2, EXM-5 (all flagged with open
  decisions in the backlog).
- **Likelihood:** Medium-High if untracked; low if actively gated.
- **Impact:** Medium — inconsistent behavior, rework when the real decision is eventually made.
- **Mitigation:** `INTG-3`'s go-live review explicitly requires every open decision affecting an
  MVP-scoped story to be resolved-and-recorded or knowingly deferred — not silently dropped. Treat
  this as a running checklist, not a one-time review.

---

## Risk summary table

| ID | Risk | Severity | Primary stories |
|---|---|---|---|
| R1 | Enrollment activated from unverified payment state | High/High | ENR-1, PAY-2, SLIP-3 |
| R2 | Tenant filter bypassed via hand-rolled query | High/High | APP-4 + all tenant-owned stories |
| R3 | Protected content served via guessable ID | Medium/High | MAT-3, SLIP-1 |
| R4 | Slip duplicate/override gate bypassed | Medium/High | SLIP-2, SLIP-3, SLIP-4 |
| R5 | Webhook unverified or non-idempotent | Medium/High | PAY-2, PAY-3 |
| R6 | Tenant_id trusted from client input | Low-Med/High | TEN-3, AUTH-1/2 |
| R7 | Async job loses tenant context | Medium/Med-High | NOTIF-1, ENR-2 |
| R8 | Low-privilege role reaches mutating endpoint | Medium/Med-High | RBAC-2 + all protected endpoints |
| R9 | Unaudited Platform Admin impersonation | Low-Med/High | PADASH-2 |
| R10 | Audit log update/delete path exposed | Low-Med/High | AUDIT-1, AUDIT-2 |
| R11 | Ledger row mutated directly | Low-Med/High | PAY-3, PAY-4 |
| R12 | Cross-tenant suite incomplete but treated as done | Medium/High | INTG-1 |
| R13 | Device-auth MVP/Phase-2 scope mismatch | Medium/Medium | AUTH-1, AUTH-2 |
| R14 | Expiry dimensions mis-evaluated | Medium/Med-High | ENR-2, MAT-3 |
| R15 | Dashboard live cross-schema joins | Medium/Medium | TADASH-1, PADASH-2 |
| R16 | Release-plan reordering not honored | Medium/High | Wave 1 vs. module order |
| R17 | Open decisions resolved ad hoc | Med-High/Medium | Multiple, see INTG-3 |
