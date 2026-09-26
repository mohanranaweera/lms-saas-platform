# Wave 7 Plan — Finance + Expenses + Settlement Foundation

Status: **DONE (uncommitted — awaiting human review).** Backend (V54/V55, expenses,
ledger-derived reports, teacher settlement foundation), frontend (five Finance screens),
tests, a financial-architecture/security review (one real defect found and fixed) and
documentation are complete — see Sections 12-16 for the completion record.

Scope per the Wave 7 brief: institute finance beyond Student payments — expense
categories + expenses (with receipt attachment), ledger-derived income reporting,
expense/course/teacher/period reports, and a Teacher settlement *foundation* — without
creating any second, manually-editable source of payment truth and without
payment-gateway split settlement.

Parity rows: PAR-23-01..05, PAR-24-02/03/04 (`klass-parity-matrix.md` §23/§24).

---

## 1. Phase A — Analysis (findings)

### 1.1 What already exists

| Area | Finding |
|---|---|
| RBAC | `DomainArea.FINANCE_EXPENSES` already exists (RBAC-2). Tenant Admin + Finance Staff `VIEW/CREATE_EDIT/DELETE`; Read-only Auditor `VIEW`; every other sub-role nothing. No `APPROVE` on this area for anyone. The enum javadoc already states that a `DELETE` grant never authorizes a literal ledger delete. |
| Authoritative payment truth | `ledger_entry` (V19) — append-only, `PAYMENT_CONFIRMED` (+) / `REFUND` (−, `reverses_entry_id`), written only by `LedgerEntryService`, now also for manual-slip approvals (Wave 6 fix). `LedgerViewEnrichmentService` resolves `orderId → courseId/method/…` in batched cross-module reads. |
| Course → teacher | `course.teacher_id` (single, opaque `tenant_user` id, composite FK). `CourseLookupApi#getTeacherIdsByCourseId` / `getCourseSummaries` exist. No teacher-assignment *history* is stored. |
| Upload gate | `SlipUploadService`/`MaterialService` pattern: bounded streaming read → magic-byte sniff (never trusts client MIME/extension) → store → DB save with compensating object delete → signed short-lived download URL only after a server-side authorization check. `ObjectStorageApi` (S3-compatible, fail-closed 503 when unconfigured). |
| Audit | `AuditLogApi#record(AuditLogEntry)` — same-transaction, append-only. |
| Tenant timezone | `GENERAL.default_timezone` tenant config (`TenantConfigApi`), default `UTC`. |
| Currency | Single implicit currency platform-wide/per-tenant at MVP (V11/V19 header notes); `ledger_entry` has no currency column. `PlatformCurrency.DEFAULT_CURRENCY`. |
| Finance/expense domain | **None.** `finance-expense-management` is a confirmed domain in `.claude/rules/architecture.md` but no package, table or endpoint exists. |
| Settlement | **None.** No settlement entity/table; `LedgerEntryType` is change-controlled (ADR required to add a type). |

### 1.2 Open decisions touched by this brief (from `docs/requirements/open-decisions.md`)

1. Expense deletion: hard delete vs append-only (matrix grants `D`; NFR §9 says financial
   history is never deleted).
2. Commission / revenue-share percentages and whether they vary.
3. Who may trigger a settlement run.
4. Gateway-fee handling.
5. Monthly-closing lock semantics.

None of these block a *foundation*; each is resolved below by the most conservative
option and flagged (§10), never silently.

---

## 2. Target behavior

