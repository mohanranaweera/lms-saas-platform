# Finance Module (Klass parity) — Plan and Posting Contract (FIN-01)

Status: plan + contract written 2026-11-15 before implementation; "Shipped behaviour" appended at
hand-off. Reference: `docs/references/klass-finance-module/` (six tab screenshots; amounts are sample
data) and https://demo.klass.lk/dashboard/finance.

> Stack note. The task text said "React, Spring Boot, MariaDB". This repository is PostgreSQL by
> `CLAUDE.md`; the module is built on PostgreSQL + Flyway like every other module.

## 1. Business goal

Give Tenant Admin / Finance Staff the Klass "Finance" workspace: an account book (cash, bank, card
accounts with balances), manual income/expense/transfer postings, scheduled postings, categories, a
budget dashboard, and a Student Income overview — **without** creating a second source of truth for
student payments, refunds, expenses, or teacher wallet money. Student income stays ledger-derived;
the account book only *posts* those facts to accounts.

## 2. User roles

| Role | Access |
|---|---|
| Tenant Admin | everything, including payment-method → account routing and reconcile |
| Finance Staff (seeded) | read all tabs; record/reverse transactions; manage accounts, categories, schedules; adjust balances; **not** routing by default |
| Read-only Auditor | read-only (every write 403) |
| Operator / Manager (configurable) | per capability grants |
| Teacher | no Finance tab access. The Student Income "Wallet" panel shows **only** when the signed-in user owns a teacher wallet (never inferred from an admin role, never an account balance) |
| Student, Platform Admin | none |

## 3. What exists today (discovery, one pass)

- `ledger_entry` (`PAYMENT_CONFIRMED` +, `REFUND` −; ADR-010) is written by five confirmation paths,
  all through `LedgerEntryApi.recordPaymentConfirmed/recordRefund` in `LedgerEntryService` (one place).
  Refunds (`RefundService`) and chargebacks (`ChargebackService`) both go through `recordRefund`.
- Payment method is derived from `payment.gateway_reference` prefix (`PaymentMethod`:
  GATEWAY / MANUAL_SLIP / MANUAL_RECEIPT / FREE / STAFF_GRANTED); a manual receipt also records
  CASH / BANK_TRANSFER / CARD (`ManualReceiptFact.paymentMethod` via `PaymentReportApi`).
- `financeexpensemanagement`: `expense_category` (unique per tenant, archive only), `expense`
  (append-only, one-way void with reason), `/finance/reports/*` (ledger-derived income).
- Teacher Wallet (ADR-036, V123): wallet entries are `teacher_settlement` rows; account-linked entries
  are rejected server-side until Finance Accounts exist; ADR-036 §6 pre-approves "a linked Pay Out may
  post one Finance outflow in the same transaction"; linked Pay In needs a product decision.
- Commission: `TeacherIncomeCalculator` (RPT-01) = revenue − teacher share at the effective
  `teacher_revenue_share_rate` (or the settled snapshot). The platform takes no commission on
  tenant-collected money (payments.md §9).
- Permissions: `Capability` enum with Klass keys. Existing Finance keys: `FINANCE_VIEW_INCOME_SUMMARY`,
  `FINANCE_DASHBOARD_ANNUAL_STATS`, `FINANCE_DASHBOARD_PENDING_PAYMENTS`, `FINANCE_VIEW_EXPENSES`,
  `FINANCE_ADD_EXPENSE`, `FINANCE_VOID_EXPENSE`, `FINANCE_VIEW_CATEGORY`, `FINANCE_ADD_CATEGORY`,
  `FINANCE_DELETE_CATEGORY`, `FINANCE_DASHBOARD_TRANSACTIONS` (unavailable until now), `FINANCE_ACCOUNT_*`
  (already bound to the PayHere gateway account per ADR-017 — **not** reused for the account book).
