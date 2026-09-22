# MVP-022 — Course Billing and Lifecycle — Module Plan

**Wave:** Wave 2 — "Course/Class expansion + billing model foundation" (`KLASS-PARITY-MASTER-INSTRUCTION.md`
§39; `docs/parity/implementation-roadmap.md` §7/§8).
**Backend domain:** `course-management` (extends the existing `Course` aggregate from MVP-008 —
confirmed during planning that Course/Module/Lesson already covers this wave's scope, no separate
Class domain was warranted; see `docs/parity/klass-parity-matrix.md` PAR-XC-03).
**Parity IDs addressed:** PAR-05-02, PAR-05-03 (partial), PAR-05-04 (partial), PAR-05-06 (partial),
PAR-05-07, PAR-05-08.

## Process note — why this plan is dated after the code it describes

This document is **reconstructed retroactively**, after backend and frontend implementation,
testing (backend: 1644 tests, `mvnw verify` BUILD SUCCESS; frontend: lint/typecheck/build clean,
Playwright green), and a three-agent review pass (architecture, security/tenant-isolation,
payment-ledger — which produced `docs/adr/ADR-015-free-course-zero-amount-payment-and-ledger.md`)
were already complete. The module's own plan was never persisted as a repo artifact before
implementation began — a gap a Phase E review flagged, the same class of process gap
`docs/api/course-management.md`'s own "Process gap" note records for MVP-008. This file closes
that gap for Wave 2: it describes what was **actually built**, reconstructed from the shipped
code, migrations, tests, and ADR-015 — not a forward-looking wishlist, and not a rewrite of
history to make deferred items look planned from the start where they weren't.

---

## 1. Business goal

Let a course carry a real billing shape beyond a single flat price — `FREE` / `ONE_TIME` /
`MONTHLY` / `SESSION` / `CUSTOM` — with `MONTHLY`/`SESSION` courses tracking a dated rate history
(billing periods) the way `course.price` already tracks price-change history. Give staff (not
just the owning Teacher) an equivalent course-creation entry point, and give both staff and
Teachers archive and clone actions on a course, none of which existed before this wave. This is
explicitly framed as a **billing foundation**, not full billing automation: recurring auto-charge
scheduling and `CUSTOM`-pricing checkout completion are out of scope and documented as deferred,
not silently dropped.

## 2. User roles

- **Tenant Admin** — full access to every endpoint in this wave (course creation, pricing model,
  billing configuration/periods, archive/unarchive, clone, teacher reassignment — the last
  unchanged from MVP-008 and still Tenant-Admin-only).
- **Course Coordinator** (staff, `DomainArea.COURSES` `V/C/E/A`) — same access as Tenant Admin
  for everything in this wave except teacher reassignment (unchanged MVP-008 restriction).
- **Teacher** — ownership-scoped access (unchanged `CourseAccessGuard` discipline): may manage
  pricing/billing/archive/clone only for courses they own, never delete, never reassign.
- **Student** — no new endpoint in this wave targets Student directly. `OrderService.createOrder`
  (`payment-management`) consumes the new `CourseLookupApi.getResolvedCheckoutAmount` read to
  resolve what a student is charged per the course's pricing model — this is a consuming change
  in `payment-management`, not a new `course-management` endpoint for students.

## 3. Acceptance criteria

1. A course has a `pricingModel` (default `ONE_TIME`, matching every pre-Wave-2 course
   unchanged) and a `pricing-model` change endpoint, audited on every genuine change.
2. A `MONTHLY`/`SESSION` course can have a billing configuration and a dated, append-only
   billing-period history, with at most one open period at a time.
3. A course can be archived/unarchived as a pure listing-visibility flag, never a delete, never
   touching enrollment/payment/price/billing-period history.
4. A course can be cloned, copying only classification + module/lesson structure +
   `pricingModel` — never enrollment/payment/price-history/billing-period history — and the
   clone is audit-logged.
5. Staff (not just Teacher) has a course-creation entry point, reusing the same backend contract.
6. Checkout amount resolution is centralized in one `CourseLookupApi` method per pricing model,
   consumed by `payment-management`, replacing the old `ONE_TIME`-only price read.
7. A genuinely `FREE` course auto-activates enrollment on checkout with a real `$0` payment and
   ledger entry; a **non**-`FREE` course that happens to resolve to `$0` (a misconfiguration) is
   rejected, never silently free-activated — this criterion was tightened mid-wave via ADR-015
   after the Phase E review found the first implementation too broad.
8. `CUSTOM` pricing resolves server-side but is explicitly **not** required to have a reachable
   student checkout path this wave (no staff-on-behalf-of-student order endpoint) — documented as
   deferred, not silently missing.