| Item | Target | In scope |
|---|---|---|
| ExpenseCategory | Tenant-owned; name unique per tenant (case-insensitive); archive/unarchive, never delete | Yes |
| Expense | date, category, description, amount (> 0, NUMERIC(12,2)), method, reference, optional receipt attachment, creator; **append-only** — correction = void (reason required, audited) + new record | Yes |
| Receipt attachment | Standard upload gate (size, magic-byte sniff PDF/PNG/JPEG, ownership = FINANCE_EXPENSES/CREATE_EDIT in the caller's tenant); signed short-lived download URL behind FINANCE_EXPENSES/VIEW | Yes |
| Income summary | **Derived only from `ledger_entry`** via `LedgerEntryApi` (gross confirmed, refunds, net, counts) — no income table, no manual income entry | Yes |
| Expense summary | Totals by category and method, excluding voided | Yes |
| Course revenue | Ledger-derived net per course | Yes |
| Teacher revenue | Ledger-derived net per course, grouped by the course's teacher | Yes |
| Period summaries | Monthly buckets (tenant timezone): income gross/refunds/net, expenses, net result | Yes |
| Teacher revenue-share rate | Append-only, effective-dated rate history per teacher (0–100%) | Yes (foundation) |
| Teacher settlement | Calculated statement per (teacher, closed period), stored figures, per-ledger-entry source items, CALCULATED → PAID one-way, adjustments as new rows | Yes (foundation) |
| Tutor payouts view | Tenant-admin Finance screen consuming settlement records | Yes |
| Platform→tenant settlement, commission %, gateway fees, split payments | — | **No — deferred** |
| New `LedgerEntryType` / payout ledger writes | — | **No — change-controlled; settlement writes no ledger rows** |
| Bank/cash accounts, scheduled payments, wallets, monthly close, P&L export | — | **No — deferred (§11)** |

---

## 3. Database impact (additive; next version V54)

`V54__create_finance_expense_schema.sql`
- `expense_category (id, tenant_id, name, description, archived, created_at/by, updated_at/by)`
  — unique `(tenant_id, lower(name))`, `uq (tenant_id, id)` composite-FK target, index
  `(tenant_id, archived)`.
- `expense (id, tenant_id, category_id, expense_date DATE, description, amount NUMERIC(12,2) CHECK > 0,
  currency VARCHAR(3), method CHECK IN (CASH, BANK_TRANSFER, CARD, CHEQUE, ONLINE, OTHER),
  reference, attachment_object_key/filename/mime_type/size_bytes (all-or-none CHECK),
  created_by, created_at, voided_at, voided_by, void_reason)` — composite FKs to
  `expense_category` and `tenant_user` (creator, voider); CHECK that void columns are all-null
  or all-set; indexes `(tenant_id, expense_date)`, `(tenant_id, category_id)`.
  Append-only enforced at the entity (`updatable = false` on every non-void column) and
  repository (delete methods throw) layers.

`V55__create_teacher_settlement_schema.sql`
- `teacher_revenue_share_rate (id, tenant_id, teacher_id, share_percent NUMERIC(5,2) 0..100,
  effective_from DATE, created_by, created_at)` — unique `(tenant_id, teacher_id, effective_from)`,
  append-only.
- `teacher_settlement (id, tenant_id, teacher_id, kind REGULAR|ADJUSTMENT, period_start, period_end,
  gross_amount, refund_amount, net_amount, share_percent, share_amount, currency,
  adjusts_settlement_id, reason, status CALCULATED|PAID, calculated_by, calculated_at,
  paid_by, paid_at, payout_reference)` — **partial unique `(tenant_id, teacher_id, period_start,
  period_end) WHERE kind = 'REGULAR'`** (the run marker, schema-enforced idempotency per
  payments.md §5); composite self-FK for `adjusts_settlement_id`; CHECKs for kind-specific
  shape, period ordering, and `status/paid_*` consistency.
- `teacher_settlement_item (id, tenant_id, settlement_id, ledger_entry_id, course_id, amount)` —
  composite FK to `ledger_entry (tenant_id, id)`; **unique `(tenant_id, ledger_entry_id)`**, so
  no ledger entry can ever be settled twice, even across overlapping or re-run periods.

No existing table/migration touched. No ledger/payment row is ever written or mutated by
this wave's code.

---

## 4. API impact (all `/api/v1`, DTO-only, tenant from trusted context)

`finance-expense-management` (`com.lms.financeexpensemanagement`)
- `GET/POST /finance/expense-categories`, `PUT /finance/expense-categories/{id}`,
  `POST /finance/expense-categories/{id}/archive|unarchive`
- `GET /finance/expenses?from&to&categoryId&method&includeVoided&page`,
  `GET /finance/expenses/{id}`, `POST /finance/expenses` (multipart: JSON `expense` part +
  optional `attachment`), `POST /finance/expenses/{id}/void` `{reason}`,
  `GET /finance/expenses/{id}/attachment-url`
- `GET /finance/reports/summary?from&to` — income summary + expense summary + net
- `GET /finance/reports/course-revenue?from&to`
- `GET /finance/reports/teacher-revenue?from&to`
- `GET /finance/reports/periods?from&to` — monthly buckets

`ledger-settlement-management` (settlement)
- `GET/POST /finance/teacher-share-rates` (list history / append new rate)
- `POST /finance/teacher-settlements` `{teacherId, periodStart, periodEnd}` → calculate
- `GET /finance/teacher-settlements?teacherId&status`, `GET /finance/teacher-settlements/{id}`
  (with per-course breakdown)
- `POST /finance/teacher-settlements/{id}/mark-paid` `{payoutReference}`
- `POST /finance/teacher-settlements/{id}/adjustments` `{amount, reason}`

New narrow cross-module reads:
- `LedgerEntryApi#findEntriesCreatedBetween(Instant, Instant)` (tenant-scoped, course-enriched).
- Finance reports read ledger through `LedgerEntryApi` only, never `LedgerEntryRepository`.

Permissions: `VIEW` for all reads, `CREATE_EDIT` for create/update/archive/calculate/
mark-paid/adjust/rate, `DELETE` for void (the grant's meaning is narrowed to "void", never a
row delete — §10).

---

## 5. Frontend impact

Tenant Admin **Finance** nav group (visible only with FINANCE_EXPENSES/VIEW):
- `/tenant-admin/finance` — overview: date-range filter, income/expense/net cards,
  period (monthly) table.
- `/tenant-admin/finance/expenses` — filterable list, create form (with receipt upload),
  void dialog (reason required) and receipt link.
- `/tenant-admin/finance/categories` — list/create/rename/archive.
- `/tenant-admin/finance/reports` — course revenue and teacher revenue tables.
- `/tenant-admin/finance/teacher-payouts` — share-rate history + settlement list, calculate
  dialog, detail (per-course breakdown, adjustments, mark paid).
All screens use the existing `QueryStateBoundary` loading/empty/error/forbidden states;
write actions hidden for Read-only Auditor but always re-enforced server-side.

---

## 6. Security impact
- All endpoints gated server-side on `FINANCE_EXPENSES` via `PermissionCheckService`.
- Receipt upload: bounded read, magic-byte sniff, size limit, no partial write; download only
  via a 5-minute signed URL after a tenant-scoped lookup + VIEW check.
- Audit (same transaction): expense create, expense void, settlement calculation, settlement
  mark-paid, settlement adjustment, share-rate change.
- No client-supplied `tenant_id` anywhere; `teacherId`/`categoryId` are validated to belong to
  the caller's tenant (404 otherwise).

## 7. Tenant isolation impact
- Every new table carries `tenant_id NOT NULL` + tenant-leading indexes + composite FKs.
- All repositories extend `TenantAwareRepository` (ADR-006) — no method takes a tenant id.
- Cross-tenant negative tests on every new endpoint family (§8).

## 8. Test plan
Backend (JUnit + Testcontainers):
- Permissions: Student/Teacher/Content Manager → 403; Auditor → reads 200, writes 403;
  Finance Staff and Tenant Admin → full.
- Tenant isolation: tenant-B ids (category, expense, attachment, settlement, teacher) → 404;
  reports never include tenant-B figures.
- Amount validation: zero/negative/over-precision/over-scale amounts rejected (400).
- Report correctness: hand-computed fixture of confirmed + refunded ledger entries across
  two courses/teachers and two months, plus expenses (incl. voided) → exact totals.
- Financial history: void keeps the row (visible with `includeVoided`), void is one-way,
  re-void → 409, repositories expose no delete; category archive keeps expenses.
- Attachment authorization: MIME spoof → 415, oversize → 413, cross-tenant URL → 404,
  Auditor may view, Student 403.
- Settlement: exact share calculation (HALF_UP to 2 dp), rate effective-dating, re-run same
  period → 409 with no new rows, overlapping period cannot re-settle an entry,
  open period (end ≥ today) rejected, refunds reduce net, adjustment references original and
  does not mutate it, mark-paid one-way, rate change does not alter stored settlements.

Frontend (Playwright, mocked API as in previous waves): finance overview, expense create/void,
attachment validation messaging, categories, reports, payouts, auditor read-only.

## 9. Migration / rollout risk
Purely additive tables. No backfill. No change to payment/ledger/enrollment behavior.

## 10. Judgment calls (flagged for product-owner sign-off)
1. **Expenses are append-only; "delete" = void with reason.** Resolves the §1.2 #1 tension
   in favor of root `CLAUDE.md`'s "never delete financial history". The `DELETE` permission
   gates the void action. No in-place edit: correction = void + re-create.
2. **Settlement is a tenant → teacher statement, triggered by tenant Finance
   (`FINANCE_EXPENSES/CREATE_EDIT`).** Platform → tenant settlement (commission %, gateway
   fees) stays deferred because its rates are an open decision.
3. **Teacher share % is tenant-entered, effective-dated config**, snapshotted onto each
   settlement at calculation time; never recomputed. No default rate is invented — a
   calculation with no effective rate is rejected (422).
4. **Revenue attribution uses the course's teacher at calculation/report time** (no
   teacher-assignment history exists). Settled figures are frozen, so later reassignment
   never changes a stored settlement; live teacher-revenue reports do follow reassignment.
