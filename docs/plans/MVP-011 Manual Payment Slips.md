# MVP-011 — Manual Payment Slips — Module Plan

**GitHub issue:** #11 — https://github.com/mohanranaweera/lms-saas-platform/issues/11 (fetched via `gh issue view 11`)
**Backlog source:** `docs/planning/product-backlog.md`, MODULE 11 (stories `SLIP-1`..`SLIP-4`)
**Spec source:** `docs/requirements/specifications/08-manual-payment-slips.md`, `docs/requirements/specifications/25-duplicate-payment-slip-detection.md`
**Backend domain:** `payment-management`'s Payment Slip Intelligence sub-module (`payment_slip`, `payment_slip_flag`), extending `enrollment-management`'s existing minimal activation slice, and — per §21's central scoping decision — a possible minimal `audit-log-management` slice

This plan was produced by delegating to seven specialist agents in parallel (product-requirements-analyst, solution-architect, database-architect, security-reviewer, qa-test-engineer, ui-ux-reviewer, payment-ledger-specialist), each grounded directly in the requirements/architecture/ADR corpus, the shipped MVP-010 codebase (`student_order`/`payment`/`ledger_entry`/`payment_refund`/`enrollment`, merged to `main`), and the actual current repository state, then reconciled into one document — mirroring the process `docs/plans/MVP-010 Order and Payment Foundation.md` used. This is a **plan only** — no application files, migrations, or tests were created or edited.

**Grounding note on current repository state**, verified directly before delegating: `main` now contains a fully implemented, merged `payment-management` (`student_order`, `payment`, `payment_refund`), `ledger-settlement-management` (`ledger_entry`), `integration-management` (`PaymentGatewayApi`, webhook receiver, `FakePaymentGatewayAdapter`), and a minimal `enrollment-management` slice (`enrollment`, `EnrollmentActivationApi.activateFromConfirmedPayment` only) — all from MVP-010 (Flyway `V1`-`V20`). `docs/adr/ADR-010-ledger-entry-type-and-enrollment-slice.md` and `docs/adr/ADR-011-webhook-tenant-resolution-carve-out.md` are the retroactive ADRs covering MVP-010's own change-controlled decisions. **No `com.lms.auditlogmanagement` package exists anywhere in the codebase** (confirmed via `Glob`, independently, by four of the seven specialist agents) — Module 19 (`audit-log-management`) does not exist as code. This is the single sharpest blocker for this module, named as such by the GitHub issue itself ("the sharpest forward-dependency in the entire backlog").

Per root `CLAUDE.md`, this plan does not invent unresolved business decisions. Every genuine ambiguity or cross-document contradiction independently corroborated by more than one specialist (not a single opinion) is flagged explicitly in §21, not resolved here.

---

## 1. Business goal

Let a student pay for a course out-of-band (e.g. bank transfer) by uploading evidence — a reference number plus a slip image/PDF — for human review, as a materially different trust model from MVP-010's gateway-webhook path: human judgment substitutes for a cryptographically verified webhook signature, and must be held to the same activation rigor as a result. The backend automatically screens every submitted slip for exact-match duplicates (same reference number, same image hash — both strictly tenant-scoped) before a human reviewer ever sees it, presents the reviewer with a queue and the backend-computed flags, and lets an authorized reviewer (Finance Staff or Institute Owner) approve or reject. Approval atomically activates enrollment in the same transaction — never a two-step "approve, then separately activate," and never client-triggered. A reviewer may override a duplicate/suspicious flag and approve anyway, but only by supplying a reason, captured in a mandatory, non-skippable audit log entry — spec 08 §9 and spec 25 §9 both independently call this "the single most explicitly, repeatedly-stated audit requirement" in the entire ruleset.

