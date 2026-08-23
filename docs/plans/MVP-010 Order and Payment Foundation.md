# MVP-010 — Order and Payment Foundation — Module Plan

**GitHub issue:** #10 — https://github.com/mohanranaweera/lms-saas-platform/issues/10 (fetched via `gh issue view 10`)
**Branch:** `feature/order-and-payment-foundation` (current branch; based directly on `main` at `c707e1e`, no commits yet)
**Backlog source:** `docs/planning/product-backlog.md`, MODULE 10 (stories `PAY-1`..`PAY-4`, lines 592-671), cross-referencing MODULE 12's `ENR-1` (lines 758-779)
**Spec source:** `docs/requirements/specifications/07-orders-and-payments.md`
**Backend domains:** `payment-management` (Order, Payment, PaymentRefund), `ledger-settlement-management` (LedgerEntry), and — per §21's central scoping decision — a **minimal** `enrollment-management` activation slice

This plan was produced by delegating to seven specialist agents in parallel (product-requirements-analyst, solution-architect, database-architect, security-reviewer, qa-test-engineer, ui-ux-reviewer, payment-ledger-specialist), each grounded in the existing requirements/architecture/ADR corpus, the product backlog, the risk register, and the actual current repository state (including reading two **unmerged** sibling feature branches read-only via `git show`), then reconciled into one document. This is a **plan only** — no application files were created or edited.

**Grounding note on current repository state**, verified directly (`git status`, `git branch -a`, `git log`, `find`) before delegating: this branch is cut from `main` at commit `c707e1e`, which contains only `common` (Module 1 shared kernel), `identityaccessservice` (Modules 2/3 — AUTH-1..3, RBAC-1/2), `tenantmanagement` (Module 4 — TEN-1..3), and `usermanagement/staff` (Module 5 — STAFF-1), plus Flyway `V1`-`V10`. **`course-management` (Module 8), `student-management` (Module 6), `teacher-management` (Module 7), and `lessons-and-learning-materials` (Module 9) all exist as fully-implemented but *unmerged* feature branches** (`feature/course-management`, `feature/student-management`, `feature/teacher-management`, `feature/lessons-and-learning-materials`) — none are in `main` yet. `enrollment-management` (Module 12) does not exist as code on any branch. This is not a hypothetical prerequisite gap the way MVP-005's was ("these modules don't exist yet") — the code for two of this module's hard blockers (`course`, `student` identity) **already exists**, just not merged, which changes §20's sequencing recommendation from "wait for these to be built" to "merge these before/alongside this module's PR."

Per root `CLAUDE.md`, this plan does not invent unresolved business decisions. Every genuine ambiguity or cross-document contradiction surfaced by more than one specialist agent independently (corroborating evidence, not a single opinion) is flagged explicitly in §21, not resolved here.

---

## 1. Business goal

Give the platform one server-authoritative, tenant-aware place to record a student's intent to buy a course (`Order`), confirm that money actually moved via a verified payment-gateway webhook (`Payment`), and turn that confirmation into an append-only financial record (`ledger_entry`) that both the student's Payment History and the tenant's Payment Dashboard are derived from — with refunds modeled as new, linked rows rather than mutations. This is Phase 1 of the four-phase payment roadmap (`CLAUDE.md` "Payment roadmap" §1; `docs/adr/ADR-003-centralized-payments-first.md`): the platform centrally collects all student payments through one integration surface, deliberately deferring tenant/tutor settlement (Phase 2), tenant-owned payment accounts (Phase 3), and split payments (Phase 4) to later, separately-approved work.