- Jobs: plain `@Scheduled` (`@EnableScheduling` in `NotificationSchedulingConfig`); no lock library.
  Cross-tenant job reads use explicitly named `...AcrossTenants...` repository methods (ADR-006).
- Tenant time zone: `GENERAL.default_timezone` (`FinancePeriodResolver.tenantZone()`); currency:
  `TenantConfigApi.resolveDefaultCurrency`.
- Reports already built: Teacher Income (RPT-01, `/reports/teacher-income`), Registration report PDF
  (`/reports/registrations`), ledger dashboard + outstanding (`/ledger/dashboard`, `/ledger/outstanding`).

## 4. Posting and reporting contract

Terms: **account book** = `finance_account` + `finance_transaction` (new). **Ledger** = `ledger_entry`
(unchanged, authoritative for student money). Amounts are positive `NUMERIC(14,2)`; direction comes from
the transaction type. `transaction_date` is a tenant-local calendar date; `posted_at` is the instant.
Period reports filter on `transaction_date`; balances are "as of now".

| Source | Trigger | Account-book effect | Dedup key | Reversal owner | Reporting |
|---|---|---|---|---|---|
| Student payment | `LedgerEntryService.recordPaymentConfirmed` publishes `LedgerEntryRecordedEvent`; the finance listener runs synchronously **in the same transaction** | one `INCOME` row, `source=LEDGER_ENTRY`, credited to the account routed for the payment's method; amount = ledger amount (one row per payment, never per course). FREE / STAFF_GRANTED / zero amounts: no row (no cash). No routing → row saved as `UNPOSTED` (no account) and listed under "Unposted" for staff to post | unique `(tenant_id, source_type, source_id = ledger_entry_id)` | Payments → Refund / Undo (ledger `REFUND`). Finance "reverse" on this row is 409 | Student Income tab reads the **ledger** (all confirmed cash payments). Budget reads the **account book** (posted rows only). The difference is labelled on both tabs |
| Refund / undo / chargeback | `recordRefund` → same event | one `REFUND` row debited from the **original posting's account** (found through the reversed ledger entry) even if routing changed; if the original is UNPOSTED the refund is UNPOSTED too | same key with the refund entry id | n/a (already a reversal) | negative income in the period it is recorded (matches finance report judgment call 6) |
| Manual income | Record Transaction → Income | `INCOME`, `source=MANUAL`, category required | optional `Idempotency-Key` (unique per tenant) | Finance reverse → `REVERSAL` row (mirror); original status → `REVERSED` | income by category |
| Manual expense | Record Transaction → Expense | creates an **`expense` row** (existing append-only table; method derived from the account's routing slot, OTHER if unmapped) **and** one `EXPENSE` row `source=EXPENSE` | `(tenant, EXPENSE, expense_id)` | `ExpenseService.voidExpense` (Expenses page or Finance reverse — both call it); the void reverses the posting in the same transaction | expense by category; the `expense` table stays the single expense population for the existing reports |
| Transfer | Record Transaction → Transfer | one `TRANSFER` row with `from_account` and `to_account` (both legs in one row → atomic by construction) | idempotency key | `REVERSAL` (to → from) | excluded from income and expenses; Transactions tab only |
| Opening balance | New Account | one `OPENING` row (amount ≥ 0, zero allowed) credited to the account | one per account (partial unique) | none | excluded from period activity; included in balances |
| Adjustment | balance pencil: target balance + expected balance + reason | one `ADJUSTMENT` row on the credit or debit side, storing balance before/after; stale `expectedBalance` → 409 | idempotency key | `REVERSAL` | excluded from income/expenses; reported as "Adjustments" |
| Scheduled occurrence | the job (or a list refresh) materialises due dates; **posting is a manual, authorised action** (no existing policy authorises automatic money postings) | the same `INCOME` / `EXPENSE` / `TRANSFER` rows as manual, `source=SCHEDULE`, `source_id=occurrence_id` | unique `(tenant_id, schedule_id, due_date)` on occurrences; `(tenant, SCHEDULE, occurrence_id)` on transactions | reverse like a manual row; the occurrence stays `POSTED` | as manual |
| Teacher wallet Pay Out with an account | `TeacherWalletService` publishes `WalletPayoutLinkedEvent` (synchronous, same transaction) | exactly one `EXPENSE` row `source=WALLET_ENTRY` (category optional) | `(tenant, WALLET_ENTRY, settlement_id)` | wallet entries are append-only (ADR-036); no reversal path — record a wallet Pay In and, if cash returns, a manual income | expense ("Teacher payouts" when no category); **never** touches student income |
| Teacher wallet Pay In / wallet-only entries | — | **no** account-book row. Account-linked Pay In stays rejected (ADR-036 §6: meaning/direction undecided) | — | — | — |

Observed Klass vs LMS policy:
- Klass auto-posts student payments as `student payment` income rows — matched (source-linked, routed by method).
- Klass transactions have edit/delete icons — **LMS policy**: monetary fields are immutable; edit =
  note/category only; delete = reversal with reason.
- Klass Scheduled posts automatically — **LMS policy**: due occurrences are shown and recorded by an
  authorised user; the UI says so. Months without the requested day are skipped (shown in the UI).
  Missed runs are caught up as DUE occurrences; a failed posting can be retried from the UI.
- Klass "Net Profit" — labelled "Net activity (income − expenses, selected period)".
- Historic payments are **not** replayed into the book; an opt-in reconcile action creates UNPOSTED rows
  for confirmed cash payments after a chosen date so they can be posted once (documented cutover).

## 5. Acceptance criteria (testable)

1. A confirmed multi-course payment creates exactly one INCOME row with the full amount; a PENDING
   payment creates nothing; a refund debits the original account after routing was changed.
2. Without routing, confirmation still succeeds and an UNPOSTED item appears; posting it once sets the
   account; posting it twice is refused.
3. A transfer changes both balances; tenant-wide net activity for the period is unchanged; a transfer to
   an archived/foreign/same account posts nothing.
4. Opening + Σ postings = account balance (reconciliation query), adjustments included.
5. Linked wallet payout: one EXPENSE row; Student Income totals unchanged; ledger_entry count unchanged.
6. Schedules: one-time and monthly materialise correct due dates (31st skips short months), duplicate
   materialisation is idempotent, cancellation keeps completed occurrences, failed posting → FAILED then
   retry succeeds.
7. Tenant B cannot read or mutate tenant A's accounts/transactions/schedules/aggregates/pickers; 404
   (or empty aggregates), never 200 with foreign data. Read-only Auditor gets 403 on every write.
8. Existing flows unchanged: checkout, enrollment, slip approval, refund, finance reports, wallet.
9. Six tabs render populated and empty states on desktop and at 375px.

## 6. Database impact (one additive migration `V125__finance_accounts.sql`)

All tables: `tenant_id NOT NULL REFERENCES tenant(id)`, `UNIQUE (tenant_id, id)`, composite FKs to
parents, tenant-leading indexes.

- `finance_account` (name, description, currency, archived, created_by/at, updated_at). Balance is
  **derived** = Σ credits − Σ debits of POSTED/REVERSED rows touching the account (no stored balance →
  no drift); the row is the per-account lock for adjustments/archive.
- `finance_payment_routing` PK `(tenant_id, method_code)`, `method_code IN
  ('CASH','CARD','BANK_TRANSFER','GATEWAY','MANUAL_SLIP','OTHER')`, FK account; replaced atomically.
- `finance_transaction` (type `OPENING|INCOME|EXPENSE|TRANSFER|ADJUSTMENT|REFUND|REVERSAL`, status
  `POSTED|REVERSED|UNPOSTED`, amount ≥ 0 (> 0 except OPENING), currency, from_account_id, to_account_id,
  category_id (FK expense_category), transaction_date, posted_at, note, source_type
  `MANUAL|LEDGER_ENTRY|EXPENSE|WALLET_ENTRY|SCHEDULE`, source_id, reverses_transaction_id,
  idempotency_key, balance_before/after (adjustments), created_by, reason, reversed_at/by).
  Shape CHECKs per type; unique `(tenant_id, source_type, source_id)`; unique `(tenant_id,
  idempotency_key)`; unique `reverses_transaction_id` where type = REVERSAL.
- `finance_scheduled_transaction` (name, type `INCOME|EXPENSE|TRANSFER`, amount, currency, accounts,
  category, note, schedule_type `ONE_TIME|MONTHLY`, scheduled_date, day_of_month 1–31, status
  `ACTIVE|COMPLETED|CANCELLED`, created_by/at, updated_at, cancelled_at/by).
- `finance_scheduled_occurrence` (schedule_id, due_date, status `DUE|POSTED|FAILED|SKIPPED`,
  transaction_id, attempts, last_error, executed_at/by) unique `(tenant_id, schedule_id, due_date)`.

No existing table is altered. Ledger entry types unchanged (payments.md §4).

## 7. Backend impact (`com.lms.financeexpensemanagement`)

- Events `LedgerEntryRecordedEvent` and `WalletPayoutLinkedEvent` live in `ledgersettlementmanagement.api`
  (publisher side) so the dependency stays finance → ledger only. New `TeacherWalletApi` (wallet summary
  for the signed-in user).
- `domain/`: `FinanceAccount`, `FinancePaymentRouting`, `FinanceTransaction`, `FinanceScheduledTransaction`,
  `FinanceScheduledOccurrence` + enums.
- `repository/`: tenant-aware repositories (`AppendOnlyTenantAwareRepository` for transactions and
  occurrences); one explicitly named cross-tenant method for the job.
- `service/`: `FinanceAccountService` (CRUD, adjust, archive guards, routing), `FinancePostingService`
  (single writer of `finance_transaction`), `FinancePostingListener` (ledger + wallet events),
  `FinanceTransactionService` (search, record, reverse, edit note), `FinanceScheduleService` + `FinanceScheduleJob`,
  `BudgetDashboardService`, `StudentIncomeService`.
- Outside the module: `LedgerEntryService` publishes the ledger event; `TeacherWalletService` accepts
  `accountId` on PAY_OUT and publishes the wallet event (PAY_IN still rejected); `ExpenseService.voidExpense`
  reverses the linked posting; `Capability` gains account-book rows.

## 8. API contract (`/api/v1/finance/...`, envelope `ApiResponse<T>`, paging `PageResponse<T>`)

| Method | Path | Capability | Notes |
|---|---|---|---|
| GET | `/accounts?includeArchived` | FINANCE_DASHBOARD_TRANSACTIONS | `AccountView[]` with `balance` |
| POST | `/accounts` `{name, description?, openingBalance}` | FINANCE_ACCOUNTS_MANAGE | 201; name unique per tenant (409) |
| PUT | `/accounts/{id}` `{name, description?}` | FINANCE_ACCOUNTS_MANAGE | metadata only |
| POST | `/accounts/{id}/adjustments` `{targetBalance, expectedBalance, reason}` + `Idempotency-Key` | FINANCE_ADJUST_ACCOUNT_BALANCE | 201 `TransactionView`; stale → 409 |
| POST | `/accounts/{id}/archive` | FINANCE_ACCOUNTS_MANAGE | 409 if mapped, used by an ACTIVE schedule, or balance ≠ 0 |
| GET / PUT | `/accounts/routing` `{cashAccountId, cardAccountId, bankOnlineAccountId, otherAccountId}` | view: FINANCE_DASHBOARD_TRANSACTIONS; change: FINANCE_ROUTING_CONFIGURE | atomic replace; audited |
| GET | `/transactions?search&type&accountId&categoryId&status&from&to&page&size` | FINANCE_DASHBOARD_TRANSACTIONS | server-side paging, newest first |
| POST | `/transactions` `{type, amount, transactionDate, note, categoryId?, accountId?, fromAccountId?, toAccountId?}` + optional `Idempotency-Key` | FINANCE_RECORD_TRANSACTION | 201 |
| PATCH | `/transactions/{id}` `{note?, categoryId?}` | FINANCE_RECORD_TRANSACTION | non-monetary only |
| POST | `/transactions/{id}/reverse` `{reason}` | FINANCE_REVERSE_TRANSACTION | MANUAL/SCHEDULE → REVERSAL; EXPENSE → void expense; LEDGER_ENTRY/WALLET_ENTRY → 409 naming the owning workflow |
| GET | `/transactions/unposted` | FINANCE_DASHBOARD_TRANSACTIONS | UNPOSTED rows |
| POST | `/transactions/{id}/post` `{accountId}` | FINANCE_RECORD_TRANSACTION | posts an UNPOSTED row once |
| POST | `/transactions/reconcile` `{from}` | FINANCE_ROUTING_CONFIGURE | opt-in: UNPOSTED rows for confirmed cash payments since `from` with no posting |
| GET | `/budget?from&to` | FINANCE_DASHBOARD_ANNUAL_STATS | totals, categories, balances, trend |
| GET | `/student-income?year` | FINANCE_VIEW_INCOME_SUMMARY | cards, monthly series, methods, teacher × month rows, wallet (nullable) |
| GET | `/student-income/months/{yyyy-MM}` | FINANCE_VIEW_INCOME_SUMMARY | month drilldown |
| GET / POST / PUT / POST cancel | `/schedules`, `/schedules/{id}`, `/schedules/{id}/cancel` | view: FINANCE_VIEW_SCHEDULES; write: FINANCE_MANAGE_SCHEDULES | |
| GET / POST | `/schedules/{id}/occurrences`, `/schedules/occurrences/{id}/post`, `/schedules/occurrences/{id}/skip` | post: FINANCE_RECORD_TRANSACTION; skip: FINANCE_MANAGE_SCHEDULES | |
| Categories | existing `/finance/expense-categories` | existing | unchanged |

Errors: 400 field validation (`fieldErrors`), 403 capability, 404 cross-tenant/unknown, 409 conflict.

## 9. Frontend impact (Next.js, `/tenant-admin/finance/*`)

Route-based tab bar (`FinanceTabs`) on `/finance` (Student Income), `/finance/budget`, `/finance/accounts`,
`/finance/transactions`, `/finance/scheduled`, `/finance/categories` (existing page, re-skinned as cards).
The old overview moves to `/finance/summary` (nav "Finance Summary"). Charts are inline SVG with table
fallbacks (no chart library in the project). Actions render from `useCapabilities().can(...)`.

## 10. Security impact

No new secrets, no uploads. All writes audited (`finance_account.*`, `finance_routing.changed`,
`finance_transaction.posted|reversed|updated`, `finance_schedule.*`) with actor, account, amount, reason —
never student names or payment references. Capability checks happen in services
(`PermissionCheckService.requireCapability`) and at controllers (`@RequiresCapability`).

## 11. Tenant impact

Every table carries `tenant_id`; repositories are `TenantAwareRepository`; pickers (accounts,
categories) and aggregates are tenant-scoped; the scheduler job iterates tenant ids and sets the tenant
context per tenant; events carry `tenantId` and the listener asserts it equals the context. Cross-tenant
tests cover accounts, transactions, schedules, budget/student-income aggregates, routing and occurrences.

## 12. Tests

Backend (Testcontainers, `FinanceTestSupport`): `FinanceAccountBookIntegrationTest` (accounts, routing,
adjustments, transfers, reversals, permissions, cross-tenant), `FinancePostingIntegrationTest` (payment →
posting once, pending, refund after routing change, unposted, expense void, wallet payout),
`FinanceScheduleIntegrationTest` (one-time, monthly, short month, duplicates, cancel, failure/retry,
cross-tenant), `FinanceDashboardIntegrationTest` (budget + student income populations, wallet panel).
Frontend: Playwright `finance-hub.spec.ts` (six tabs, desktop + mobile, empty/populated, read-only).

## 13. Documentation

`docs/api/finance-expense-management.md` (new section), this file, `docs/operations/finance-module-operating-note.md`,
`docs/parity/klass-parity-matrix.md` rows PAR-23-01 / PAR-24-05, `docs/adr/ADR-036` consequence note.

## 14. Backend review findings (security, tenant isolation, payment ledger — 2026-11-15)

Reviewed against local code only (`feat/finance-module-klass`), no production system or data.

| Area | Result | Notes |
|---|---|---|
| Endpoint authorisation | Pass | Every controller method: `@PreAuthorize("isAuthenticated()")` + `@RequiresCapability`; every service method re-checks `requireCapability`. Read-only Auditor 403 on every write is covered by `FinanceAccountBookIntegrationTest`. |
| Tenant identity | Pass | No request carries a tenant id; all repositories are `TenantAwareRepository`; the four native aggregates take `tenantId` from `TenantContext`; the single cross-tenant query (`findTenantIdsWithActiveSchedulesAcrossTenants`) returns tenant ids only and is used by the job, which sets/clears the context per tenant. Both listeners assert `event.tenantId()` equals the request context. |
| Cross-tenant tests | Pass (one gap noted) | `tenantsAreIsolatedAcrossEveryFinanceSurface` proves 404/empty for accounts, transactions, routing, unposted, schedules, occurrences, budget and every mutation. Not separately asserted: Student Income for tenant B (it reads the ledger through `TeacherIncomeLedgerApi`, whose tenant scoping is proven by the RPT-01 suites). |
| Schema | Pass | Every new table: `tenant_id NOT NULL`, `UNIQUE (tenant_id, id)`, composite FKs to parents, tenant-leading indexes, CHECK-enforced state/shape invariants, append-only repositories. |
| Ledger rules | Pass | No `ledger_entry` type or write path changed; `LedgerEntryService` only publishes an event after the row is written. Financial rows are never updated in place or deleted; reversals/refunds are new rows. Enrollment / paid entitlement activation untouched. |
| Idempotency | Pass | Unique `(tenant, source_type, source_id)`, `(tenant, idempotency_key)`, `(tenant, schedule, due_date)`, single reversal per row; duplicate webhook delivery remains covered by `PaymentAndLedgerIntegrationTest` (green). |
| New coupling (accepted risk, mitigated) | Hardened | The Finance posting runs inside the payment-confirmation transaction so a confirmed payment and its posting commit together. A database failure in the posting still rolls the confirmation back (correct: never a half-written book); a non-database (logic) failure is logged as `finance.ledger_posting_skipped` and the confirmation commits — Reconcile backfills. Missing routing is not a failure (UNPOSTED). |
| Secrets / uploads / protected content | n/a | None introduced. |
| Change-controlled areas | Flagged, not changed | Capability catalog gains Finance account-book rows (same `FINANCE_EXPENSES` static gate; routing Tenant Admin only). ADR-036 addendum records the linked wallet payout posting that decision 6 pre-approved. Ledger entry types, enrollment/entitlement rules, auth architecture and tenancy strategy are unchanged. |

Open product decisions carried forward (not blocking): account-linked wallet **Pay In** meaning (ADR-036 §6); whether the Klass-style **automatic** posting of scheduled transactions should ever be enabled (today: manual recording only); whether Finance expenses should allow a receipt upload from the Transactions tab (today: use the Expenses page).