5. **Periods use the tenant's `default_timezone`**; settlement periods must be fully closed
   (end date before today in that zone).
6. **Refunds count in the period the refund was recorded**, even if the original payment was
   settled earlier (cash-basis treatment; each ledger entry is settled at most once).
7. **Single currency**: amounts are reported in the platform default currency, matching the
   existing single-implicit-currency assumption; expenses accept only that currency.
8. **PAID is a record-keeping status only** — the platform moves no money; no ledger entry is
   written.

## 11. Deferred (explicit)
Bank/cash accounts, scheduled payments, wallets, expense approval workflow (no `A` grant
exists), monthly close/lock, P&L/cashflow/export, platform finance reports, platform →
tenant settlement, commission/gateway fees, split payments, settlement export.

---

## 12. Phase B completion (backend)

Shipped as planned in §3/§4, plus one contract addition found during Phase C (§14):

- `V54__create_finance_expense_schema.sql`, `V55__create_teacher_settlement_schema.sql` —
  additive only; V1-V53 untouched.
- `com.lms.financeexpensemanagement` (new domain): `ExpenseCategory`, `Expense`,
  `ExpenseMethod`; `ExpenseCategoryService`, `ExpenseService` (+ `ExpenseWriteService`
  transactional step, `ExpenseReceiptSniffer`), `FinanceReportService`,
  `FinancePeriodResolver`; three controllers.