The module's defining discipline, repeated in every source document, is the strict separation between **order state** ("what the student intends to buy") and **payment/ledger state** ("what money was actually confirmed") — only the latter may ever justify activating enrollment, and never from a frontend success page (`docs/architecture/enrollment-access.md` §2-3). This is explicitly named as the platform's single highest financial-integrity surface (issue #10's own framing) and the risk register's top-severity cluster (`docs/planning/risk-register.md` R1, R5, R11).

## 2. Roles and permissions

Source: `docs/requirements/user-roles-and-permissions.md` §2, "Payments / slips" row: **Institute Owner: `V/C/E/A`** · **Finance Staff: `V/C/E/A`** · Course Coordinator: `—` · **Student Support: `V`** · Content Manager: `—` · Exam Manager: `—` · Attendance Operator: `—` · **Read-only Auditor: `V`**.

- **Student** — initiates order creation and gateway payment; views own Payment History/Outstanding Payments only. Not part of the staff matrix. No permission to approve, edit, or refund anything, and no path exists (or may exist) for a student to trigger a refund.
- **Finance Staff** and **Institute Owner / Tenant Admin** — both hold `V/C/E/A`: full operational authority within their own tenant (view dashboard, process refunds per PAY-4). Both holding `A` is the source of an open approver-precedence question (§21).
- **Student Support** — `V` only. Must be rejected server-side on every state-changing payment endpoint even if a stale UI exposes a control.
- **Read-only Auditor** — `V` only, full oversight, zero mutation.
- **Course Coordinator, Content Manager, Exam Manager, Attendance Operator** — `—`, no access at all to order/payment/ledger data. Any endpoint reachable by these roles is a defect.
- **Platform Admin** — not in the tenant staff matrix; per spec §2/§6, has cross-tenant **dashboard oversight only**, and "no destructive/state-changing payment action may be submitted without the target tenant visibly named." Cross-tenant aggregation is explicitly a reporting-layer concern, never a relaxed query-layer default on `order`/`payment`/`ledger_entry` themselves.
- **`integration-management`** — non-UI actor; owns gateway credentials and webhook signature verification. PAY-2 has a hard cross-module dependency on this domain's `api`, never on a named vendor SDK.

Per `.claude/rules/payments.md` §8, a passing `PermissionCheckService.hasPermission(DomainArea.PAYMENTS_*, ...)` check is a **coarse category grant only** — it authorizes "this actor may generally act in this domain area," never a specific mutation on a terminal-state row. Every payment/ledger mutation endpoint must independently re-verify the append-only/no-terminal-mutation invariants regardless of what that check returns (see §15).

## 3. Preconditions

- Student is authenticated and tenant identity is resolved server-side from the trusted authenticated context — never client-supplied (`.claude/rules/tenancy.md`, `.claude/rules/payments.md` §1).
- The target course exists, is published, and carries a priced `course.price NUMERIC` column (`course-management`'s CRS-1/CRS-4) — `Order` snapshots this price at creation time.
- `Order` creation is server-side only, triggered by an authenticated "Enroll" action — never a client-constructed payload with a client-chosen price or tenant.
- For PAY-2's atomic activation step: a real, callable `enrollment-management` activation `api` must exist to be invoked inside the same transaction as payment confirmation (see §21's central scoping decision — this module's own implementation must provide this minimal slice, since ENR-1 as a full story is Module 12 and would otherwise not exist yet).

**Preconditions this module cannot assume are already satisfied, and must actively resolve as part of implementation sequencing:**

1. **`course-management` and `student-management` are not yet in `main`.** They exist, fully implemented, on `feature/course-management` and `feature/student-management` — but `Order.course_id`/`Order.student_id` need real composite FK targets that only exist once those branches merge (or this branch is rebased onto an integration branch containing both, mirroring the `integration/staff-management-prereqs` precedent already used once in this repo's history). This is a concrete sequencing blocker, not a documentation gap — see §20.
2. **`enrollment-management` does not exist as code anywhere.** PAY-2's and SLIP-3's own acceptance criteria require enrollment activation "in the same transaction" as payment/slip confirmation — structurally impossible to build or test without it. §21 records the recommendation (grounded in `docs/planning/dependency-map.md` and `docs/planning/mvp-release-plan.md`, not invented here) that this module's implementation includes a minimal `enrollment-management` activation slice.
3. **`integration-management` does not exist as code anywhere.** PAY-2's webhook-verification dependency is on an `api` contract that must be designed and minimally stubbed (a fake/test adapter, no real vendor), not consumed from an existing implementation.
4. **No payment gateway is selected** (spec "Open decisions"; `docs/architecture/payment-ledger.md` §10; ADR-003 open questions). PAY-2 must be built against a generic adapter interface only.
5. **Manual payment slip (Module 11) does not exist.** PAY-1's "choose payment method" step must leave a seam for the slip path to plug in later without building slip logic now — not resolved explicitly by any source document; see §21.

## 4. User flows

### Normal flow (spec §4)
1. Student browses the catalog, selects "Enroll" on a published, priced course.
2. Backend creates `Order` server-side: `tenant_id` from trusted context, `student_id` from session, `course_id` + a snapshotted `amount`/`currency` from `course.price` at that instant. `Order.status` starts `PLACED`/`PENDING`.
3. Student chooses a payment method — the gateway path is this module's scope; manual slip is Module 11.
4. Gateway path: student is redirected to/embeds the (unnamed) gateway. **On redirect return, the frontend shows a loading/"awaiting confirmation" state only** — it must never compute or infer "paid" from anything client-visible, including a redirect query parameter that looks successful.
5. The gateway sends a verified, signature-checked, server-to-server webhook. Backend persists `Payment` transitioning to `CONFIRMED`.
6. **In the same local transaction**: `enrollment-management`'s minimal activation slice activates enrollment, with a `NOT NULL` FK trail back to the confirming `Payment` row — never a bare boolean.
7. Frontend re-fetches/polls and unlocks course access only once the backend returns an active enrollment record.

### Alternative / edge flows
- **Payment failed/rejected by gateway** — Payment History shows a failed/rejected state with `role="alert"`; retry path returns to the payment-method step; access remains locked.
- **Payment pending beyond a reasonable interval** — a distinct "pending confirmation" copy (never reused failure copy) with a Support link; no SLA/timeout value is specified anywhere — do not invent one.
- **Duplicate webhook delivery** — must be fully idempotent: identical `Payment`/ledger/enrollment outcome on retry, enforced by a DB-level uniqueness guard (§8), not application-logic-only "check then insert."
- **Refund** — Finance Staff/Institute Owner-initiated; creates a new `payment_refund` row + a new `ledger_entry` with `reverses_entry_id` set; the original `CONFIRMED` `Payment` row is never mutated, and no `UPDATE` code path may exist on a terminal payment row at all.
- **Payment expiry / reactivation** — cross-referenced only, not this module's scope: reactivation always creates a brand-new `Order`/`Payment`, never resurrects the expired one.
- **What must NOT happen, anywhere**: enrollment activation triggered by a frontend "payment succeeded" page/payload; activation reading `Order.status` alone; any activation code path reachable without a persisted `CONFIRMED` payment or `APPROVED` slip.

### Flows named as genuinely underspecified (flagged, not resolved)
- **Order abandonment** — no document anywhere defines what happens to an `Order` that stays `PLACED`/`PENDING` indefinitely (gateway session expires, webhook never arrives). No cleanup/expiry/cancellation state exists for `Order` itself. Genuine gap — see §21.
- **Cross-tenant response shape** — the tenancy rule requires "403/404, never 200 with filtered data" generically, but no document pins the exact status code (403 vs. 404) for this module's endpoints specifically; to be settled at API-contract time (§10).
- **Student Support's exact view contents** — `V`-only on Payments/slips, but no document says whether that view includes amounts or only status; clarify at API-contract stage.
- **Mid-transaction failure** — PAY-2's own testing requirement calls for a simulated failure inside the joint transaction (confirm the `Payment` write also rolls back if the activation half fails) — this is a named test requirement correctly anticipating `enrollment-access.md` §5's atomicity rule.
- **Empty states** — "no payments have been made yet" must be visually/textually distinct from "no payments match the selected filter," on both Student Payment History and Tenant Admin Payment Dashboard.

## 5. Acceptance criteria

Reconciled from spec §8 and backlog PAY-1..4's acceptance-criteria fields.

**PAY-1 (Order creation)**
- `Order` created server-side only; `tenant_id`/`student_id` resolved from trusted authenticated session — a request-body/query-supplied `tenant_id` or price must be structurally ignored, not merely validated against. Testcontainers test: server-resolved tenant/price wins over any client-supplied value.
- `order` table: `tenant_id NOT NULL` + FK to tenant; composite index leading with `tenant_id`; composite FKs enforcing same-tenant `student_id`/`course_id` relationships, not bare-ID FKs.
- Money columns `NUMERIC`, never float; round-trip precision test required.
- `status` is a fixed, DB-`CHECK`-constrained enum.
- `Order.status` is never read by any enrollment-activation code path — enforced and tested, not just documented.
- Cross-tenant negative test on order create/read/list.
- Playwright: checkout UI has no student-editable price field.

**PAY-2 (Gateway adapter + webhook confirmation)**
- Frontend never marks enrollment active on gateway-redirect return, regardless of redirect query params.
- `payment` table: `tenant_id NOT NULL` + FK; `status` DB `CHECK` over `PENDING/CONFIRMED/REJECTED/REFUNDED` (see §21 for a flagged internal contradiction on the `REFUNDED` value); `CHECK (amount > 0)`; composite FK to `order`; index `(tenant_id, status, created_at)`.
- A verified webhook is the *only* path that can transition `payment.status -> CONFIRMED` — structurally, not just by convention; no shared endpoint/DTO exists between "frontend polling status" and "gateway confirms payment."
- Payment confirmation + enrollment activation commit in **one** local transaction. Test: simulate a mid-transaction failure on the activation half; confirm the payment write also rolls back.
- Idempotency: duplicate webhook delivery produces an identical terminal state, enforced by a DB-level uniqueness constraint on the gateway reference (§8), not application-logic-only.
- Failed/rejected payment: `role="alert"` state; retry path; audit log entry written (payment approvals/rejections is a canonical mandatory-audit action).
- Cross-tenant negative test, including on the webhook-tenant-resolution path itself (§14).
- Async status states announced via the shared toast/live-region wrapper; status badges pair color with text/icon.

**PAY-3 (Ledger + Payment History UI)**
- Every `CONFIRMED` payment produces exactly one append-only `ledger_entry` with a traceable link to `tenant_id` and the originating `order`/`payment` — an orphaned entry is a data-integrity bug.
- No repository method exposes `delete`/`deleteById` for `ledger_entry` — a structural test, not a code-review note.
- The admin Payment Dashboard and any "payment history" surface are derived from `ledger_entry` + slip state, never from `order` or a raw upload record.
- `ledger_entry.entry_type` is a fixed, DB-enforced enum; adding/removing a type is change-controlled (needs an ADR).
- Two distinct empty states, testable independently.
- Cross-tenant negative test on ledger read/list/dashboard.

**PAY-4 (Refunds)**
- A refund creates a new `payment_refund` row + a new `ledger_entry` with `reverses_entry_id` pointing at the original entry.
- The original terminal `Payment` row is provably unchanged before/after (Testcontainers field-for-field assertion).
- No `UPDATE` code path exists on any terminal-state `Payment` row.
- Refund amount cannot exceed the original payment amount — enforced via FK + service-layer guard + test (not a bare single-row `CHECK`, since "≤ original" needs cross-row awareness).
- Authorization restricted to roles holding `A`; explicit test that no student self-service refund-trigger path exists.
- Exactly one audit log row per refund action.
- Idempotency test: re-submitting the same refund request does not create a second row.
- Cross-tenant negative test.
- **Not testable/assertable yet**: refund *eligibility* (time window, course-start dependency) is an open policy decision (§21) — acceptance criteria here cover only the mechanism.

**Cross-cutting**
- Every new tenant-owned table has `tenant_id NOT NULL` + FK + a composite leading-`tenant_id` index.
- Every cross-tenant negative test proves 403/404-class rejection, never 200-with-empty-or-filtered-data.
- No endpoint treats `hasPermission(DomainArea.PAYMENTS_*, ...) == true` as sufficient authorization for a terminal-row mutation.

## 6. Out-of-scope items

- **Manual payment slips end-to-end** (Module 11, SLIP-1..4) — upload, duplicate reference/image-hash checks, review queue, approve/reject, override-with-reason. PAY-1's checkout flow must leave a seam for a payment-method branch point, but slip schema/logic/UI belong to a separate module.
- **The enrollment-management domain beyond the minimal activation slice PAY-2/SLIP-3 need** — specifically `ENR-2` (course-level expiry, access-expired state) and `ENR-3` (reactivation workflow) are Module 12 stories, not this module's concern. No student-facing enrollment read/list endpoint, no expiry/reactivation logic, no status beyond a single `ACTIVE` value.
- **Settlement (Phase 2)** and everything under `ledger-settlement-management`'s settlement-run responsibilities — commission calculation, gateway-fee tracking, settlement-run idempotency constraints, payout ledger entries.
- **Tenant-specific payment accounts (Phase 3)** and **split payments (Phase 4)** — no "tenant payout account" column, no split-disbursement logic, anywhere in this module's schema or API.
- **Naming or integrating any specific payment gateway** — none is selected anywhere in the source material; PAY-2 is built only against a generic `integration-management` adapter boundary.
- **Refund window/eligibility policy** — only the append-only, new-row, audit-logged *mechanism* is in scope.
- **AUDIT-1's own infrastructure build-out** — PAY-2/PAY-4's audit-log *writes* need `AUDIT-1`'s schema to exist to actually persist; this module does not build that schema itself (see §21's forward-dependency flag), but must not substitute an ad hoc module-local audit table either.
- **Platform Admin's cross-tenant payment dashboard/settlement-run UI** — named as an actor with read oversight, but the actual Platform Admin screens are a later module (`PADASH-2`); this module only ensures the underlying data doesn't leak cross-tenant if/when that view is built.

## 7. Domain model

Every aggregate below sits in its **owning** domain package per `.claude/rules/architecture.md`; every cross-domain reference is a bare `UUID` column plus a DB-level composite FK — never a JPA entity association across a domain boundary (the existing precedent: `StaffProfile.userId` referencing `identity-access-service`'s `tenant_user` row by id only).

| Aggregate | Owning package | Cross-domain references (by id only) |
|---|---|---|
| `Order` | `com.lms.paymentmanagement.domain` | `course_id -> course.id` (composite `(tenant_id, course_id)`); `student_id -> tenant_user.id` (composite `(tenant_id, student_id)` — see §21 for why `tenant_user`, not `student_profile`) |
| `Payment` | `com.lms.paymentmanagement.domain` | `order_id -> order.id` (same package, composite FK) |
| `PaymentRefund` | `com.lms.paymentmanagement.domain` | `original_payment_id -> payment.id` (same package) |
| `LedgerEntry` | `com.lms.ledgersettlementmanagement.domain` | `payment_id`/`order_id` (cross-domain, raw UUID + composite FK); `reverses_entry_id` (self-reference, same package) |
| `Enrollment` (minimal slice — §21) | `com.lms.enrollmentmanagement.domain` | `student_id`, `course_id` (cross-domain); `activating_payment_id` (cross-domain, raw UUID + FK to `payment.id`); `activating_slip_id` (nullable, FK target does not exist yet — Module 11) |

Every table is tenant-owned per `.claude/rules/tenancy.md`: `tenant_id UUID NOT NULL REFERENCES tenant(id)`, resolved exclusively from `com.lms.common.tenant.TenantContext`, a composite leading-`tenant_id` index matching the module's real query shape, and every repository extends `com.lms.common.persistence.TenantAwareRepository<T, UUID>` rather than hand-rolling `WHERE tenant_id = ...`. `Payment`, `PaymentRefund`, and `LedgerEntry` are append-only per `.claude/rules/backend.md` — no `delete`/`deleteById` exposed anywhere; corrections are new rows. Money columns are `NUMERIC`, never float. `status` columns are DB-`CHECK`-constrained fixed enums, not service-layer discipline alone.

## 8. Database design

> Migration version numbers are **intentionally not assigned** in this plan — see §21. Both `feature/course-management` and `feature/student-management` independently claim `V11` off the same `main` base this branch shares; the real version number for every table below depends on how that collision is resolved at merge time.

### `order` — *flag: `order` is a reserved SQL keyword; rename (e.g. `student_order`) or commit to consistent quoting before implementation (§21)*

```sql
CREATE TABLE "order" (
    id           UUID PRIMARY KEY,
    tenant_id    UUID NOT NULL REFERENCES tenant (id),
    student_id   UUID NOT NULL,
    course_id    UUID NOT NULL,
    amount       NUMERIC(12,2) NOT NULL,
    currency     VARCHAR(3) NOT NULL,
    status       VARCHAR(20) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL,
    created_by   UUID,
    updated_by   UUID,

    CONSTRAINT uq_order_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_order_course FOREIGN KEY (tenant_id, course_id)
        REFERENCES course (tenant_id, id),
    CONSTRAINT fk_order_student FOREIGN KEY (tenant_id, student_id)
        REFERENCES tenant_user (tenant_id, id),
    CONSTRAINT ck_order_amount CHECK (amount >= 0),
    CONSTRAINT ck_order_status CHECK (status IN ('PLACED', 'PENDING'))  -- incomplete enum, §21
);

CREATE INDEX idx_order_tenant_student ON "order" (tenant_id, student_id);
CREATE INDEX idx_order_tenant_status_created_at ON "order" (tenant_id, status, created_at DESC);
```

`amount NUMERIC(12,2) CHECK (amount >= 0)` matches `course.price`'s own `>= 0` convention exactly (a snapshot, so the same "future $0/trial course" reasoning applies). `order.student_id` FKs to `tenant_user`, not `student_profile` — see §21 for the reasoning (existing `course.teacher_id` precedent, plus `student_profile` currently lacking a `UNIQUE (tenant_id, id)` a FK could target).

### `payment`

```sql
CREATE TABLE payment (
    id                UUID PRIMARY KEY,
    tenant_id         UUID NOT NULL REFERENCES tenant (id),
    order_id          UUID NOT NULL,
    amount            NUMERIC(12,2) NOT NULL,
    currency          VARCHAR(3) NOT NULL,
    status            VARCHAR(20) NOT NULL,
    gateway_reference VARCHAR(255),
    confirmed_at      TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_payment_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_payment_order FOREIGN KEY (tenant_id, order_id)
        REFERENCES "order" (tenant_id, id),
    CONSTRAINT ck_payment_amount CHECK (amount > 0),
    CONSTRAINT ck_payment_status CHECK (status IN ('PENDING', 'CONFIRMED', 'REJECTED', 'REFUNDED')),
    CONSTRAINT ck_payment_confirmed_requires_reference CHECK (
        status <> 'CONFIRMED' OR gateway_reference IS NOT NULL
    )
);

CREATE INDEX idx_payment_tenant_status_created_at ON payment (tenant_id, status, created_at DESC);
CREATE INDEX idx_payment_tenant_order ON payment (tenant_id, order_id);
CREATE UNIQUE INDEX uq_payment_tenant_gateway_reference
    ON payment (tenant_id, gateway_reference) WHERE gateway_reference IS NOT NULL;
```

`uq_payment_tenant_gateway_reference` is the concrete schema-level idempotency guard PAY-2's "duplicate webhook must be idempotent" criterion requires — without it, idempotency is service-layer "check then insert" only, the exact race condition the tenancy/backend rules warn against elsewhere. The **one narrow, explicitly-justified `UPDATE`** allowed on this table (per `database-architecture.md` §3's carve-out) is the verified webhook's single `PENDING -> (CONFIRMED|REJECTED)` transition; once terminal, no further `UPDATE` — including for `REFUNDED`, which per §21 should likely never be written to `payment.status` at all (refund state is read from `payment_refund`/`ledger_entry` instead, to avoid a second mutation of an already-terminal row).

### `ledger_entry`

```sql
CREATE TABLE ledger_entry (
    id                UUID PRIMARY KEY,
    tenant_id         UUID NOT NULL REFERENCES tenant (id),
    order_id          UUID NOT NULL,
    payment_id        UUID,
    entry_type        VARCHAR(30) NOT NULL,
    amount            NUMERIC(12,2) NOT NULL,
    reverses_entry_id UUID,
    created_at        TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_ledger_entry_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_ledger_entry_order FOREIGN KEY (tenant_id, order_id)
        REFERENCES "order" (tenant_id, id),
    CONSTRAINT fk_ledger_entry_payment FOREIGN KEY (tenant_id, payment_id)
        REFERENCES payment (tenant_id, id),
    CONSTRAINT fk_ledger_entry_reverses FOREIGN KEY (tenant_id, reverses_entry_id)
        REFERENCES ledger_entry (tenant_id, id),
    CONSTRAINT ck_ledger_entry_amount_nonzero CHECK (amount <> 0),
    CONSTRAINT ck_ledger_entry_type CHECK (entry_type IN (/* pending ADR — §21 */))
);

CREATE INDEX idx_ledger_entry_tenant_payment ON ledger_entry (tenant_id, payment_id);
CREATE INDEX idx_ledger_entry_tenant_order ON ledger_entry (tenant_id, order_id);
CREATE INDEX idx_ledger_entry_tenant_created_at ON ledger_entry (tenant_id, created_at DESC);
```

`order_id NOT NULL`, `payment_id` nullable — `order_id` is the mandatory traceability anchor every entry in this module's scope has; `payment_id` is null-able to leave room for a future non-`payment`-table source (e.g. Module 11's slip path) without a schema change. `reverses_entry_id` is a self-referencing composite FK, so "reversing another tenant's entry" is a constraint violation, not a service-layer bug. **`entry_type`'s CHECK enum is intentionally left open** — `.claude/rules/payments.md` §4 and `payment-ledger.md` §5 both make ledger entry types change-controlled; the two types directly implied by PAY-3/PAY-4 (a confirmed-payment credit, a refund/reversal debit — e.g. `PAYMENT_CONFIRMED`, `REFUND`) are proposed as the minimal candidate set but must go through the ADR path before the `CHECK` is finalized (§21).

### `payment_refund`

```sql
CREATE TABLE payment_refund (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID NOT NULL REFERENCES tenant (id),
    original_payment_id UUID NOT NULL,
    amount              NUMERIC(12,2) NOT NULL,
    reason              TEXT NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_payment_refund_original_payment FOREIGN KEY (tenant_id, original_payment_id)
        REFERENCES payment (tenant_id, id),
    CONSTRAINT ck_payment_refund_amount CHECK (amount > 0)
);

CREATE INDEX idx_payment_refund_tenant_original_payment
    ON payment_refund (tenant_id, original_payment_id);
```

`reason TEXT NOT NULL` is a recommendation (by analogy to the slip-override "no reason, no valid override" rule), not a literally sourced requirement — confirm before implementation. "Refund ≤ original (minus prior refunds)" is not expressible as a single-row `CHECK` (Postgres can't aggregate cross-row in a `CHECK`); enforce via FK + a service-layer guard summing prior refunds, covered by a dedicated test, per the same pattern `database-architecture.md` §4 prescribes for "settlement references a confirmed payment."

### `enrollment` — minimal activation slice (§21 central decision)

```sql
CREATE TABLE enrollment (
    id                    UUID PRIMARY KEY,
    tenant_id             UUID NOT NULL REFERENCES tenant (id),
    student_id            UUID NOT NULL,
    course_id             UUID NOT NULL,
    activating_payment_id UUID,
    activating_slip_id    UUID,
    status                VARCHAR(20) NOT NULL,
    activated_at          TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_enrollment_course FOREIGN KEY (tenant_id, course_id)
        REFERENCES course (tenant_id, id),
    CONSTRAINT fk_enrollment_student FOREIGN KEY (tenant_id, student_id)
        REFERENCES tenant_user (tenant_id, id),
    CONSTRAINT fk_enrollment_activating_payment FOREIGN KEY (tenant_id, activating_payment_id)
        REFERENCES payment (tenant_id, id),
    -- fk_enrollment_activating_slip -> payment_slip(tenant_id, id): CANNOT be created yet,
    -- payment_slip does not exist until Module 11 — see §21.
    CONSTRAINT uq_enrollment_tenant_student_course UNIQUE (tenant_id, student_id, course_id),
    CONSTRAINT ck_enrollment_exactly_one_activation_source CHECK (
        (activating_payment_id IS NOT NULL AND activating_slip_id IS NULL) OR
        (activating_payment_id IS NULL AND activating_slip_id IS NOT NULL)
    )
);

CREATE INDEX idx_enrollment_tenant_student ON enrollment (tenant_id, student_id);
CREATE INDEX idx_enrollment_tenant_course ON enrollment (tenant_id, course_id);
```

The exactly-one-non-null `CHECK` is ENR-1's literal, load-bearing invariant: activation "cannot exist as a bare boolean flag" and must trace to exactly one confirmed-payment or approved-slip row. `uq_enrollment_tenant_student_course` backs the idempotent-activation requirement (a repeated activation call for the same student+course is a no-op, not a second row). The `activating_slip_id` FK is deferred until Module 11's `payment_slip` table exists — this table cannot fully ship its Module-11-facing half in isolation; flagged in §21, not silently worked around.

### Cross-tenant same-tenant-enforcement FK summary

| Child column | Target | Target's `UNIQUE (tenant_id, id)` status |
|---|---|---|
| `order.course_id` | `course (tenant_id, id)` | Already exists (`uq_course_tenant_id`, on unmerged `feature/course-management`) |
| `order.student_id` | `tenant_user (tenant_id, id)` | Already exists (`uq_tenant_user_tenant_id`, `V3`, in `main`) |
| `payment.order_id` | `"order" (tenant_id, id)` | Added by this module's migration |
| `ledger_entry.order_id` / `.payment_id` | `"order"` / `payment (tenant_id, id)` | As above |
| `ledger_entry.reverses_entry_id` | `ledger_entry (tenant_id, id)` | Added by this module's migration |
| `payment_refund.original_payment_id` | `payment (tenant_id, id)` | As above |
| `enrollment.course_id` / `.student_id` | `course` / `tenant_user (tenant_id, id)` | Already exists |
| `enrollment.activating_payment_id` | `payment (tenant_id, id)` | As above |
| `enrollment.activating_slip_id` | `payment_slip (tenant_id, id)` | **Does not exist — Module 11 blocker** |

### Append-only enforcement — repository-interface design constraint

`PaymentRepository`, `LedgerEntryRepository`, `PaymentRefundRepository`, and `EnrollmentRepository` (activation is insert-only) must **not** extend `CrudRepository`/`JpaRepository` (both expose `delete*` by default). They extend Spring Data's bare `Repository<T, ID>` and re-declare only the methods actually needed via `TenantAwareRepository<T, ID>` — so no method signature anywhere can delete one of these rows. This also means `TenantAwareRepository` itself must not extend `CrudRepository`, or every tenant-owned repository (including these) would inherit `delete` regardless of the leaf interface. Production DB-role privilege reduction (revoke `DELETE` on all four tables for the app role in every environment; additionally revoke `UPDATE` on `ledger_entry`/`payment_refund`/`enrollment`, which have no justified update path at all) should accompany the migration.

## 9. Backend design

### Package structure

```
com.lms.paymentmanagement
├── api        # PaymentStatusApi, ManualSlipStatusApi (read-only; consumed by enrollment-management)
├── web        # OrderController, RefundController (webhook itself is received by integration-management, not here)
├── service    # OrderService, PaymentConfirmationService, RefundService
├── domain     # Order, Payment, PaymentRefund
├── repository # OrderRepository, PaymentRepository, PaymentRefundRepository (extend TenantAwareRepository)
└── config

com.lms.ledgersettlementmanagement
├── api        # LedgerEntryApi (write: recordFromConfirmedPayment/recordRefund; read: Payment History/Dashboard)
├── web        # Payment History / Payment Dashboard read endpoints
├── service    # LedgerEntryService
├── domain     # LedgerEntry
├── repository # LedgerEntryRepository (no delete/deleteById method exposed anywhere)
└── config

com.lms.enrollmentmanagement   # minimal slice only — §21
├── api        # EnrollmentActivationApi — the only class other domains may import
├── service    # EnrollmentActivationService (idempotent, defense-in-depth re-check)
├── domain     # Enrollment
├── repository # EnrollmentRepository (extends TenantAwareRepository)
└── (no web package yet — no student-facing endpoint in this module's scope)
```

### `api` interfaces

- **`PaymentStatusApi`** (`payment-management`) — read-only status check, consumed by `enrollment-management` as an independent re-verification, never trusted from the caller's claim alone.
- **`EnrollmentActivationApi`** (`enrollment-management`) — `activateFromConfirmedPayment(paymentId, studentId, courseId)` / `activateFromApprovedSlip(...)` (the slip variant stubbed for Module 11's future use), each idempotent via `uq_enrollment_tenant_student_course`. Before writing the `enrollment` row, the implementation independently calls back into `PaymentStatusApi` to confirm the payment is genuinely `CONFIRMED`, rather than trusting the caller — the same defense-in-depth pattern already established in this codebase (`StaffService`'s independent permission re-check).
- **`LedgerEntryApi`** (`ledger-settlement-management`) — a write method consumed synchronously by `payment-management`'s confirmation/refund services, plus read methods for Payment History/Dashboard.

`payment-management` and `enrollment-management` end up with a **bidirectional** `api`-level dependency (payment calls enrollment to activate; enrollment calls back into payment to independently re-verify). This is the exact junction `docs/architecture/modular-monolith.md` describes as intentional, not the circularity `.claude/rules/architecture.md` warns against (that rule targets `identity-access-service`/`tenant-management` never depending on business domains) — and it compiles fine since `backend/pom.xml` is a single-module Maven build with no reactor, so this is a package-level dependency Java tolerates within one compilation unit, not an artifact-level cycle.

### Transaction boundary — webhook-confirm-and-activate flow

Per `.claude/rules/backend.md` and `enrollment-access.md` §5, no transaction spans the outbound gateway call itself:

1. **Order placed** (PAY-1) — its own committed transaction; no gateway involved.
2. **Payment initiation** — a short transaction persists a `PENDING` `Payment` row; then, *outside* any open transaction, `payment-management` calls `integration-management`'s `PaymentGatewayApi.initiatePayment(...)`. The frontend redirect-return page renders "awaiting confirmation" only.
3. **Webhook arrives** at `integration-management`'s webhook endpoint (never a `payment-management` controller). It verifies signature/authenticity, durably logs receipt, then calls into `payment-management`'s confirmation `api` — never trusting any tenant id embedded in the webhook body; tenant identity is resolved from the platform's own `Order`/`Payment` record the external reference maps to.
4. **One local `@Transactional` service method** (never on the controller) then: transitions `Payment` to `CONFIRMED` (idempotent — a retried webhook for an already-`CONFIRMED` payment is a no-op); calls `LedgerEntryApi` synchronously to append the ledger entry inside the same transaction (a "paid" state with no ledger row is explicitly a bug per `.claude/rules/payments.md`, not a display nuance — confirm this placement with whoever owns `ledger-settlement-management`'s design before implementation, since it's a recommendation, not a verbatim-sourced rule); calls `EnrollmentActivationApi.activateFromConfirmedPayment(...)` synchronously (which independently re-verifies via `PaymentStatusApi`); commits.
5. **After commit**, a `payment-confirmed` domain event (e.g. `@TransactionalEventListener(phase = AFTER_COMMIT)`) is published for `notification-management`/`audit-log-management`/`reporting-analytics` to consume asynchronously — these side effects must never share or block the triggering transaction.

`payment-management` never embeds a gateway SDK or credential — only ever calls `integration-management`'s `PaymentGatewayApi` (`initiatePayment`, `processRefund`) and receives translated `api` calls back after webhook processing. No vendor is assumed or hardcoded anywhere.

## 10. API contract

Draft only — must be finalized via the `review-api-contract` skill into `docs/api/payment-management.md` (and `docs/api/ledger-settlement-management.md`) before implementation starts on either side. All responses use the existing `com.lms.common.api.ApiResponse<T>` envelope. No client-supplied `tenant_id`, price, or status is ever accepted — always resolved/computed server-side.

| Method + path | Auth | Notes |
|---|---|---|
| `POST /api/v1/orders` | Student | Body: `courseId` only — no price, no `tenantId`. `201` with the created order (server-computed `amount`/`currency`/`status`), `409` if the course isn't published/priced, `404` if `courseId` doesn't resolve within the caller's tenant. |
| `GET /api/v1/orders/{id}` | Student (own order only), Finance Staff/Institute Owner (`V`), Read-only Auditor (`V`) | `403`/`404` uniform for cross-tenant or another student's order. |
| `GET /api/v1/orders/{id}/payment-status` | Student (own order), staff `V` roles | Polled by the "awaiting confirmation" screen; returns `PENDING`/`CONFIRMED`/`REJECTED` only — never derived from a client-supplied redirect param. |
| `POST /api/v1/payments/webhooks/{provider}` | None (provider-signed) — **owned by `integration-management`, not `payment-management`**, listed here only to make the boundary explicit | Signature-verified; translates into an in-process call to `payment-management`'s confirmation service. Never exposed as a `payment-management` controller endpoint. |
| `GET /api/v1/payments/{id}` | Finance Staff/Institute Owner (`V`), Student Support (`V`), Read-only Auditor (`V`) | `403`/`404` uniform cross-tenant. |
| `GET /api/v1/ledger/history` (student, own) | Student | Distinguishes zero-data vs. filtered-empty via response metadata, per PAY-3. |
| `GET /api/v1/ledger/dashboard` | Finance Staff/Institute Owner/Read-only Auditor (`V`+) | Paginated, filterable by status/date; tenant-scoped only — no cross-tenant aggregate here (that's a separate, later Platform-Admin-authorized surface). |
| `POST /api/v1/payments/{id}/refunds` | Finance Staff/Institute Owner only (`A`) | Body: `amount`, `reason` (required, non-blank). `201` with the new `payment_refund`; `409` if `amount` exceeds the refundable remainder or the payment isn't in a refund-eligible terminal state; `403` for any non-`A` role including Read-only Auditor and Student Support. |
| `GET /api/v1/payments/{id}/refunds` | Same `V`+ roles as payment read | `403`/`404` uniform cross-tenant. |

Every endpoint resolves `tenant_id` exclusively from the authenticated session context. The exact 403-vs-404 choice for cross-tenant addressing (§4) should be pinned down and made **uniform across every endpoint in this table** during the `review-api-contract` pass, not decided ad hoc per endpoint.

## 11. Frontend screens

Scope: Student-side checkout entry point + gateway-redirect "awaiting confirmation" state + Payment History; Tenant-Admin-side Payment Dashboard + Refunds. Manual-slip UI and Platform Admin's cross-tenant dashboard are out of scope (§6).

**Documentation inconsistency, reported not resolved**: `docs/requirements/specifications/07-orders-and-payments.md`, `docs/ui-ux/user-journeys.md` (Journey 1), and PAY-1's own backlog entry all reference a `Student > Payments > Checkout` screen that `docs/ui-ux/screen-map.md`'s Student Portal list does not enumerate. `docs/ui-ux/component-library-spec.md` §7.1's Step Indicator also assumes a "Checkout" multi-step flow exists. This plan uses "Checkout entry point" per the backlog's own wording and leaves the canonical screen-map naming/placement (own route vs. embedded in Course Detail) as an open reconciliation item.

### Student portal (`app/(student)/`)

| Screen | Route (indicative) | Key components | States |
|---|---|---|---|
| **Checkout entry point** | `student/checkout/[courseId]` (naming unresolved, see above) | Step Indicator (if multi-step), `fieldset`/`legend`-wrapped payment-method radio group, `Button` | Loading (order-creation submitting, `aria-busy`); error (course unpublished/already-enrolled/price mismatch) via inline `Alert`, retryable; permission-denied via `QueryStateBoundary`'s login-redirect, never a client-side guess. Price is read-only display, never editable (structural Playwright assertion). |
| **Awaiting-confirmation** | `student/payments/awaiting-confirmation` | `LoadingState` (already `aria-busy`+`aria-live="polite"` in this codebase) driving a polling React Query hook | Loading is the primary state — must poll/re-fetch, never read gateway redirect query params. Distinct `role="alert"` failure state (reuse `components/ui/alert.tsx`). Distinct "still pending" state with Support link. Uniform generic 403/404 for a guessed/foreign order id. **Highest-risk regression point on this screen**: confirm the implementation never derives "confirmed" from a `?status=success`-style redirect param — mandatory negative Playwright test. |
| **Payment History** | `student/payments/history` | Card-list (consumer surface, not the admin data-table), Status Chip per row, filter controls | Two distinct empty states (zero-data vs. filtered) — the page must resolve which content to pass to `EmptyState` based on whether filters are active. Row-level `role="alert"` failed/rejected state with retry link. Uniform generic 403/404 for a manipulated order id in the URL. |

### Tenant Admin portal (`app/(tenant-admin)/`)

| Screen | Route (indicative) | Key components | States |
|---|---|---|---|
| **Payment Dashboard** | `tenant-admin/payments/dashboard` | Shared responsive data-table (not yet built — see below), Status Chip, optional summary Statistic Card, filters | Two distinct empty states; `PermissionDeniedState` for non-`V/C/E/A` roles driven only by a real 403, never a client role-string check; no tenant selector (single-tenant view); `md`+ table with card-fallback below `md`; row action icon buttons need per-row `aria-label`s. |
| **Refunds** | `tenant-admin/payments/refunds` | Reuse existing `AlertDialog` + `Button variant="destructive"` for the Confirmation Dialog; shared data-table for the refund-eligible list | Destructive confirmation must name the specific payment/order and amount (extends the spec's "target visibly named" rule to entity level); mutation failure (amount exceeds refundable remainder, non-refundable status) surfaced via `role="alert"`, never silently swallowed; same allow-list permission pattern as Dashboard; verify at implementation time that the dialog disables Escape/scrim-click dismissal for this destructive case. |

### New `components/ui/` primitives needed

Current inventory confirmed by direct read: `button.tsx`, `card.tsx`, `input.tsx`, `label.tsx`, `sheet.tsx`, `skeleton.tsx`, `alert-dialog.tsx`, `alert.tsx`, plus the shared state components at `components/states/` (`loading-state.tsx`, `empty-state.tsx`, `error-state.tsx`, `permission-denied-state.tsx`, `query-state-boundary.tsx`) — reuse all of these as-is; no screen in this module should hand-roll its own loading/empty/error/permission-denied branching.

**Genuinely missing, needed here:**
- **Shared responsive data-table** — not built anywhere yet. MVP-005's plan already flagged this identical gap for Staff List/Activity Log; whichever module lands first should build the one shared version per `.claude/rules/frontend.md`'s explicit "one shared table component" rule — this module must not build a second bespoke table.
- **Status Chip / Badge** — the only existing status-badge implementation (`(platform-admin)/platform-admin/tenants/status-badge.tsx`) is hardcoded to `TenantStatus` and not reusable. Recommend generalizing into a shared `components/ui/status-chip.tsx` before PAY-3 builds a payment-specific chip.
- **Toast / shared live-region wrapper** — does not exist anywhere in `frontend/src` yet. Required by `docs/ui-ux/design-system.md` §4.4 and explicitly needed by PAY-2's awaiting-confirmation/confirmed/failed announcements and PAY-4's refund success/failure. This is a blocking gap for both flows' async-status accessibility requirement.
- **`Select`** and a **date-range filter** — needed for Dashboard/History/Refunds filters; not yet in the inventory. `component-library-spec.md` §1.10 explicitly defers the calendar-picker's concrete anatomy to "first use" — this module is that first use.

**Status-vocabulary reconciliation needed before PAY-3 implementation**: `docs/ui-ux/component-library-spec.md` §2.10's Status Chip table lists **Pending**, **Pending Payment**, and **Payment Due** as three distinct entries, and **Failed** vs. **Rejected** as two distinct entries, while the backend `payment.status` enum is only `PENDING | CONFIRMED | REJECTED | REFUNDED`. Someone must fix this mapping before wiring the Dashboard/History status columns — flagged, not resolved here (§21).

## 12. Validation rules

- **Order creation**: `courseId` required, must resolve to a `PUBLIC`/published course within the caller's own tenant. No `price`, `amount`, `currency`, `tenantId`, or `studentId` field is ever accepted from the client — all server-computed/resolved. `course.price` is snapshotted at the instant of order creation, not re-read later.
- **Webhook payload**: validated by `integration-management` per the (unnamed) provider's signature scheme before any translation into a `payment-management` `api` call; a webhook lacking a resolvable `gateway_reference`/order mapping is rejected outright, not silently accepted as "pending."
- **Refund amount**: `> 0`; cannot exceed the original payment's amount minus the sum of any prior refunds against it (service-layer guard, not a bare `CHECK`, per §8).
- **Refund reason**: required, non-blank (recommendation — confirm before implementation, §21). Empty/whitespace-only rejected before any row is written.
- **Money precision**: every amount field is `NUMERIC`, validated end-to-end through create/read round-trips — never coerced through a floating-point type at any layer (DTO, service, or persistence).
- **Status transitions**: `Payment.status` only ever legally transitions `PENDING -> CONFIRMED` or `PENDING -> REJECTED`, enforced at both the service layer (mirroring `StaffService`'s doubled-enforcement pattern) and the DB `CHECK` constraint. No other transition (including any path back to `PENDING`) is valid.
- **Currency**: `VARCHAR(3) NOT NULL` on `order`/`payment`, but with no currency catalog/config to validate against yet, since `course` itself carries no currency column (see §21 — this inconsistency needs resolving before implementation, not defaulted).

## 13. Error cases

| Case | Expected behavior | Status |
|---|---|---|
| Client supplies a `price`/`tenantId` on order creation | Silently ignored; server-computed values used; no error, but a test proves the client value never wins | Settled requirement |
| Order for an unpublished/unpriced course | `409` (or `404` if the course doesn't resolve in-tenant at all) | Needs pinning at API-contract time (§10) |
| Cross-tenant order/payment/ledger/refund access by id | Uniform `403`/`404` (exact code TBD at contract time), never `200` with empty/filtered data | Settled requirement; exact code open |
| Unverified/unsigned webhook | Rejected before any state change; no `Payment` row created or mutated | Settled requirement |
| Duplicate webhook delivery (same `gateway_reference`) | Idempotent no-op — identical terminal state returned, no second write anywhere | Settled requirement; DB-enforced via unique index |
| Payment failed/rejected by gateway | `Payment.status = REJECTED`; Payment History shows `role="alert"` state; audit row written; access stays locked | Settled requirement |
| Refund amount exceeds refundable remainder | `409`, field-level error on `amount`; no row written | Settled requirement |
| Refund with empty/missing reason | `400`/`422` before any state change or audit row | Recommendation pending confirmation (§21) |
| Refund attempted by Student/Student Support/Read-only Auditor | `403` server-side regardless of UI state | Settled requirement |
| Attempted `UPDATE`/mutation of a terminal `Payment`/`ledger_entry` row via any code path | No such method exists at all (compile-time absence) | Settled requirement — structural, not runtime-checked |
| Order left `PLACED`/`PENDING` indefinitely (gateway session expired, no webhook ever arrives) | **No defined behavior anywhere in source material** — no cleanup/expiry/cancellation state exists for `Order` | **Open decision — do not invent** (§21) |
| `hasPermission(DomainArea.PAYMENTS_*, ...)` returns `true` but the target row is terminal | Endpoint independently rejects the mutation regardless of the coarse grant | Settled requirement (`.claude/rules/payments.md` §8) |

## 14. Tenant-isolation rules

Cross-cutting: `tenant_id` is resolved exactly once, at the auth/edge layer, from the validated session/token. No repository method, service method, or DTO for `order`, `payment`, `ledger_entry`, `payment_refund`, or `enrollment` may accept a caller-supplied `tenant_id` — not from body, query, path, header, or (critically) the webhook payload.

- **`order`** — cross-tenant negative test: Tenant A requesting Tenant B's order by id → 403/404, never 200. Server-resolved tenant/price wins over any client-supplied value in the same request (combined test). Composite FKs `(tenant_id, student_id)`/`(tenant_id, course_id)` enforce same-tenant linkage structurally.
- **`payment`** — cross-tenant negative test on read-by-id. **Webhook-tenant-resolution rule**: the tenant for a webhook-driven update is resolved by looking up the platform's own `order`/`payment` row the gateway's external reference maps to — never from any tenant identifier embedded in the webhook body itself (`docs/architecture/integration-architecture.md` §4 step 3). Test: a webhook whose payload claims an inconsistent tenant must be processed using the order's real tenant, or rejected outright — never the payload's claim.
- **`ledger_entry`** — cross-tenant negative test on both the student Payment History and the tenant-admin Dashboard, in a single-record fetch *and* a list/aggregate response. Bulk/dashboard/reporting surfaces are the named common bypass source (`.claude/rules/tenancy.md`) — Platform Admin's oversight view, if built later, must not be a single relaxed cross-tenant query "because admin." No `delete`/`deleteById` method exists at all (structural, not a permission check).
- **`payment_refund`** — cross-tenant negative test: Finance Staff of Tenant A attempting to refund a Tenant B payment (by guessing/enumerating the id) → 403/404 with **zero side effects** — no `payment_refund` row and no reversal `ledger_entry` created as a byproduct of the rejected attempt (mirrors the SLIP-4 "zero side effects on rejection" testing pattern). Composite FK `(tenant_id, original_payment_id) -> payment(tenant_id, id)` enforces this at the schema level too.
- **`enrollment` (minimal slice)** — same composite-FK-enforced same-tenant linkage on `student_id`/`course_id`/`activating_payment_id`; cross-tenant negative test on any future read path, even though no student-facing read endpoint ships in this module.

## 15. Security rules

- **Webhook signature/authenticity verification is a hard gate before any state persists.** `integration-management` validates the provider's signature/HMAC on receipt; a `CONFIRMED` transition may never be persisted from an unverified or unsigned callback, and verification happens before translation into `payment-management`'s confirmation `api` call.
- **Only two activation code paths may exist, structurally:** (1) `Payment` reaching `CONFIRMED` via the verified webhook, and (2) a slip reaching `APPROVED` via authorized reviewer action (Module 11, called out here only as the second approved path this module's `EnrollmentActivationApi` must also support). No endpoint — checkout, redirect-return handler, order-status endpoint, or admin tooling — may accept a client-reported "payment succeeded" payload as activation evidence. An enumeration of every activation call site, each traced back to a persisted `CONFIRMED`/`APPROVED` row, should be a named test artifact.
- **Idempotency for duplicate webhook delivery is mandatory**, enforced by the DB-level uniqueness constraint on `gateway_reference` (§8) — not an application-only "have we seen this before" check a race condition could bypass.
- **Refund/mutation authorization is restricted to `A`-holding roles only** (Finance Staff, Institute Owner). No student-facing endpoint may trigger a refund directly or indirectly; explicit negative test: a student calling the refund endpoint against their own payment is rejected 403.
- **`.claude/rules/payments.md` §8 applies directly to every mutation endpoint in this module.** A `hasPermission(DomainArea.PAYMENTS_*, ...) == true` result authorizes "this actor may generally act in this domain area," never the specific mutation attempted. The refund service must independently verify the original payment is in a refund-eligible terminal state and that the write path creates a new row rather than any `UPDATE`, regardless of what the permission check returned — the structural absence of an `UPDATE`/`DELETE` method is the actual control (risk register R11), not the permission check.
- **Minimal-slice stub is not exempt from ENR-1's full rigor.** Because this module's `enrollment-management` activation slice satisfies the same acceptance criteria the real ENR-1 story would (§21), it must, from day one: write the `NOT NULL` FK trail; enforce the exactly-one-non-null `CHECK` at the schema level; enforce same-tenant linkage via composite FK; pass a cross-tenant negative test and an idempotency test even in minimal form; and be reachable only from the two approved call sites — no "temporary" third convenience admin endpoint to manually flip enrollment active. Treating this as a throwaway stub "to harden later" is exactly the shortcut risk register R1 names.

## 16. Audit requirements

- **Payment approval/rejection** (the webhook-driven `CONFIRMED`/`REJECTED` transition) is on `.claude/rules/security.md`'s canonical mandatory-audit-action list. Every such transition writes an audit entry capturing actor id (for the webhook path, the recorded actor is the verified integration/system identity that processed confirmation — distinct from a human reviewer's identity, so the trail doesn't imply human judgment where there was none), tenant id, the target payment/order id, a timestamp, and the before/after status.
- **The audit write happens inside the same transaction/service boundary as the privileged action itself** — never a separate, skippable call — the same atomicity principle already required for payment-confirmation + enrollment-activation; the audit row is a third write belonging in that same local transaction.
- **Refund actions require exactly one audit row per refund**, containing actor identity, tenant, the original payment id, the new `payment_refund` id, amount, reason, and timestamp, written in the same transaction as the refund + reversal ledger entry.
- **Forward-dependency flag, not a defect to silently route around**: these audit-write requirements depend on `AUDIT-1`'s schema (Module 19), which the release plan already flags as needing to be pulled forward into an early wave rather than built in module-number order. This plan does not build `AUDIT-1`'s schema itself; it must not substitute an ad hoc, module-local "audit table" for payments that would later need reconciling with the canonical `audit_log` table — confirm `AUDIT-1`'s availability before or concurrently with this module's implementation (§21).

## 17. Payment impact

This module implements exactly Phase 1 of the four-phase roadmap and nothing beyond it, confirmed independently by the payment-ledger-specialist review:

- No Phase 2 (settlement/commission), Phase 3 (tenant-specific payment accounts), or Phase 4 (split payments) concept is scaffolded into any table/column/API introduced here. **Explicit guardrail for implementers**: adding a `tenant_payout_account_id`, `settlement_status`, or `split_amount`/`commission_amount` column to `order`/`payment` "for future-proofing" during implementation would itself be a change-controlled violation requiring its own ADR — do not add one under time pressure.
- The proposed design satisfies `.claude/rules/payments.md` §1-§4: tenant_id resolution from trusted context only; immutable terminal payment rows (one narrow, justified `PENDING`-only transition, §8); append-only ledger with `reverses_entry_id` chaining; no ledger `delete`/`deleteById` exposed anywhere; refund-as-new-row.
- Open business decisions this module must **not** invent an answer for: payment gateway selection (build only against `integration-management`'s generic `PaymentGatewayApi`); refund window/eligibility policy (mechanism only, no hardcoded eligibility window); the exact ledger entry type set beyond the two minimal candidates named in §8 (adding a third type — e.g. a separate `SLIP_APPROVED` type to distinguish provenance — needs the ADR path even though it looks like a small, reasonable addition).
- The cross-module transaction calling `enrollment-management`'s activation `api` from within `payment-management`'s webhook-confirmation transaction is compliant with both `payments.md` and `enrollment-access.md` — both documents already converge on requiring this exact coupling — but because this transaction is the literal intersection of two independently change-controlled rule areas, any future change to either side of it (e.g. what counts as sufficient webhook verification, or what the activation `api` requires as a precondition) should be reviewed against **both** documents in the same pass, not signed off against one in isolation.

## 18. Tests

**Grounding**: unlike MVP-005, this branch's base (`main`) already has real `identity-access-service`, `tenant-management`, and RBAC code — tests here *can* exercise a real authenticated session and real tenant/permission checks once `payment-management`'s own code exists. What blocks tests is the *data model*, not the auth stack: no `course`/`student` tables exist in `main` yet (only on unmerged branches), and no `enrollment-management`/`integration-management` code exists anywhere.

### Unit tests (writable as soon as domain/DTO classes are scaffolded)
- Order-create DTO has no client-settable `price`/`amount`/`tenantId` field (structural/reflection assertion).
- Webhook signature-verification logic against fixture payloads (valid/tampered/missing signature) — written against `integration-management`'s generic adapter contract, not a vendor SDK.
- `Payment` status state-machine transition validation (legal vs. illegal transitions) at the service layer, mirroring the DB `CHECK`.
- `ledger_entry.entry_type` enum validation; amount sign-convention per type as pure logic.
- Refund-amount validation (`> 0`, cannot exceed original) — no "partial-refund accumulation" logic invented ahead of an open policy decision.

### Testcontainers integration tests
1. Server-resolved tenant/price wins over a spoofed client value on order creation.
2. Verified webhook → `Payment` `CONFIRMED` + enrollment activation in the same transaction, **plus** a simulated mid-transaction-failure/rollback test proving no partial state (no orphaned `CONFIRMED` payment with no enrollment, and vice versa).
3. **Mandatory idempotency test**: identical webhook delivered twice → exactly one `CONFIRMED` transition, one ledger entry, one enrollment activation. Equivalent test for a duplicate refund-trigger action.
4. **Structural test**: no `update`/`delete` method reachable on `PaymentRepository`/`LedgerEntryRepository`/`PaymentRefundRepository` — including verifying `TenantAwareRepository` itself doesn't leak `CrudRepository`'s `delete*` methods through inheritance.
5. Refund creates a new row via `reverses_entry_id`; original `Payment` row field-for-field unchanged before/after (snapshot comparison, not just "no update endpoint exists").
6. Money-column precision round-trip against real Postgres `NUMERIC` (no float coercion/scale drift).
7. DB `CHECK` constraints on `status`/`entry_type` are schema-enforced (raw out-of-enum insert attempt rejected by the database itself, not just the Java layer).
8. Ledger orphan-prevention: a `ledger_entry` with no `payment_id`/`order_id` is rejected by FK/`NOT NULL`.
9. Dashboard/Payment History is ledger-derived, not `payment.status`-derived: seed a `CONFIRMED` payment with **no** corresponding ledger entry and assert it is not reported as "paid."

### Mandatory cross-tenant negative tests (403/404, never 200-with-filtered-data)
Order create/read/detail/list (every filter/pagination/search combination), payment status-read, the webhook endpoint's tenant-resolution path, ledger read (both Payment History and Dashboard, single-record and list/aggregate), refund create, refund read/list.

### Playwright E2E
Checkout has no price-editable field, and a tampered outgoing request body still results in the server-side course price persisting; redirect-return shows "awaiting confirmation" and a spoofed success query param does not render an active/enrolled state; Payment History's two distinct empty states; failed/rejected `role="alert"` rendering; async status via the shared toast/`aria-live` pattern with icon+text+color; Refunds' destructive confirmation dialog with a real backend read-after-cancel proving no state change; Refunds role-gating (Read-only Auditor/Student Support see no control, and a direct API call from that role's session still fails server-side).

### Named follow-ups — explicitly blocked, not silently skipped
1. **Every Testcontainers test involving a real `Order` row** — blocked on `course-management`/`student-management` merging to `main`.
2. **Every test involving real enrollment activation** (the atomic-transaction test, its rollback variant, the idempotency test) — blocked on this module's own minimal `enrollment-management` slice actually being built first (§21), and on `integration-management`'s minimal skeleton existing to exercise the webhook path at all.
3. **Cross-tenant tests for order/webhook paths** — same blocker as items 1-2.
4. **All Playwright tests** — blocked on the same backend prerequisites plus the frontend screens not existing yet.
5. **Partial-refund accumulation edge case** — blocked on the open refund-window/eligibility policy decision; not invented.
6. **Webhook signature tests against a concrete vendor payload shape** — soft-blocked on gateway selection (its own future ADR).
7. **Audit-row tests for payment approvals/rejections and PAY-4's "exactly one audit row per refund"** — soft-blocked on `AUDIT-1` existing; tests stop at "the domain state transitioned correctly" until then.
8. **Settlement-run idempotency** — out of scope for MVP-010 entirely (Phase 2); not pulled forward here.
9. **Platform-Admin cross-tenant oversight test** — deferred until that view is actually built.

## 19. Documentation changes

- `docs/architecture/database-architecture.md` — new `order`/`payment`/`ledger_entry`/`payment_refund`/`enrollment` table entries, once §21's open naming/enum items are resolved.
- `docs/architecture/payment-ledger.md` — confirm the shipped `Order`/`Payment`/`LedgerEntry` schema matches §2/§5 exactly, and record the ADR-approved `entry_type` enum once ratified.
- `docs/architecture/enrollment-access.md` — confirm the minimal activation slice's implementation matches §2-§5 exactly (FK traceability, atomic transaction, prohibited frontend-activation path); note explicitly that this document's full scope (§6 Smart Expiry) remains Module 12's to implement.
- `docs/architecture/integration-architecture.md` — confirm the minimal `PaymentGatewayApi`/webhook-skeleton built for this module matches the adapter/webhook-handling pattern described.
- `docs/api/payment-management.md` and `docs/api/ledger-settlement-management.md` (new) — produced via `review-api-contract` from §10's draft before implementation begins on either side.
- `docs/ui-ux/screen-map.md` — reconcile the "Checkout" screen naming/enumeration gap (§11) once a UX decision is made.
- `docs/ui-ux/component-library-spec.md` — reconcile the Status Chip payment-status vocabulary (§11) against the actual `payment.status` enum.
- `docs/requirements/open-decisions.md` — append: the ENR-1-scope-pulled-into-MVP-010 decision and its rationale; the `order`/`payment` currency-column-with-no-source inconsistency; the `payment.status = REFUNDED` vs. immutability contradiction; the approver-precedence question for refunds; the Order-abandonment/no-cleanup-state gap.
- `docs/adr/` — a new ADR is required before finalizing the `ledger_entry.entry_type` enum (change-controlled per `payments.md` §4), and another (or the same one, if scoped together) recording the decision to pull a minimal `enrollment-management` activation slice into this module ahead of Module 12's literal position — mirroring how `AUDIT-1`'s pull-forward is being handled for SLIP-4.

## 20. Implementation order

**Sequencing blocker to resolve first, not a style preference**: `course-management` and `student-management` are fully built but unmerged (`feature/course-management`, `feature/student-management`). `PAY-1`'s `order` table needs real composite FKs to `course` and to student identity — Postgres cannot create those FKs against tables that don't exist in the branch's schema. Either (a) both branches land on `main` via their own reviewed PRs first, or (b) this branch is rebased onto (or merged from) an integration branch containing both — this repo already has exactly this precedent (`integration/staff-management-prereqs`, commit `1cf39e4`). This decision belongs to whoever sequences the actual work, not to this plan.

Given that prerequisite is resolved, build order within this module:

1. **`Order` (PAY-1)** — first; everything downstream references it.
2. **Minimal `enrollment-management` bootstrap** (schema + `EnrollmentActivationApi` skeleton, no controllers) — built *before* wiring PAY-2's webhook handler, so the `api` contract exists to call into, per the release plan's "design the `api` contract concurrently with PAY-2, not after."
3. **`Payment` + webhook confirmation (PAY-2)** — including a minimal `integration-management` skeleton (a generic `PaymentGatewayApi` interface + webhook receiver + a fake/test adapter, since no real gateway is selected) needed to exercise the flow end-to-end in tests. This step wires `payment-management -> enrollment-management` (activation) and the `enrollment-management -> payment-management` re-check.
4. **`ledger-settlement-management`'s `LedgerEntry` (PAY-3)** — wired into step 3's transaction once `Payment` confirmation exists to append from.
5. **`PaymentRefund` (PAY-4)** — last, requiring a `CONFIRMED` `Payment` + its `LedgerEntry` to already exist to link/reverse against.

Per root `CLAUDE.md`'s development workflow, backend implementation (steps 1-5 above, run through `implement-backend`) completes and passes review before any `implement-frontend` work begins on §11's screens.

## 21. Risks and unresolved decisions

Ranked by how directly each blocks or reshapes implementation.

1. **Central scoping decision — minimal `enrollment-management` activation slice pulled into this module.** PAY-2/SLIP-3 cannot reach Definition-of-Done without a real, callable enrollment-activation `api` in the same transaction as payment/slip confirmation. `docs/planning/dependency-map.md` and `docs/planning/mvp-release-plan.md` already state this coupling must be designed/built concurrently, not sequentially — this plan's recommendation (§3, §7-9, §20) to build the minimal ENR-1 slice as part of MVP-010 is grounded in those already-approved documents, not invented. **This still needs explicit sign-off** from whoever owns Module 12/issue #12, since it means this module's PR would touch a nominally different module's domain package. If rejected, the alternative is that MVP-010 cannot reach a true Definition-of-Done standalone, and PAY-2 ships with an interface-only stub pending a separate, coordinated ENR-1 PR — the source docs argue against that path, but it is not this plan's call to make unilaterally.
2. **`course-management`/`student-management` unmerged prerequisite.** Both exist, fully built, on unmerged branches. This module's `order` migration cannot be written with real FKs until they land on `main` or an integration branch is created. Sequencing decision for whoever plans the actual PRs (§20).
3. **Migration-numbering collision.** `feature/course-management` and `feature/student-management` each independently used `V11__...` off the same `main` base. This module's own migration number cannot be assigned here — it depends on how that collision is resolved at merge time.
4. **`integration-management` minimal skeleton — scope decision not made by the source docs the way ENR-1's is.** PAY-2's webhook-verification dependency is real and "cross-module hard" per its own backlog entry, but unlike ENR-1 it is not named in `dependency-map.md`'s forward-reference table or pulled into a wave by the release plan. Recommend explicitly deciding whether this module's scope also bootstraps a minimal `integration-management` skeleton (fake/test gateway adapter only, no vendor) or whether that is tracked as a separate blocking prerequisite.
5. **`order` is a reserved SQL keyword.** Rename (e.g. `student_order`) or commit to consistent double-quoting everywhere (DDL, JPA `@Table`, native queries) before implementation — a real, recurring foot-gun if left undecided.
6. **`order.status` and `ledger_entry.entry_type` enums are incompletely sourced.** No document states a complete `Order` status list (no `CANCELLED`/`EXPIRED` value anywhere) or the full ledger entry-type set — the latter is explicitly change-controlled (needs an ADR before the `CHECK` constraint is finalized, per `.claude/rules/payments.md` §4).
7. **`currency` column has no upstream source.** `course.price` deliberately has no currency column ("a single implicit currency is assumed platform-wide/per-tenant at MVP," per `course-management`'s own migration comment), yet the backlog lists `currency` on both `order` and `payment`. Resolve before implementation — do not invent multi-currency/FX logic to paper over the gap.
8. **`payment.status = 'REFUNDED'` appears to contradict the "immutable once terminal" rule.** Writing `REFUNDED` onto an already-`CONFIRMED` payment row would be a second `UPDATE` on a terminal row, which `.claude/rules/payments.md` §1 and `database-architecture.md` §3 both forbid. Likely resolution: never write `REFUNDED` to `payment.status` at all — read refund state from `payment_refund`/`ledger_entry` instead — but this needs explicit confirmation, not a silent implementation choice.
9. **`order.student_id` FK target: `tenant_user` vs. `student_profile`.** This plan recommends `tenant_user(tenant_id, id)` (§7-8), following the existing `course.teacher_id` precedent and because `student_profile` currently lacks a `UNIQUE (tenant_id, id)` a FK could target at all (a pre-existing gap in `user-management`'s own migrations, identical on `staff_profile`, independent of this module). Confirm this recommendation before implementation.
10. **`payment_refund.reason NOT NULL`** is this plan's recommendation by analogy to the slip-override rule, not a literally sourced requirement — confirm.
11. **Ledger-entry write placed inside the same transaction as payment confirmation** is this plan's design recommendation (§9), inferred from "paid with no ledger row is a bug," not a rule spelled out verbatim anywhere — confirm with whoever owns `ledger-settlement-management`'s design.
12. **Approver precedence for refunds (and manual-slip/reactivation approval generally)** — both Finance Staff and Institute Owner hold `A`; no document resolves precedence or dual-role interaction when both are eligible (`docs/requirements/open-decisions.md` §4).
13. **Order-abandonment / no-cleanup state.** No document defines what happens to an `Order` that never receives a webhook (expired gateway session, abandoned checkout) — genuinely undefined, not an intentional omission spotted by only one reviewer.
14. **"Checkout" screen naming/enumeration gap** between `user-journeys.md`/the spec/backlog and `screen-map.md` — a pre-existing, already-documented inconsistency (`docs/requirements/open-decisions.md` §11), reconciled here only by using the backlog's own wording, not resolved.
15. **Status Chip vocabulary mismatch** — `component-library-spec.md` names more payment-status labels (Pending, Pending Payment, Payment Due, Failed, Rejected) than the backend's four-value enum distinguishes. Needs a mapping decision before PAY-3's UI wiring.
16. **`AUDIT-1` forward dependency.** PAY-2/PAY-4's mandatory audit-log writes need `AUDIT-1`'s schema, which sorts many modules later by story-ID but must be pulled forward per the release plan (the same pattern already flagged sharply for SLIP-4). Confirm `AUDIT-1`'s availability timeline before or alongside this module's implementation.
17. **Payment gateway selection and refund window/eligibility policy** remain explicitly open business decisions (`docs/architecture/payment-ledger.md` §10) — this plan builds only the generic adapter boundary and the refund mechanism, never a vendor integration or a hardcoded eligibility window.
18. **Manual-slip payment-method seam.** PAY-1's "choose payment method" step needs to leave room for Module 11's slip path without building slip logic now — no document specifies the exact seam shape (a `payment_method` field vs. a separate flow branch); worth deciding at API-contract time.
19. **Documentation gap noted by the qa-test-engineer review**: `docs/architecture/enrollment-access.md` §8 cites `.claude/rules/testing.md` as the source of a "required-test matrix," but that file does not exist under `.claude/rules/` (only `architecture.md`, `backend.md`, `frontend.md`, `git-workflow.md`, `payments.md`, `security.md`, `tenancy.md`, `ui-ux.md` do). This plan cross-referenced `tenancy.md`/`payments.md`/`security.md` directly instead; worth fixing the stale cross-reference in `enrollment-access.md` itself.