9. Every new tenant-owned table/query has a passing cross-tenant negative test.

## 4. Database impact

Six new/altered migrations, V37–V42, all additive (no already-applied migration edited):

| Migration | Change |
|---|---|
| `V37__add_course_pricing_model_and_archive.sql` | `course` gains `pricing_model VARCHAR(20) NOT NULL DEFAULT 'ONE_TIME'` (CHECK-constrained, matching `course.status`'s existing convention, not a native enum type) and `archived_at TIMESTAMPTZ` (nullable, mirrors `enrollment.superseded_at`'s pattern). |
| `V38__create_course_billing_configuration.sql` | New table `course_billing_configuration` — one row per course (`UNIQUE (tenant_id, course_id)`), composite FK to `course` with `ON DELETE CASCADE` (mirrors `course_module`/`course_lesson`'s V14 precedent — live, mutable config, no independent audit value once the course is gone). |
| `V39__create_course_billing_period.sql` | New table `course_billing_period` — append-only, no live FK back to `course_billing_configuration` (mirrors `course_price_history`'s V12 orphan-tolerant technique, so a course delete cascading through the configuration never destroys or blocks on billing-period history). `uq_course_billing_period_current` (partial unique index, `WHERE effective_to IS NULL`) enforces at most one open period per configuration. |
| `V40__add_billing_period_reference_to_student_order.sql` | `student_order` gains a nullable `billing_period_id`, composite FK to `course_billing_period`, purely for traceability — never changes `student_order.amount`'s existing immutable-snapshot semantics. |
| `V41__allow_zero_amount_payment_for_free_courses.sql` | Widens `payment.amount`'s CHECK from `> 0` to `>= 0` (additive, same constraint name) so a `FREE` course's `$0` payment can reach `CONFIRMED`. |
| `V42__allow_zero_amount_ledger_entry_for_free_course_confirmations.sql` | Widens `ledger_entry.amount`'s CHECK to an entry-type-aware form (`PAYMENT_CONFIRMED >= 0`, `REFUND < 0`) — **not** a plain sign-agnostic widening, which an earlier draft got wrong and `mvnw verify` caught before merge (see ADR-015). Added mid-wave, after the Phase E review found V41 alone left FREE confirmations invisible to the ledger-derived Payment Dashboard. |

Every new/altered tenant-owned table/column follows this schema's established conventions:
`tenant_id NOT NULL`, composite tenant-scoped FKs where the parent is also tenant-owned, and
tenant-leading indexes matching the actual query shape (`(tenant_id, course_id)` for the
configuration lookup, `(tenant_id, billing_configuration_id, effective_from)` for period
history).

## 5. Backend impact

New classes, all inside `com.lms.coursemanagement` (no new top-level domain):

- **Domain:** `CoursePricingModel` (enum), `CourseBillingConfiguration`, `CourseBillingPeriod` —
  both new entities structural children of `Course`, mirroring `CourseModule`/`CourseLesson`'s
  "opaque parent id, not a JPA association" pattern. `Course` gains `pricingModel`/`archivedAt`
  fields with dedicated, single-write-path setters (`setPricingModel` only from
  `CourseService#changePricingModel`; `archive`/`unarchive` only from `CourseService`'s matching
  methods) — enforced by convention/review, the same discipline `Course.setPrice` already used.
- **Repository:** `CourseBillingConfigurationRepository`, `CourseBillingPeriodRepository`.
- **Service:** `BillingConfigurationService` (new — billing-configuration/period read/write
  paths); `CourseCheckoutAmountResolver` (new — the batched, non-N+1 per-pricing-model amount
  resolver `CourseService#listCourses`/`toView` and `CoursePublicService` both use);
  `CourseService` gains `changePricingModel`/`archiveCourse`/`unarchiveCourse`/`cloneCourse`.
- **Web:** new `CourseBillingController` (`/api/v1/courses/{courseId}/billing-configuration`,
  `/billing-periods`); `CourseController` gains `PATCH .../pricing-model`,
  `POST .../archive`, `POST .../unarchive`, `POST .../clone`.
- **api package (cross-module contract):** `CourseLookupApi.getResolvedCheckoutAmount(UUID)` —
  new method, returning the new `CheckoutAmount` record (`amount`, `currency`,
  `billingPeriodId`, `requiresManualQuote`, `freePricing`); `CourseBillingNotConfiguredException`;
  new domain events `CoursePricingModelChangedEvent`, `CourseArchiveStateChangedEvent`,
  `CourseClonedEvent`, `CourseBillingConfigurationChangedEvent`, `CourseBillingPeriodAddedEvent`.
- **Consumer change in `payment-management`:** `OrderService.createOrder` switches from the old
  `ONE_TIME`-only `CourseLookupApi#getCurrentPrice` read to `getResolvedCheckoutAmount`, adds the
  `freePricing`-gated auto-activation branch and the `$0`-misconfiguration rejection (ADR-015),
  and adds `activateFreeCheckout` (drives a `$0` `Payment` through the same `confirm()` transition
  and `EnrollmentActivationApi` call every gateway-confirmed payment uses — no new activation code
  path) plus a real `LedgerEntryApi.recordPaymentConfirmed` call for the `$0` case.

See `docs/architecture/modular-monolith.md`'s Wave 2 worked example for the full cross-module
communication detail (why `freePricing` is a derived boolean, not the raw
`CoursePricingModel` enum, on the `api`-package contract).

## 6. API contract

Full detail in `docs/api/course-billing.md` (new file, this wave) — mirrors
`docs/api/tenant-configuration-management.md`'s structure/detail level:

- `GET`/`POST /api/v1/courses/{courseId}/billing-configuration`
- `GET`/`POST /api/v1/courses/{courseId}/billing-periods`
- `PATCH /api/v1/courses/{id}/pricing-model`
- `POST /api/v1/courses/{id}/archive` / `.../unarchive` / `.../clone`
- `GET /api/v1/courses` gains an `includeArchived` query param
- `CourseResponse`/`PublicCourseResponse` gain `pricingModel`/`archivedAt`(authenticated
  only)/`resolvedAmount`/`currency`/`requiresManualQuote`

`docs/api/course-management.md` (the pre-existing MVP-008 contract) is annotated with a pointer
note rather than rewritten, per this wave's documentation-update pass.

## 7. Frontend impact

New Tenant Admin routes/components (`frontend/src/app/(tenant-admin)/tenant-admin/courses/`,
`frontend/src/components/courses/`):

- `courses/new` — staff course-creation entry point (`course-create-form.tsx`), closing
  PAR-05-02.
- Course workspace (`courses/[courseId]/`) restructured into real tabs
  (`course-workspace-tabs.tsx`, `course-workspace-shell.tsx`):
  - `billing/` — pricing-model control (`course-pricing-model-control.tsx`) + billing
    configuration/period management (`course-billing-configuration-form.tsx`,
    `course-billing-panel.tsx`, `course-billing-period-panel.tsx`).
  - `settings/` — publish/unpublish (`course-visibility-control.tsx`), teacher reassignment
    (`course-teacher-reassign-form.tsx`, Tenant-Admin-only, unchanged), archive/unarchive
    (`course-archive-control.tsx`, new), clone (`course-clone-action.tsx`, new), delete
    (`course-delete-action.tsx`, Tenant-Admin-only, unchanged).
  - `access/` — `accessDurationDays`/`enrollmentRule`, pulled out of the old general edit form
    into its own tab (`course-access-form.tsx`); no backend change needed, these fields already
    existed on `CourseResponse`/`CourseUpdateRequest`.
  - `schedule/`, `sessions/`, `recordings/`, `analytics/` — **deliberate structural
    placeholders** (`course-workspace-placeholder.tsx`, a shared `CourseWorkspacePlaceholder`
    component, "Coming in a later release", no fake data or controls) — blocked on the
    not-yet-built Live Sessions module and `reporting-analytics`. Visible/clickable tabs, no
    functionality, by design.
- Storefront/checkout (public + student-facing) pricing display (`course-price-display.tsx`)
  centralizes per-pricing-model label/note/checkout-availability logic in one place: `FREE`
  renders "Free"; `ONE_TIME`/`MONTHLY`/`SESSION` render the resolved amount with a
  `/month`/`/session` suffix where applicable; `CUSTOM` renders "Contact us for pricing" with no
  checkout form; an unconfigured `MONTHLY`/`SESSION` course renders "Pricing not yet available."

Students/Teachers/Materials/Attendance/Exams were **not** built as new course-scoped tabs in this
wave — Materials already lives per-lesson under the teacher module editor, and the others are
existing tenant-wide screens elsewhere in this codebase; this wave did not invent duplicate
course-scoped versions of them.

## 8. Security impact

- Every new/changed billing-configuration/billing-period/archive/unarchive/clone/pricing-model
  endpoint reuses the exact same `CourseAccessGuard` staff-matrix-or-Teacher-ownership check
  every other course-mutation endpoint already uses — no new authorization mechanism introduced.
- The FREE-checkout auto-activation gate was the subject of the Phase E security/payment-ledger
  finding this wave's ADR-015 resolves: the original implementation would have let a
  misconfigured `$0` `ONE_TIME`/`MONTHLY`/`SESSION` course silently grant free enrollment with no
  payment evidence — now rejected outright (`409`) instead, before the review caught it, per
  `.claude/rules/payments.md` §1/§2's "activation must read confirmed payment state, never a
  loosely-derived signal" and "no screen may show 'paid' with no matching ledger entry" rules.
- `CUSTOM` pricing's `customAmount` override is permission-checked (`DomainArea.COURSES
  CREATE_EDIT`) at the point `OrderService` would consume it, even though no reachable caller
  exists yet — implemented and independently tested ahead of the future endpoint that will call
  it, rather than left unguarded until that endpoint ships.
- Clone now publishes `CourseClonedEvent`, closing a gap the Phase E review found: cloning
  previously left no audit trail at all, inconsistent with `.claude/rules/security.md`'s
  mandatory audit-logging list for course-content-shaped mutations.

## 9. Tenant impact

- Every new table (`course_billing_configuration`, `course_billing_period`) carries
  `tenant_id NOT NULL` with a composite tenant-scoped FK back to its tenant-owned parent, and a
  tenant-leading index matching its real query shape — no new global/shared table was introduced.
- `CourseLookupApi.getResolvedCheckoutAmount` resolves exclusively through the same
  `TenantContext`-derived tenant scoping every other method on that interface already uses — no
  overload accepts a caller-supplied tenant id, and a cross-tenant/nonexistent course id resolves
  identically to `Optional.empty()`.
- `student_order.billing_period_id`'s new composite FK explicitly requires the referenced billing
  period to belong to the same tenant (`(tenant_id, billing_period_id) REFERENCES
  course_billing_period (tenant_id, id)`) — a cross-tenant reference is a schema constraint
  violation, not merely a service-layer bug, per `.claude/rules/tenancy.md`.
- Cross-tenant negative tests exist for the new billing-configuration/billing-period endpoints
  and the archive/unarchive/clone/pricing-model endpoints, following the same pattern established
  for every other `course-management` mutation.

## 10. Tests

- **Backend:** `BillingConfigurationServiceTest`, `CourseBillingAndLifecycleIntegrationTest`
  (covers billing configuration/period creation, the close-then-insert period transition, the
  `uq_course_billing_period_current` concurrent-race `409` mapping, archive/unarchive/clone,
  pricing-model change), `CourseBillingConfigurationRequestValidationTest`,
  `CourseBillingPeriodRequestValidationTest`, and `BillingHistoryIntegrityIntegrationTest`
  (`payment-management`, verifying `student_order.billing_period_id` traceability). Full suite:
  1644 tests, `mvnw verify` BUILD SUCCESS.
- **Frontend:** `tenant-admin-course-create.spec.ts`, `tenant-admin-courses.spec.ts`, and the
  existing course-management Playwright specs extended for the new workspace tabs; lint/typecheck/
  build clean, Playwright green.
- **Reviews:** architecture, security/tenant-isolation, and payment-ledger reviews all passed,
  with the one finding from that pass (the FREE-checkout gating/ledger-entry gap) fixed and
  recorded in ADR-015 before sign-off.

## 11. Documentation updates

Produced/updated as the direct output of this documentation pass (see this module's own
`update-documentation` run):

- `docs/parity/klass-parity-matrix.md` — PAR-05-02/03/04/06/07/08 reclassified; PAR-26-01–04 and
  PAR-07-04 marked as explicitly descoped from this wave, not silently dropped.
- `docs/parity/implementation-roadmap.md` — new §8 recording Wave 2 as done-with-caveats,
  matching the matrix detail.
- `docs/architecture/modular-monolith.md` — new worked example documenting
  `CourseLookupApi.getResolvedCheckoutAmount` and the `CourseBillingConfiguration`/
  `CourseBillingPeriod` sub-aggregate.
- `docs/api/course-billing.md` (new) — the full REST contract for this wave's endpoints.
- `docs/api/course-management.md` — annotated with a pointer to the above rather than rewritten.
- This file (new) — the reconstructed module plan itself.

## Related

- `docs/adr/ADR-015-free-course-zero-amount-payment-and-ledger.md`
- `docs/api/course-billing.md`, `docs/api/course-management.md`
- `docs/architecture/modular-monolith.md` §4 (Wave 2 worked example)
- `docs/parity/klass-parity-matrix.md` (PAR-05-02/03/04/06/07/08, PAR-XC-03, PAR-26-01–04, PAR-07-04)
- `backend/src/main/resources/db/migration/V37__add_course_pricing_model_and_archive.sql` through
  `V42__allow_zero_amount_ledger_entry_for_free_course_confirmations.sql`