- `ledger-settlement-management`: `LedgerRevenueApi`/`LedgerRevenueEntry`/`LedgerRevenueService`
  (tenant-scoped, batched order→course read — the only ledger access path finance uses);
  `TeacherRevenueShareRate`, `TeacherSettlement`, `TeacherSettlementItem`,
  `TeacherSettlementService`, `TeacherSettlementController`.
- `common.persistence.AppendOnlyTenantAwareRepository` — shared "every delete throws" base
  for the three settlement repositories.
- `LedgerEntryRepository#findAllCreatedBetween` — one additive, Specification-based (tenant-
  scoped) read. No ledger write path was added or changed; `LedgerEntryType` is unchanged.

**Deviation from §4 (reasoned):** `LedgerRevenueEntry` exposes a `boolean refund` instead of
the `LedgerEntryType` enum, so `finance-expense-management` never imports another module's
`domain` package (`.claude/rules/architecture.md`). A grep-based boundary check confirms all
new code imports only other modules' `api` packages.

## 13. Phase C completion (frontend)

`tenant-admin/finance` (overview: income/expense/net cards + monthly table),
`/finance/expenses` (filters: dates/category/method/show-voided; record form with receipt;
void dialog; signed-URL receipt button), `/finance/categories` (create/rename/archive),
`/finance/reports` (course + teacher revenue), `/finance/teacher-payouts` (+ `[settlementId]`
detail: breakdown, adjustments, mark paid). Typed client `lib/api/finance.ts`, Zod schemas
`lib/validation/finance.ts`, `canViewFinance`/`canManageFinance`, shared
`DateRangeFilter`/`SignedAmount`/`SettlementStatusBadge`. Every screen uses
`QueryStateBoundary` (loading/empty/error/403), filter-aware empty states, `DataTable`
card fallback, `role="alert"` field errors and live regions; negative amounts carry a "−" and
screen-reader text, never color alone. Read-only Auditor sees no mutating control (UX only —
backend-enforced). Nav: Finance group gains Finance Summary, Expenses, Expense Categories,
Finance Reports, Teacher Payouts (`canViewFinance`).

## 14. API mismatch found in Phase C (reported, then fixed in the backend)

The payouts screen needed a teacher picker, but (1) Finance Staff hold no `TEACHERS` grant, so
`GET /api/v1/teachers` 403s for them, and (2) that endpoint returns `teacher_profile` ids while
settlement keys on the `tenant_user` id `course.teacher_id` stores. Fixed with a narrow
`usermanagement.api.TeacherLookupApi` and `GET /api/v1/finance/teachers`
(`FINANCE_EXPENSES`/`VIEW`, id/name/email only), with its own permission + cross-tenant test.

## 15. Phase D/E — tests and financial-architecture review