This is Phase 1 scope (`docs/architecture/payment-ledger.md` §2.1's "two payment confirmation paths": automated/gateway, and manual payment slip). MVP scope is **exact-match duplicate detection only** — OCR-based reference extraction is explicitly Phase 3 (spec 25 §10); no OCR-dependent logic is built here.

Sources: `docs/requirements/specifications/08-manual-payment-slips.md` §1; `docs/requirements/specifications/25-duplicate-payment-slip-detection.md` §1; `docs/architecture/payment-ledger.md` §3-4; GitHub issue #11.

## 2. Roles and permissions

Per `docs/requirements/user-roles-and-permissions.md` §2 "Payments / slips" row, confirmed as the actually-shipped, already-wired RBAC matrix (`backend/src/main/java/com/lms/identityaccessservice/service/PermissionCheckServiceImpl.java` lines 141-142, 158-159, 183, 216) — **no new `DomainArea` or grant plumbing is needed**, `PAYMENTS_SLIPS` already exists and is already granted correctly:

| Role | `PAYMENTS_SLIPS` grant | May do |
|---|---|---|
| Institute Owner (`TENANT_ADMIN`) | `VIEW`, `CREATE_EDIT`, `APPROVE` | Review queue, approve/reject, override-with-reason. **Both this role and Finance Staff independently hold `APPROVE`** — source of the unresolved precedence question (§21 item 1) |
| Finance Staff (`FINANCE_STAFF`) | `VIEW`, `CREATE_EDIT`, `APPROVE` | Same as above |
| Student | not in staff matrix | Uploads only their own order's slip; no queue visibility, no approve/reject/override; a student calling the reviewer transition endpoint directly is denied by the matrix's default-empty-grant for `STUDENT` |
| Student Support | `VIEW` only | Read-only; rejected 403 server-side on every state-changing endpoint even given a stale UI |
| Read-only Auditor | `VIEW` only (full, tenant-scoped) | Read-only |
| Course Coordinator, Content Manager, Exam Manager, Attendance Operator | `—` | No access; any reachable endpoint is a defect |
| Platform Admin | not in tenant staff matrix | Cross-tenant oversight only, out of scope (mirrors MVP-010's deferral) |

Per `.claude/rules/payments.md` §8 and `DomainArea.PAYMENTS_SLIPS`'s own javadoc (`DomainArea.java` lines 14-24), a passing `hasPermission(PAYMENTS_SLIPS, APPROVE)` check is a **coarse category grant only** — it never by itself proves the slip is actually in `UNDER_REVIEW`, that both duplicate checks have run, or that the override-reason gate was satisfied. Every SLIP-3/4 mutation endpoint must independently re-verify all three, regardless of what the permission check returns — exactly the discipline `RefundService`/`OrderService` already apply for the sibling `payment-management` endpoints.

## 3. Preconditions

- An `Order` exists for the student/course, and the student has chosen the manual-slip payment method at checkout — `student_order` already exists and ships this seam (MVP-010, merged).
- `EnrollmentActivationApi` exists but currently exposes **only** `activateFromConfirmedPayment(paymentId, studentId, courseId)` — despite its own javadoc anticipating "the future Module 11 slip path," **`activateFromApprovedSlip(...)` does not exist yet** and must be built from scratch by this module, including its own independent re-verification call (a new `SlipStatusApi`, mirroring `PaymentStatusApi.isConfirmedForCurrentTenant`).
- `enrollment.activating_slip_id` exists as a nullable column with **no FK constraint** — `V19`'s own header comment requires a **new** migration to add `fk_enrollment_activating_slip` once `payment_slip` exists; `V19` itself is never edited. `ck_enrollment_exactly_one_activation_source` already enforces the "exactly one activation source" invariant this module's activation path slots into.
- `ledger_entry.entry_type` is currently a closed two-value CHECK enum (`PAYMENT_CONFIRMED`, `REFUND`) per `ADR-010`, which explicitly reserved `payment_id` as nullable "to leave room for a future non-payment-table source... when Module 11 is built" but explicitly did **not** pre-approve a third `entry_type` value — "no such type may be added without a new ADR amending this one."
- `DomainArea.PAYMENTS_SLIPS` and its RBAC grants already exist and are correctly wired (§2) — no new permission plumbing needed.
- **No `audit-log-management` domain exists anywhere in the codebase.** `RefundService`/`PaymentConfirmationService` (MVP-010) both currently substitute a structured `log.atInfo()` line, explicitly documented in their own javadoc as "an interim measure only, not a substitute for that module's eventual durable audit trail." This is the largest open precondition gap for this module — see §21 item 2.
- `content-management`'s `ObjectStorageApi`/`ContentSniffer`/`MaterialAccessGuard` (Module 9, merged) are the established precedent for upload validation, signed-download-URL protected access, and owner/role guard shape — this module should mirror that pattern's *mechanism*, not import it directly (that module's storage port is not exported as a cross-module `api` contract; see §9).

## 4. User flows

### Normal flow (spec 08 §4; SLIP-1..4; issue #11)
1. Student opens Payment Slip Upload for an existing `Order` they own, enters a reference number, uploads a slip image/PDF.
2. Backend validates server-side (magic-byte content sniffing — not extension/declared Content-Type — size, ownership) before any write; reject with **zero partial write** to storage or DB on any failure.
3. Slip persists as `SUBMITTED`; Payment History shows "Submitted — under review," explicitly, visually distinct from "paid"/"confirmed"; course access stays locked.
4. Backend runs the duplicate-reference and duplicate-image-hash checks, both structurally tenant-filtered via `TenantAwareRepository` (never an incidental `WHERE`).
5. Slip appears in the tenant's Manual Slip Review Queue as `UNDER_REVIEW`, with any flags visible.
6. Reviewer (Finance Staff or Institute Owner) opens Slip Detail, sees backend-supplied flag results (frontend performs zero duplicate-detection logic itself, per spec 25's explicit UI-state note).
7. Reviewer approves (`APPROVED`, reviewer identity + timestamp recorded) or rejects (`REJECTED`).
8. On approval: slip-approval + enrollment activation (+ audit write, if this was an override — §21 item 2) commit in **one transaction**.
9. Student's Payment History re-fetches; course access unlocks only once the backend confirms an active enrollment record.

### Alternative flows
- **Flagged slip, reviewer overrides**: "Approve anyway" is disabled client-side until a reason is entered (UX convenience only); backend independently rejects a reasonless override before any state change or audit write — zero side effects on rejection.
- **Rejection**: one-directional to `REJECTED`; student notified asynchronously (never sharing the approval transaction); enrollment stays inactive; no `REJECTED -> UNDER_REVIEW` reopen.
- **Reversal of an already-`APPROVED` slip**: named only as a "must never be a state rollback" constraint (mirrors PAY-4's refund-as-new-row pattern) — no story here builds the actual reversal path.
- **Duplicate approval attempt (idempotency)**: approving an already-`APPROVED` slip a second time must not double-activate enrollment or double-write a ledger entry — enforced via the state machine (terminal `APPROVED`) plus the same `uq_enrollment_tenant_student_course` guarantee PAY-2 already relies on.

### Flows genuinely underspecified (flagged, not resolved — §21)
- Approver precedence when both Finance Staff and Institute Owner are eligible on the same slip.
- Slip resubmission after `REJECTED` — no document says whether a student may resubmit against the same `Order`.
- What happens to `student_order.status` when its slip is `REJECTED` (parallel to MVP-010's already-flagged, still-open order-abandonment gap).
- A second slip upload against an `Order` that already has a `CONFIRMED` gateway payment or an already-`APPROVED` slip.
- Empty states: "no pending slips" must be visually/textually distinct from "no slips match your filter" (spec 08 §8) — same two-state pattern PAY-3 already established for the Payment Dashboard, reusable but not yet built for this queue.

## 5. Acceptance criteria

Reconciled from spec 08 §8, spec 25 §8, and issue #11's own AC list, organized by story.

**SLIP-1 (Upload + server-side validation)**
- Upload endpoint validates MIME/content-sniffing (magic bytes, not extension/declared type), size, and ownership (order belongs to the uploading student) server-side, before any write; reject with zero partial write to storage or DB.
- `payment_slip` row created only on full validation success: `tenant_id NOT NULL` + FK, composite FKs `(tenant_id, order_id)`/`(tenant_id, student_id)`, index `(tenant_id, status)`.
- Slip enters `SUBMITTED`; distinguishable from "paid" in Payment History; course access stays locked; `SUBMITTED` alone never reachable from any activation call site (tested).
- Slip file never reachable via a direct/predictable URL — every fetch passes a tenant + (owning student OR staff `VIEW`) authorization check before a signed URL is minted.
- Cross-tenant negative test: student of tenant A cannot upload against or view tenant B's order/slip.
- Accessible upload control: clear label naming accepted formats/size (mirrors `material-upload-form.tsx`'s established pattern).

**SLIP-2 (Duplicate detection, exact-match only)**
- Both duplicate-reference and duplicate-image-hash checks run automatically on submission, structurally tenant-filtered.
- No approval code path can reach `APPROVED` without both checks having run (re-verified inside the approve transaction, not trusted from upload-time flag state alone).
- Same reference/hash within one tenant IS flagged; same reference/hash across two different tenants is explicitly NOT flagged — both directions independently tested (spec 25 §8's named "dual-direction" test).
- A flagged slip is auto-flagged only, never auto-rejected — tested.
- Re-running checks adds a new flag row; never clears/overwrites a prior one — full flag history stays queryable, tested.
- No OCR-dependent logic anywhere.

**SLIP-3 (Review queue + approve/reject, atomic activation)**
- Approval and enrollment activation commit in one local transaction, via a new `EnrollmentActivationApi.activateFromApprovedSlip(...)` with its own independent re-verification against a new `SlipStatusApi`, idempotent via the existing `uq_enrollment_tenant_student_course` constraint.
- Simulated mid-transaction failure test: activation-half failure rolls back the slip-approval write too.
- State machine is one-directional: no code path allows `APPROVED -> SUBMITTED` or any backward transition — structural test.
- Rejection: `REJECTED`; student notified asynchronously; enrollment stays inactive.
- Idempotency: approving an already-`APPROVED` slip a second time is a no-op — no double activation, no double ledger write (if any — §21 item 3).
- Only Finance Staff/Institute Owner may transition `UNDER_REVIEW -> APPROVED|REJECTED`; Student Support/Read-only Auditor rejected 403 server-side.
- Minimum audit entry on every approve/reject: reviewer identity + timestamp — **subject to §21 item 2's blocker**.
- Cross-tenant negative test on every slip endpoint (upload, detail, signed-URL, review-queue list, approve, reject, override-approve).
- Two distinct empty states on the review queue.

**SLIP-4 (Override-with-reason + mandatory audit)**
- "Approve anyway" disabled client-side until a reason is present (UX convenience only, backend independently re-enforces).
- Backend rejects a reasonless override **before any state change or audit row is written** — zero side effects on rejection, tested.
- A valid override writes exactly one audit entry containing, at minimum: reviewer identity, tenant, slip/reference id, the specific flag(s) overridden, the reason, and a timestamp — in the **same transaction** as the override-approval, never a separate/skippable call.
- The override write path and the audit-write path are the literal same code path.
- Cross-tenant negative test on the override endpoint.
- **This entire story is structurally blocked on §21 item 2's central scoping decision being made and implemented** — no code path can satisfy this AC's literal "audit row" requirement without a real, durable, schema-enforced-`NOT NULL` place to write one.

## 6. Out-of-scope items

- **OCR-based reference extraction / "smart" slip intelligence** — explicitly Phase 3 (spec 25 §10; `module-catalog.md`; FR-PM-3). MVP is exact-match only.
- **Slip reversal-after-approval as a built feature** — named only as a "must never be a state rollback" constraint.
- **Slip resubmission workflow** — no story covers it (flagged §4, §21).
- **Reactivation approval flow generally** (Module 12/`18-smart-expiry.md`'s concern) — only the manual-slip approval mechanism itself is in scope here.
- **Platform Admin cross-tenant slip oversight** — same deferral MVP-010 already established for its Payment Dashboard.
- **`audit-log-management`'s full build-out** (query UI, retention policy, consumption of other domains' pending events like `CoursePriceChangedEvent`) — this module does not build Module 19 in full, only whatever minimal slice §21 item 2 settles on, if any.
- **A new `SLIP_APPROVED` ledger-entry type, or any `entry_type` enum change** — change-controlled, needs its own ADR (§21 item 3), not built speculatively here.
- **A second/tie-breaking approver-precedence mechanism for slip approval** — mechanism-only; no product decision is made or assumed (§21 item 1).

## 7. Domain model

Per `docs/requirements/module-catalog.md`, manual payment slips are `payment-management`'s Payment Slip Intelligence sub-module — not a new top-level domain. Every cross-domain reference is a bare `UUID` column plus a DB-level composite FK, never a JPA entity association across the domain boundary, per `.claude/rules/architecture.md` and the existing `student_order`/`payment` precedent.

| Aggregate | Owning package | Cross-domain references (by id only) |
|---|---|---|
| `PaymentSlip` | `com.lms.paymentmanagement.slip.domain` | `order_id -> student_order.id` (composite `(tenant_id, order_id)`); `student_id -> tenant_user.id` (composite); `reviewer_id -> tenant_user.id` (composite, nullable until reviewed) |
| `PaymentSlipFlag` | `com.lms.paymentmanagement.slip.domain` | `slip_id -> payment_slip.id` (composite, same package) |
| `Enrollment` (extended) | `com.lms.enrollmentmanagement.domain` | `activating_slip_id -> payment_slip.id` (composite, cross-domain — the FK `V19` deliberately deferred) |
| `AuditLog` (if §21 item 2 resolves to a minimal forward-pull) | `com.lms.auditlogmanagement.domain` (new package) | `actor_id -> tenant_user.id` (composite) |

`PaymentSlip` and `PaymentSlipFlag` are tenant-owned per `.claude/rules/tenancy.md`: `tenant_id UUID NOT NULL REFERENCES tenant(id)`, resolved exclusively from `TenantContext`, composite leading-`tenant_id` indexes matching the module's real query shape, every repository extending `TenantAwareRepository<T, UUID>`. `PaymentSlipFlag` is fully append-only (no `updated_at`, no `delete`/`deleteById` exposed anywhere) — matching `ledger_entry`/`payment_refund`'s exact shape. Whether `PaymentSlip` itself needs the same append-only treatment (new row per state transition) or a narrow in-place `status` `UPDATE` (mirroring `payment.status`'s one justified `PENDING -> (CONFIRMED|REJECTED)` transition) is an open design point — §21 item 5.

**Naming note**: `EnrollmentActivationApi`'s existing javadoc refers to a future "`manual-payment-slip-management`" domain — this is stale/inaccurate terminology. `.claude/rules/architecture.md`'s confirmed domain list has no such entry; `payment-management` is authoritative (spec 08's own header line, and the backlog's own package placement throughout SLIP-1..4).

## 8. Database design

Migration version: `V21` is free (highest applied is `V20`). Proposed filename: `V21__create_payment_slip_schema.sql`.

### `payment_slip`

```sql
CREATE TABLE payment_slip (
    id                   UUID PRIMARY KEY,
    tenant_id            UUID NOT NULL REFERENCES tenant (id),
    order_id             UUID NOT NULL,
    student_id           UUID NOT NULL,
    storage_object_key   VARCHAR NOT NULL,
    reference_number     VARCHAR NOT NULL,
    image_hash           VARCHAR NOT NULL,
    status               VARCHAR(20) NOT NULL,
    submitted_at         TIMESTAMPTZ NOT NULL,
    reviewer_id          UUID,
    reviewed_at          TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL,
    updated_at           TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_payment_slip_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_payment_slip_order FOREIGN KEY (tenant_id, order_id)
        REFERENCES student_order (tenant_id, id),
    CONSTRAINT fk_payment_slip_student FOREIGN KEY (tenant_id, student_id)
        REFERENCES tenant_user (tenant_id, id),
    CONSTRAINT fk_payment_slip_reviewer FOREIGN KEY (tenant_id, reviewer_id)
        REFERENCES tenant_user (tenant_id, id),

    CONSTRAINT ck_payment_slip_reference_number_not_blank CHECK (btrim(reference_number) <> ''),
    CONSTRAINT ck_payment_slip_image_hash_not_blank CHECK (btrim(image_hash) <> ''),
    CONSTRAINT ck_payment_slip_storage_object_key_not_blank CHECK (btrim(storage_object_key) <> ''),
    CONSTRAINT ck_payment_slip_status CHECK (status IN ('SUBMITTED', 'UNDER_REVIEW', 'APPROVED', 'REJECTED')),
    -- Mirrors payment.ck_payment_confirmed_requires_reference: a terminal
    -- review decision must carry who/when, never a bare status flip.
    CONSTRAINT ck_payment_slip_reviewed_requires_reviewer CHECK (
        status NOT IN ('APPROVED', 'REJECTED') OR (reviewer_id IS NOT NULL AND reviewed_at IS NOT NULL)
    )
);

CREATE INDEX idx_payment_slip_tenant_status_submitted_at ON payment_slip (tenant_id, status, submitted_at DESC);
CREATE INDEX idx_payment_slip_tenant_order ON payment_slip (tenant_id, order_id);
CREATE INDEX idx_payment_slip_tenant_student ON payment_slip (tenant_id, student_id);
-- Critical duplicate-check lookup indexes (spec 25 §2/§7) — tenant-scoped,
-- NOT unique: a duplicate is a soft flag-and-review gate, never a hard
-- DB-level block (auto-flag, never auto-reject).
CREATE INDEX idx_payment_slip_tenant_reference_number ON payment_slip (tenant_id, reference_number);
CREATE INDEX idx_payment_slip_tenant_image_hash ON payment_slip (tenant_id, image_hash);
```

No unique constraint on `(tenant_id, order_id)`, `(tenant_id, reference_number)`, or `(tenant_id, image_hash)` — deliberately: a rejected slip is terminal, so a resubmission is a new row (mirrors `payment.order_id`'s no-uniqueness precedent), and duplicates must remain insertable since they're flagged, not schema-rejected. `image_hash NOT NULL` assumes synchronous hash computation at upload time — if hashing is deferred to an async job this needs to become nullable with a "hash pending" state (§21 item 6, flagged not decided).

### `payment_slip_flag`

```sql
CREATE TABLE payment_slip_flag (
    id           UUID PRIMARY KEY,
    tenant_id    UUID NOT NULL REFERENCES tenant (id),
    slip_id      UUID NOT NULL,
    flag_type    VARCHAR(30) NOT NULL,
    detected_at  TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_payment_slip_flag_slip FOREIGN KEY (tenant_id, slip_id)
        REFERENCES payment_slip (tenant_id, id),
    -- Minimal, change-controlled enum matching spec 25's MVP scope
    -- (exact-match only). Mirrors ledger_entry.ck_ledger_entry_type's
    -- "adding a third value needs an ADR" precedent.
    CONSTRAINT ck_payment_slip_flag_flag_type CHECK (flag_type IN ('DUPLICATE_REFERENCE', 'DUPLICATE_IMAGE_HASH'))
);

CREATE INDEX idx_payment_slip_flag_tenant_slip ON payment_slip_flag (tenant_id, slip_id);
```

Fully append-only (no `updated_at`, no `delete`/`deleteById` exposed anywhere) — mirrors `ledger_entry`/`payment_refund`'s exact shape, backing spec 25's "never clear/overwrite a prior flag" rule.

### Follow-up: `fk_enrollment_activating_slip`

```sql
ALTER TABLE enrollment
    ADD CONSTRAINT fk_enrollment_activating_slip
    FOREIGN KEY (tenant_id, activating_slip_id)
    REFERENCES payment_slip (tenant_id, id);
```

A **new** statement in this same `V21` migration — `V19` is never edited, per its own header comment.

### Cross-tenant same-tenant-enforcement FK summary

| Child column | Target | Target's `UNIQUE (tenant_id, id)` status |
|---|---|---|
| `payment_slip.order_id` | `student_order (tenant_id, id)` | Already exists |
| `payment_slip.student_id` / `.reviewer_id` | `tenant_user (tenant_id, id)` | Already exists |
| `payment_slip_flag.slip_id` | `payment_slip (tenant_id, id)` | Added by this migration |
| `enrollment.activating_slip_id` | `payment_slip (tenant_id, id)` | Added by this migration — closes `V19`'s documented gap |

### Open schema design points (§21)

- **`payment_slip.status` transition-order enforcement** — a plain CHECK can't express "previous value must have been X." Two options, not decided here: (a) service-layer transition guard + row lock, mirroring `payment.status`'s existing precedent; (b) a `BEFORE UPDATE` trigger, which `.claude/rules/backend.md`'s "prefer schema-enforced invariants for this domain" guidance would favor but is a new pattern not used anywhere else in this codebase. Flagged for implementation-time decision, not a correctness blocker either way.
- **At-most-one-active-slip-per-order** — not in the issue's literal column list; whether a partial unique constraint should cap concurrent `SUBMITTED`/`UNDER_REVIEW` slips per order is unresolved (§21 item 7).

## 9. Backend design

### Package structure

```
com.lms.paymentmanagement.slip.domain      # PaymentSlip, PaymentSlipStatus, PaymentSlipFlag, FlagType
com.lms.paymentmanagement.slip.repository  # PaymentSlipRepository, PaymentSlipFlagRepository (extend TenantAwareRepository; no delete exposed)
com.lms.paymentmanagement.slip.service     # SlipUploadService, SlipDuplicateCheckService, SlipReviewService
com.lms.paymentmanagement.slip.web         # SlipController (student upload/detail), SlipReviewController (staff queue/approve/reject)
com.lms.paymentmanagement.slip.storage     # module-local SlipStorageApi port + UnavailableSlipStorageApi default bean
com.lms.paymentmanagement.api              # + SlipStatusApi, SlipApprovedEvent, SlipRejectedEvent (new)

com.lms.enrollmentmanagement.api           # + EnrollmentActivationApi.activateFromApprovedSlip(...) (extension)
com.lms.enrollmentmanagement.domain        # + Enrollment.fromApprovedSlip(...) factory (extension)
```

### New `api` interfaces

- **`SlipStatusApi.isApprovedForCurrentTenant(UUID slipId)`** — the defense-in-depth re-verification contract `enrollment-management` needs before activating, directly mirroring `PaymentStatusApi.isConfirmedForCurrentTenant`'s exact shape and tenant-context discipline.
- **`EnrollmentActivationApi.activateFromApprovedSlip(UUID slipId, UUID studentId, UUID courseId)`** — a real addition to `enrollment-management`'s already-shipped package from `payment-management`'s PR, the same kind of boundary-crossing `ADR-010` already accepted once for `activateFromConfirmedPayment`. Requires a parallel `Enrollment.fromApprovedSlip(...)` factory enforcing `ck_enrollment_exactly_one_activation_source` at construction time, and reuses the exact `uq_enrollment_tenant_student_course` + catch-`DataIntegrityViolationException` idempotency pattern `EnrollmentActivationService.activateFromConfirmedPayment` already established. **Per `ADR-010`'s own "Required follow-up" section, this specific addition needs its own ADR (or an amendment to ADR-010) before shipping** — ADR-010 accepted the schema groundwork only, not this code path (§21 item 3).

### Storage seam

**Recommend mirroring Module 9's exact precedent**: a module-local `com.lms.paymentmanagement.slip.storage.SlipStorageApi` port (`store`/`delete`/`generateSignedDownloadUrl`, same shape as `content-management`'s `ObjectStorageApi`), plus an `UnavailableSlipStorageApi` fail-closed default bean (503, never a silent local-disk fallback, per `.claude/rules/architecture.md`'s scalability guidance). **Not** a direct import of `content-management`'s port — that port lives under `material.storage`, not under `content-management.api`, so it is not actually exported as a cross-module contract; reusing it directly would itself be a boundary violation of the kind `.claude/rules/architecture.md` warns against. `ContentSniffer`'s magic-byte signature table should either be duplicated locally (small, self-contained) or promoted to a shared `com.lms.common` utility — a genuine, small scoping call flagged for the implementer, not decided here (§21 item 8).

### Transaction boundaries

Per `.claude/rules/backend.md` and the `MaterialService`/`PaymentConfirmationService` precedents:

1. **Upload**: no `@Transactional` spanning the outbound storage call — store the object, then persist `PaymentSlip` (`SUBMITTED`) as a separate, self-transactional write, mirroring `MaterialService.createMaterial`'s explicit "never span a transaction across an outbound call" discipline.
2. **Duplicate-check timing**: run synchronously at upload (flags visible in the queue immediately), but **re-verify inside the approve transaction regardless** — never trust that an upload-time flag snapshot is still authoritative by approve time.
3. **Approve + activate (+ audit) transaction**: one `@Transactional` boundary — slip status write (`APPROVED`), `EnrollmentActivationApi.activateFromApprovedSlip` call, and (if overriding) the audit write all commit together or none do. Mirrors `.claude/rules/backend.md`'s explicit "payment confirmation + enrollment activation must be atomic" rule, extended by the identical logic to the slip path, plus `.claude/rules/payments.md`'s explicit "override-audit write in the same transaction, never a separate/skippable call" requirement. Does not span the outbound storage call (image hash was already computed at upload time).
4. **Idempotent double-approve**: the slip's own `UNDER_REVIEW -> APPROVED` transition needs its own idempotency guard (e.g. a conditional `WHERE status = 'UNDER_REVIEW'` update, or an explicit status check inside the transaction) so a double-submitted approve action doesn't re-run activation/audit side effects twice — same class of guard `PaymentConfirmationService` already applies (`if (payment.getStatus() != PENDING) return;`).

## 10. API contract

Draft only — must be finalized via `review-api-contract` into `docs/api/payment-management.md` before implementation. All responses use `ApiResponse<T>`. No client-supplied `tenantId`/`studentId`/`status`/`reviewerId` is ever accepted.

| Method + path | Auth | Notes |
|---|---|---|
| `POST /api/v1/orders/{orderId}/slips` (multipart) | Student, order owner only | Reference number + file. Server-side MIME sniff, size, ownership checked before any write; `409`/`400` on failure with zero partial write. |
| `GET /api/v1/payment-slips/{slipId}` | Owner student, or staff `PAYMENTS_SLIPS`/`VIEW` | Slip detail + full flag history (append-only, never filtered to latest-only). `403`/`404` uniform for cross-tenant/cross-student. |
| `GET /api/v1/payment-slips/{slipId}/download-url` | Same as above | Returns a short-lived signed URL, never a raw storage key — mirrors `MaterialController`'s `GET /{id}/download-url` pattern exactly. |
| `GET /api/v1/payment-slips/review-queue` | Staff `PAYMENTS_SLIPS`/`VIEW` only (never student) | Paginated `SUBMITTED`/`UNDER_REVIEW` queue, tenant-scoped; response metadata distinguishes zero-data from filtered-empty. |
| `POST /api/v1/payment-slips/{slipId}/approve` | Staff `PAYMENTS_SLIPS`/`APPROVE` | `409` if unresolved flags exist and no `overrideReason` supplied. Atomic with enrollment activation. |
| `POST /api/v1/payment-slips/{slipId}/approve` (override, body: `overrideReason`) | Staff `PAYMENTS_SLIPS`/`APPROVE` | Same endpoint; a blank/missing `overrideReason` when active flags exist is rejected `400`/`422` before any state change or audit write. |
| `POST /api/v1/payment-slips/{slipId}/reject` (body: `reason`) | Staff `PAYMENTS_SLIPS`/`APPROVE` | One-directional terminal transition; no reversal endpoint. |

Every endpoint independently re-verifies tenant/ownership/state server-side per request — the `PAYMENTS_SLIPS` grant is category-only, never sufficient authorization alone. Exact 403-vs-404 convention should follow the uniform choice already made at MVP-010's contract-finalization time (mirrored, not re-litigated here).

## 11. Frontend screens

Scope: Student-side Payment Slip Upload; Tenant-Admin-side Manual Slip Review Queue + Slip Detail (with override-with-reason flow). Reuses the shared component inventory already built by MVP-010/MVP-009 — no new shared table/live-region/state components needed.

### Student portal (`app/(student)/`)

| Screen | Route (indicative) | Key components | States |
|---|---|---|---|
| **Payment Slip Upload** | `student/payments/slip-upload/[orderId]` | Reference-number `Input` (RHF+Zod), file input mirroring `material-upload-form.tsx`'s exact accessible-label/dropzone/helper-text pattern | Loading/submitting (`aria-busy`, `LiveRegion` "Uploading…"); success renders "Submitted — under review," never "paid"-styled copy, course access stays visibly locked; validation error and backend rejection both route through the same `role="alert"` region; permission-denied via `QueryStateBoundary` for the order-context fetch. |

### Tenant Admin portal (`app/(tenant-admin)/`)

| Screen | Route (indicative) | Key components | States |
|---|---|---|---|
| **Manual Slip Review Queue** | `tenant-admin/payments/slip-review` | Existing `data-table.tsx` (same pattern as `payments/dashboard`/`refunds`), `Select` filter (status/date/flagged-only), new "Duplicate Flagged" Status Chip | Two distinct empty states ("no pending slips" vs. "no slips match your filter"), mirroring `dashboard/page.tsx`'s existing zero-data-vs-filtered pattern. |
| **Slip Detail / Duplicate & Suspicious Flags** | `tenant-admin/payments/slip-review/[slipId]` | Flag list/history display (renders backend-supplied flags verbatim, including full history — never computes its own duplicate signal); `AlertDialog`-based Approve/Reject, naming the specific slip/student; override-reason `Input`/`Textarea` + gated "Approve anyway" button | "Approve anyway" `disabled` until reason non-empty (native `disabled`/`aria-disabled`, RHF-schema-driven, keyboard-operable via existing `@base-ui/react` primitives — no bespoke click-only handler); async approve/reject/override status via the existing `LiveRegion` + `role="alert"` pattern; explicit comment noting the client-side gate is UX convenience only, backend independently re-enforces (mirrors `refund-dialog.tsx`'s existing convention). |

### New/reused component inventory

**Directly reusable, no changes**: `data-table.tsx`, `select.tsx` (first real consumer per MVP-010's own note), `badge.tsx` (base primitive), `alert.tsx`/`alert-dialog.tsx`, `live-region.tsx`, all of `components/states/*`, `material-upload-form.tsx`'s pattern (not the component itself — course-material-specific), `refund-dialog.tsx`/`teacher-decision-dialog.tsx`'s dialog structure, `lib/auth/permissions.ts` (extend with a new `canReviewSlips` UX-only helper).

**Genuinely new**: a `SlipStatusBadge` (recommend as a sibling export in the existing `components/payments/status-badges.tsx`, not merged into `PaymentStatusBadge`'s union — slip states are a distinct one-directional state machine per `.claude/rules/payments.md` §2, and merging risks the exact "Submitted looks like Confirmed" vocabulary collision the spec explicitly warns against); reference-number input; a flag list/chip display component supporting full flag history, not just latest.

**Blocking design-system gap, flagged not resolved here**: `frontend/src/components/ui/badge.tsx`'s `badgeVariants` has no `warning` variant — only `default/secondary/destructive/outline/ghost/link`. The "Duplicate Flagged" Status Chip (already enumerated in `component-library-spec.md` §2.10 as warning-colored, `alert-triangle` icon) has no accurate existing variant to render with. This gap was already flagged as unresolved in MVP-010's own plan §11 and remains unresolved — recommend adding a `warning` variant + matching color token as a small, scoped, in-module addition rather than approximating with `outline`/`secondary` (§21 item 9).

## 12. Validation rules

- **Upload**: reference number required, non-blank (exact length/character-set format unspecified anywhere — §21 item 10, flag before implementation). File required, magic-byte-sniffed to PDF/image only (no "notes"/plain-text branch — spec 08 describes only "slip image/PDF," unlike Module 9's broader allow-list). Max size not specified anywhere in source material — must be picked and recorded explicitly at implementation time, not left implicit (§21 item 11).
- **Ownership**: the uploading actor must be the student who owns the target `Order`, resolved from `AuthenticatedPrincipalHolder`/`TenantContext` — never a client-supplied `studentId`/`orderId` pair alone.
- **Override reason**: required, non-blank — empty/whitespace-only rejected before any row is written (mirrors `payment_refund.reason`'s existing `ck_payment_refund_reason_not_blank` pattern).
- **Status transitions**: `payment_slip.status` only legally transitions `SUBMITTED -> UNDER_REVIEW -> APPROVED|REJECTED`; no other transition is valid, enforced at the service layer at minimum (schema-trigger option flagged §8/§21 item 5).
- **Duplicate checks**: exact-match string/hash equality only, within a structurally tenant-filtered candidate set — no fuzzy matching, no OCR.

## 13. Error cases

| Case | Expected behavior | Status |
|---|---|---|
| Client supplies `tenantId`/`studentId`/`status`/`reviewerId` on upload or review | Silently ignored; server-resolved values used | Settled requirement |
| Upload fails MIME/size/ownership check | Rejected before any write; zero partial rows/storage writes | Settled requirement |
| Cross-tenant/cross-student slip access by id | Uniform 403/404, never 200 with filtered data | Settled requirement; exact code TBD at contract time |
| Approve attempted with unresolved flags and no override reason | `409`/`400`/`422` (exact code TBD); zero state change, zero audit row | Settled requirement, blocked on §21 item 2 for the audit-row half |
| Approve/reject attempted by Student/Student Support/Read-only Auditor | `403` server-side regardless of UI state | Settled requirement |
| Double-approve on an already-`APPROVED` slip | No-op; no double activation, no double ledger write | Settled requirement |
| Attempted `APPROVED -> SUBMITTED` or any backward transition | No such code path exists at all | Settled requirement — structural |
| Second slip uploaded against an order with an already-terminal outcome | **No defined behavior anywhere in source material** | Open decision — do not invent (§21 item 4) |
| Slip rejected — what happens to `student_order.status` | **Undefined**, parallel to MVP-010's own already-flagged order-abandonment gap | Open decision — do not invent (§21 item 4) |
| `hasPermission(PAYMENTS_SLIPS, APPROVE)` returns true but slip isn't actually `UNDER_REVIEW` | Endpoint independently rejects the mutation regardless of the coarse grant | Settled requirement |

## 14. Tenant-isolation rules

`tenant_id` is resolved exactly once, at the edge, from the validated session — never from any request body/query/path/header on any slip endpoint.

- **`payment_slip`** — cross-tenant negative test on upload, detail read, download-url, review-queue list (both single-record and list/aggregate). Composite FKs `(tenant_id, order_id)`/`(tenant_id, student_id)`/`(tenant_id, reviewer_id)` enforce same-tenant linkage structurally.
- **`payment_slip_flag`** — duplicate-check queries are structurally tenant-filtered via `TenantAwareRepository`; the mandatory **dual-direction** test (same tenant = flagged; cross-tenant = never flagged) is the single most important test in this module per spec 25 §7-8, since a missing tenant filter here is a cross-tenant data leak disguised as a false-positive duplicate hit.
- **Approve/reject/override endpoints** — cross-tenant negative test with **zero side effects on rejection** (no slip status change, no enrollment row, no audit row), mirroring PAY-4's "zero side effects" refund-rejection test pattern.
- **`enrollment.activating_slip_id`** — same composite-FK-enforced same-tenant linkage as the existing `activating_payment_id` column.
- **Flag-display anti-leak rule**: the review-queue/slip-detail UI must never display another tenant's slip/reference id as a "possible duplicate" hint, per spec 25's explicit UI-state note — worth an explicit negative test on the flag-read API response payload itself, not just the obvious endpoints.

## 15. Security rules

- **Upload validation mirrors Module 9's `ContentSniffer` mechanism exactly** (magic-byte sniffing, never trusts client `Content-Type`/extension) but with a narrower allow-list (PDF + image signatures only, no plain-text/"notes" branch).
- **Protected-content access**: slip file never reachable via a direct/predictable URL — every fetch (student's own slip, reviewer's queued slip) re-runs a tenant + role/ownership authorization check before a signed URL is minted, mirroring `MaterialAccessGuard`'s structural shape.
- **Anti-enumeration**: a student requesting another student's slip id (even same-tenant) should return 404, not 403, per `MaterialAccessGuard`'s established student-role convention — staff/reviewer denials keep the existing 403 (permission-denied)/404 (cross-tenant) split.
- **Authorization for state transitions**: only `TENANT_ADMIN`/`FINANCE_STAFF` (both hold `PAYMENTS_SLIPS`/`APPROVE`) may transition `UNDER_REVIEW -> APPROVED|REJECTED`; every mutation endpoint calls `requirePermission(PAYMENTS_SLIPS, APPROVE)` specifically — never `VIEW` or `CREATE_EDIT`, which the same two roles also hold for slip *creation/editing* and must not be conflated with the higher-trust transition action.
- **The override-with-reason gate is the sharpest requirement in this module.** Server MUST reject a reasonless override before any state change or audit row is written — not merely discourage it client-side; the override write path and the audit-write path must be the literal same code path, no separate/skippable call. **This requirement cannot be fully, durably satisfied until §21 item 2 is resolved** — flagged, not silently worked around.
- **Duplicate-check tenant-scoping is a security control, not just a data-integrity one** — a missing tenant filter here is a cross-tenant information leak (confirming another tenant's slip/reference exists), not merely a false-positive bug.
- **Minimum negative-test set**: cross-tenant slip access (list/detail/download-url/approve/reject/override), cross-student slip access, role-boundary tests (Student Support/Read-only Auditor denied on every mutation), self-approval-by-student denied, auto-reject-never-happens, idempotent-approval, reasonless-override-rejected, one-directional-state-machine.

## 16. Audit requirements

- **Every approve/reject transition** requires a minimum audit entry: reviewer identity, timestamp, tenant, target slip id — on `.claude/rules/security.md`'s canonical mandatory-audit-action list ("payment approvals/rejections").
- **Every override of a duplicate/suspicious flag** additionally requires: the specific flag(s) overridden and the reviewer-supplied reason, written in the same transaction as the override-approval itself — spec 08 §9 and spec 25 §9 both independently call this "non-negotiable."
- **This requirement is currently unimplementable in a fully spec-compliant, durable form**, because no `audit-log-management` schema exists anywhere in the codebase (confirmed independently by four of the seven specialist reviews via `Glob`). MVP-010's own `RefundService`/`PaymentConfirmationService` already hit this identical gap and substituted a structured `log.atInfo()` line, explicitly self-documented as "interim... not a substitute for that module's eventual durable audit trail" — that interim posture is *acceptable* for a best-effort log line, but §4's override gate is a hard **reject-if-missing** requirement on the mutation itself, which a log line cannot structurally enforce (logging frameworks don't participate in the DB transaction and can't roll back an approval on write failure).
- **This module's plan cannot resolve this gap unilaterally — see §21 item 2 for the two live options (minimal forward-pulled `audit-log-management` slice vs. an interim domain-local override-audit table + published event, mirroring MVP-008's `course_price_history` precedent) and the explicit sign-off this decision requires**, mirroring exactly how `ADR-010` required sign-off for MVP-010's own enrollment-slice pull-forward.

## 17. Payment impact

This module extends Phase 1 of the four-phase payment roadmap and introduces no Phase 2/3/4 concept — confirmed by the payment-ledger-specialist review, independently. No settlement/commission, tenant-payout-account, or split-payment concept is scaffolded into `payment_slip`/`payment_slip_flag` or any endpoint here.

- **The `ledger_entry`-derivation question is a genuine, unresolved open architecture item, not something this plan settles.** `.claude/rules/payments.md` §2 / `payment-ledger.md` §3 require "the admin payment dashboard and any payment history surface must be derived from ledger entries **+ slip state**" — but `ledger_entry.entry_type`'s CHECK constraint is currently closed to exactly `PAYMENT_CONFIRMED`/`REFUND` (`ADR-010`), and an `APPROVED` slip has no corresponding `payment` row to attach a `PAYMENT_CONFIRMED` entry to. Two live candidate resolutions exist (not decided here, both change-controlled, both need their own ADR per `ADR-010`'s explicit "no such type may be added without a new ADR amending this one" and `payment-ledger.md` §9's change-control summary): (a) add a third `entry_type` value (e.g. `SLIP_APPROVED`) with `payment_id NULL` and a new nullable `slip_id` column on `ledger_entry`; or (b) treat "ledger entries + slip state" as two independently-queried signals the dashboard/history UI combines at read time, with no ledger entry ever written for the slip path. Until this ADR lands, the payment-history/dashboard requirement is **not fully satisfiable for the slip path** — this must be flagged to whoever implements PAY-3-adjacent dashboard/history wiring, not silently narrowed (§21 item 3).
- **Adding `EnrollmentActivationApi.activateFromApprovedSlip(...)` is itself change-controlled** — `.claude/rules/payments.md` §7 lists "any new way for enrollment to activate" as requiring explicit approval before implementation. `ADR-010` reserved the schema groundwork for exactly this addition but did not itself authorize the code path (§21 item 3).
- **Approver precedence** for slip approval is the same open question already logged for refunds in `docs/requirements/open-decisions.md` §17 — carried forward here, not re-litigated (§21 item 1).
- **The override-audit gate's financial-integrity framing**: a flagged-and-overridden slip approval is exactly the kind of judgment call that must be independently traceable after the fact (a reviewer who waves through a flagged duplicate is asserting something the system itself flagged as suspicious) — treating "no `audit-log-management` schema exists yet" as a footnote rather than a real blocker would undermine the one control this entire module exists to provide.

## 18. Tests

### Unit tests
- Slip state-machine transition validation (legal vs. illegal transitions) at the service layer, mirroring the DB CHECK.
- Upload validation as mocked/pure logic (mirroring `MaterialServiceTest`'s established style): oversized file rejected before full buffering; content-sniffer rejects a disguised file; ownership-check denial calls no storage/DB write; storage failure after validation never orphans a `payment_slip` row.
- Flag-additive-never-cleared logic: a re-run detection result always produces an INSERT, never an UPDATE/DELETE, on the mocked flag repository.
- Duplicate exact-match comparison as pure logic, over a pre-filtered same-tenant candidate set.
- Override-reason presence validation: empty/whitespace/`null` all rejected with zero downstream mock interactions.
- Structural DTO check: no client-settable `tenantId`/`studentId`/`status`/`reviewerId` field on the upload/review DTOs.

### Testcontainers integration tests
1. Valid upload persists `SUBMITTED`, tenant/order-scoped from the authenticated session, never a client-supplied value.
2. Rejection (MIME/size/ownership) leaves zero `payment_slip` rows and zero storage writes.
3. **Mandatory dual-direction duplicate-detection test**: same reference/hash within one tenant IS flagged; same reference/hash across two tenants is NOT flagged.
4. Re-running duplicate checks adds a new flag row, never clears/overwrites a prior one (field-for-field unchanged on the old row).
5. Auto-reject never happens on a flagged slip absent explicit reviewer action.
6. **Approval + enrollment activation in one transaction, with simulated mid-transaction rollback** — mirrors `PaymentConfirmationRollbackIntegrationTest`'s exact technique against the new `activateFromApprovedSlip` call site.
7. **Mandatory idempotency test for approval** — sequential and (recommended, matching the rigor already applied via `PaymentWebhookConcurrencyIntegrationTest`/`RefundIdempotencyConcurrencyIntegrationTest`, both of which exist because a sequential-only test previously missed a real lock-ordering bug in this same module) a genuinely concurrent `CyclicBarrier`-based variant.
8. Override-no-reason-rejected-before-any-state-change: zero side effects (status unchanged, zero audit rows, zero activation).
9. Valid override writes exactly one audit row with all required fields NOT NULL — **blocked, see below**.
10. Structural append-only test for `payment_slip_flag` (and `payment_slip` if modeled append-only, per §8's open design point), extending `AppendOnlyRepositoriesStructuralIntegrationTest`'s existing pattern.
11. DB CHECK constraint schema-enforced test for `payment_slip.status` (raw out-of-enum insert rejected by Postgres itself).
12. `activating_slip_id` FK same-tenant enforcement — a direct cross-tenant insert attempt rejected by the composite FK itself.
13. Slip file never reachable via a direct/predictable URL — signed-URL response shape asserted, no raw storage key ever present.

### Mandatory cross-tenant negative tests
Upload, slip detail read, signed-URL fetch, review-queue list, approve, reject, override-approve — each proving 403/404, never 200-with-filtered-data, with zero side effects on every mutating endpoint. Plus same-tenant role-boundary tests (Student Support/Read-only Auditor denied on every mutation) and same-student-different-student anti-enumeration tests (404, not 403, for a student).

### Playwright E2E
Accessible upload label; "Submitted — under review" rendered distinctly from "paid"; review-queue table with two distinct empty states and the "Duplicate Flagged" chip; approve/reject flow with no double-submit; override-reason-required UI, keyboard-operable; **direct-API-bypass test** (raw fetch with empty `overrideReason`, asserting server-side rejection independent of the UI gate); frontend performs no client-side duplicate-detection logic (network/route inspection); role-gating (Student Support/Read-only Auditor see no controls, and a direct API call from that session still fails server-side).

### Explicitly BLOCKED tests — named, not silently skipped
1. **Every test asserting a written, durable audit-log row** (item 9 above; the SLIP-4 override-audit test) — blocked on §21 item 2's central scoping decision. Until resolved, these tests stop at "the domain state transitioned correctly," mirroring MVP-010 §18's own identical stopping point for PAY-2/PAY-4's audit tests.
2. **`activateFromApprovedSlip` rollback/idempotency tests** — blocked until that method and its FK counterpart actually exist (this module's own implementation step, not an external blocker).
3. **Object-storage integration tests for slip files** — soft-blocked pending §21 item 8's storage-seam scoping call.
4. **Duplicate-check-across-tenants test for `image_hash` specifically** — depends on whether hashing is synchronous or async at upload time (§21 item 6).
5. **All Playwright tests** — blocked on the backend endpoints and frontend screens not existing yet.
6. **Approver-precedence test** — blocked on §21 item 1; do not invent a precedence order to make a test pass.
7. **OCR-based tests** — explicitly out of MVP scope, not pulled forward.

## 19. Documentation changes

- `docs/architecture/payment-ledger.md` — add the manual-slip state machine's full detail (currently only §3 sketches it), and resolve/record the `entry_type`-for-slip-approval question (§21 item 3) once ADR'd.
- `docs/architecture/enrollment-access.md` — confirm `activateFromApprovedSlip`'s implementation matches the same FK-traceability/atomic-transaction/no-frontend-activation rigor already documented for the payment path.
- `docs/api/payment-management.md` — add the slip-upload/review-queue/approve/reject/override endpoints, produced via `review-api-contract` from §10's draft.
- `docs/requirements/specifications/08-manual-payment-slips.md` and `25-duplicate-payment-slip-detection.md` — mark the approver-precedence open decision as still open (do not close it), and record the final resolution once available.
- `docs/requirements/open-decisions.md` §4/§17 — append this module's own carried-forward items: the audit-log-management central scoping decision and its resolution; the `entry_type` question; the `activateFromApprovedSlip` ADR requirement; slip-resubmission/second-slip-on-terminal-order gaps.
- `docs/adr/` — a new ADR is required before finalizing `entry_type` (if option (a) in §17 is chosen), a new ADR (or `ADR-010` amendment) is required before shipping `activateFromApprovedSlip`, and a new ADR is required for whichever option §21 item 2 resolves to (audit-log-management scoping).
- `docs/ui-ux/component-library-spec.md` — record the `warning` Badge variant addition once built (§21 item 9).

## 20. Implementation order

1. **`payment_slip`/`payment_slip_flag` schema + `fk_enrollment_activating_slip`** (`V21`) — no blockers, can start immediately.
2. **`SlipStorageApi` module-local port + `UnavailableSlipStorageApi`** — mirrors Module 9's precedent, no external dependency.
3. **SLIP-1 (upload + validation)** — depends on 1-2.
4. **SLIP-2 (duplicate detection)** — depends on 1, runs alongside/after 3.
5. **`SlipStatusApi` + `EnrollmentActivationApi.activateFromApprovedSlip` + `Enrollment.fromApprovedSlip`** — **blocked on §21 item 3's ADR sign-off** before this step may ship; build the schema/interface skeleton in parallel if desired, but do not merge the activation code path without it.
6. **SLIP-3 (review queue + approve/reject, atomic activation)** — depends on 1-5.
7. **§21 item 2's central scoping decision (audit-log-management)** — **must be resolved before SLIP-4 can ship in a spec-compliant form**; can be worked in parallel with steps 1-6 but is the hard gate on this step.
8. **SLIP-4 (override-with-reason + audit)** — depends on 6-7.

Per root `CLAUDE.md`'s development workflow, backend implementation (steps 1-8) completes and passes review before `implement-frontend` work begins on §11's screens.

## 21. Risks and unresolved decisions

Ranked by how directly each blocks or reshapes implementation.

1. **Approver precedence — unresolved.** Both Finance Staff and Institute Owner hold `PAYMENTS_SLIPS`/`APPROVE`; no document resolves precedence or dual-role interaction when both are eligible on the same slip. Corroborated independently by spec 08's own "Open decisions" section, `user-roles-and-permissions.md` Open Q2, `open-decisions.md` §4 and §17, and MVP-010's plan §21 item 12 (which explicitly named Module 11 as facing "the identical question"). Not this plan's call to make.

2. **Central scoping decision — `audit-log-management` does not exist, and SLIP-4's override-audit requirement is structurally, not just softly, blocked by that absence.** Confirmed independently by four of seven specialist reviews (security, product-requirements, solution-architect, database-architect, payment-ledger, qa) via direct `Glob`/`Grep` — zero code anywhere under `com.lms.auditlogmanagement`. Unlike MVP-010's PAY-2/PAY-4 audit gaps (which could defer to "the domain state transitioned correctly, audit tested later"), spec 08 §9/spec 25 §9 frame the override-audit write as a **gate on the mutation itself** — an invalid/missing audit write must block the approval, which a best-effort `log.atInfo()` line (MVP-010's own precedent for `RefundService`/`PaymentConfirmationService`) cannot structurally provide. Two live options, both requiring explicit product-owner sign-off (mirroring `ADR-010`'s own precedent for MVP-010's enrollment-slice pull-forward), specialists split between them:
   - **(A) Pull forward a minimal `com.lms.auditlogmanagement` slice** (a real `audit_log` table + a narrow `AuditLogApi.record(...)` write method, no read/query UI, no consumption of other domains' pending events) — recommended by the solution-architect and database-architect reviews, reasoning that a module-local shim would duplicate the schema Module 19 needs anyway and fragment the "one canonical audit trail" property, and that ADR-010's own enrollment-slice precedent already establishes this exact pattern is acceptable for a "structurally untestable without it" acceptance criterion.
   - **(B) An interim domain-local override-audit table + a published domain event** (e.g. `SlipOverrideAuditRecord` inside `payment-management`, publishing `SlipOverriddenEvent` in the same transaction so the real `audit-log-management` can later consume it with zero rework) — recommended by the payment-ledger-specialist review, mirroring MVP-008's `course_price_history`/`CoursePriceChangedEvent` precedent, with the explicit caveat (per `open-decisions.md` §16's own language for that precedent) that this must never be reported as "fully meeting" the audit requirement until Module 19 actually exists and consumes the event.
   - **(C) Defer SLIP-4 entirely** until Module 19 lands, shipping only SLIP-1..3 — the fallback ADR-010 itself named for its own analogous decision.
   This plan does not choose between them — it is a product/architecture decision needing the same sign-off ADR-010 required, not something a module plan may decide unilaterally.

3. **`ledger_entry.entry_type` and `EnrollmentActivationApi.activateFromApprovedSlip` are both individually change-controlled additions that `ADR-010` explicitly reserved room for but did not itself authorize.** `ADR-010`'s own "Required follow-up if accepted" section states verbatim that either addition "requires their own new migration and an amendment to (or a new ADR superseding) this one." Two separate ADRs (or one combined ADR, if scoped together) are needed before implementation, not implied by ADR-010's existing acceptance.

4. **Order-status-on-rejection and second-slip-on-terminal-order flows are genuinely undefined** by any source document — parallel to MVP-010's own already-flagged, still-open order-abandonment gap (`open-decisions.md` §17's last bullet). Do not invent a cleanup/resubmission policy.

5. **`payment_slip`'s own append-only-vs-in-place-status-update modeling choice** is not settled by any source document — `payment.status` precedent suggests a narrow, justified in-place update is acceptable for this kind of state machine; `.claude/rules/backend.md`'s "prefer schema-enforced invariants for this domain" guidance could also support new-row-per-transition. Implementation-time decision, not a correctness blocker.

6. **Whether `image_hash` is computed synchronously (blocking the upload response) or asynchronously (requiring a "hash pending" intermediate state)** is not specified by any source document — the schema in §8 assumes synchronous; if async is chosen, `payment_slip.image_hash` must become nullable and this migration should be revisited before merge, not patched around after.

7. **Whether a partial-unique "at most one active slip per order" constraint should exist** — not in the issue's literal column list; a student could otherwise submit multiple concurrent `SUBMITTED`/`UNDER_REVIEW` slips against the same order. Flag for product-owner confirmation before implementation.

8. **`ContentSniffer` duplication vs. promotion to `com.lms.common`** — Module 9's sniffer is package-private to `content-management`. A small, deliberate scoping call for the implementer (duplicate the ~80-line signature table locally, or propose promoting it to a shared utility) — not a blocker either way, but worth a conscious choice rather than an accidental import that violates the module-boundary rule.

9. **`Badge`'s missing `warning` variant blocks an accurate "Duplicate Flagged" Status Chip implementation.** Already flagged as unresolved in MVP-010's own plan §11 and `component-library-spec.md` §2.10's own "Open question"; still unresolved. Recommend adding the variant as a small, scoped, in-module addition rather than approximating with `outline`/`secondary`.

10. **`payment_slip.reference_number`'s format/length validation is unspecified** beyond "duplicate check exists" — no minimum/maximum length or character-set rule anywhere. Mirrors MVP-010's own identical "confirm before implementation" treatment of `payment_refund.reason`.

11. **Upload max file size is not specified anywhere in source material** and must be picked and explicitly recorded at implementation time (spec 08 §8's "rejects on... size... failure" checklist item presumes a concrete limit exists) — not left implicit or inferred from Module 9's own (differently-scoped) material-upload limit without a deliberate check that the two should actually match.

12. **Slip-resubmission workflow after `REJECTED`** — no story or spec addresses whether/how a student may resubmit a corrected slip against the same order. Genuinely underspecified, not an oversight of this plan.