**Backend tests added** (all green): `ExpenseIntegrationTest` (15), `FinanceReportIntegrationTest`
(3, hand-computed fixture: month boundary 2025-03-31T23:30Z, refund recorded in a later month,
voided expense excluded, exclusive range end, cross-tenant), `TeacherSettlementIntegrationTest`
(8), `TeacherSettlementShareCalculationTest` (8 rounding cases), `FinanceUnitTest` (2).
Coverage against the brief: permissions (Finance/Admin full, Auditor read-only, Student/Teacher/
Content Manager 403, anonymous 401); tenant isolation (cross-tenant 404 on every id-addressed
endpoint, list/report exclusion, client `tenantId` ignored); amount validation (0, negative,
3 dp, non-numeric, overflow, missing); report correctness; financial history (void one-way,
reason required, audited, row kept, repositories expose no delete); attachment authorization
(415 spoof, 413 oversize, no partial write, no object key on the wire, Auditor may view,
Student 403, cross-tenant 404); settlement calculations (exact figures, idempotent re-run,
overlap, refund carry-over, rate-snapshot immunity, open period, missing rate, rate change
mid-period, adjustments, mark-paid, DB-constraint proofs, no ledger rows written).

**Frontend:** `e2e/finance.spec.ts` (15 scenarios, all green).

**Review findings (self-review against payments.md / security.md / tenancy.md / backend.md):**

1. **FIXED — lost update on concurrent one-way transitions.** Two simultaneous `mark-paid`
   calls (or expense voids) could both pass the state check; the second silently overwrote
   `paid_by`/`payout_reference` (or the void reason) and a second audit row was written.
   Fixed with `EntityManager.refresh(entity, PESSIMISTIC_WRITE)` after the tenant-scoped load;
   covered by two `CyclicBarrier` race tests (exactly one 200, one 409, one audit row). A
   two-thread race is inherently probabilistic — the tests guard against regression but cannot
   guarantee the pre-fix code would have failed on every run.
2. **FIXED (found by the full E2E run) — nav label collision.** The new nav label
   "Finance Overview" substring-matched existing specs' `getByRole('link', { name: 'View' })`
   ("Over**view**"), breaking 3 student-management tests. Renamed to "Finance Summary" after
   checking every link-name locator in the suite for collisions.
3. **Verified clean:** income is never stored/editable; no ledger row is written/updated/
   deleted by any Wave 7 path; settlement idempotency is schema-enforced (two unique
   constraints, both proven by direct-insert tests); stored figures are `updatable = false`;
   every tenant-owned table has `tenant_id NOT NULL`, tenant-leading indexes and composite FKs;
   no repository accepts a tenant id; audit writes share the business transaction;
   `markPaid` updating `status` in place is the narrow, justified status-column update
   `backend.md` permits (one-way, CHECK-constrained, row-locked, audited).
4. **FLAGGED, not fixed (pre-existing, codebase-wide):** an unknown `sort` property on any
   paged endpoint (Wave 7's included) surfaces as a generic 500, because
   `GlobalExceptionHandler` has no `PropertyReferenceException` mapping. No data leaks; the
   fix belongs in a cross-cutting change, not this wave.
5. **FLAGGED:** overlapping REGULAR periods are rejected by an application pre-check; the DB
   guarantees no *ledger entry* is settled twice, but two concurrent overlapping-period runs
   that share no entries could both succeed. No money can be double-counted either way.

## 16. Phase F/G — documentation and final verification

**Docs:** new `docs/api/finance-expense-management.md`; `docs/api/ledger-settlement-management.md`
(header corrected + "Wave 7: teacher settlement foundation"); `docs/api/README.md`;
`klass-parity-matrix.md` (§23/§24 rows + section headers, PAR-04-03's stale note);
`implementation-roadmap.md` (Wave 7 status); `docs/requirements/open-decisions.md` (interim
choices annotated as *not* resolutions). No ADR written: no change-controlled area was changed
(no ledger entry type, no enrollment activation path, no tenancy/auth change) — a human
reviewer should confirm that reading.

**Final verification (fresh runs against the final tree):**
- Backend `mvnw verify`: **1925 tests, 0 failures, 0 errors, 0 skipped — BUILD SUCCESS**
  (Wave 6 ended at 1889).
- Frontend `tsc --noEmit`: clean. `npm run lint`: 0 errors, 2 pre-existing warnings.
- Playwright full suite: **675 passed, 2 failed, 1 skipped** (Wave 6 ended at 655/7/1). Both
  failures are in `platform-admin-audit-log.spec.ts` — the pre-existing lowercase-`status`
  fixture bug Wave 6 §14 already documented, untouched by this wave. One other test
  (`student-detail-tabs.spec.ts:50`, a 300 ms loading-state window) failed once under
  full-suite load, then passed 3/3 in isolation and in the final full run — a timing flake.

**Not committed.** Backend and frontend changes span one wave; per root `CLAUDE.md` and
`.claude/rules/git-workflow.md`, committing them together needs "full-stack implementation
approved", so the commit split/PR is left to the human reviewer.
