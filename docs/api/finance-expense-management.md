# finance-expense-management — API Contract

Wave 7 (`docs/parity/waves/wave-07-plan.md`, PAR-23-01/02/04/05). Package
`com.lms.financeexpensemanagement`. Teacher settlement (PAR-23-03, PAR-24-02/03/04) lives in
`ledger-settlement-management` — see `docs/api/ledger-settlement-management.md`, "Wave 7: teacher
settlement foundation".

## Principles

- **Income is never stored or entered here.** Every income figure is derived at request time from
  the authoritative `ledger_entry` table (`PAYMENT_CONFIRMED` +, `REFUND` −) through
  `ledgersettlementmanagement.api.LedgerRevenueApi`. There is no income table, no manual income
  endpoint, and no way to edit a payment-derived figure.
- **Expenses are append-only.** No update or delete endpoint exists. The only mutation is a
  one-way **void** with a mandatory reason; the row is kept and audited. A correction is void +
  new expense (plan §10 judgment call 1 — resolves the "hard delete vs. append-only" open
  decision conservatively, pending product sign-off).
- **Authorization:** every endpoint is `isAuthenticated()` at the controller plus a real
  server-side `FINANCE_EXPENSES` check in the service — `VIEW` for reads, `CREATE_EDIT` for
  creates/updates/archives, `DELETE` for void (the matrix's "D" is narrowed to "void"). Tenant
  Admin and Finance Staff: full. Read-only Auditor: view only. Every other role: 403.
- **Tenant isolation:** tenant identity comes only from the authenticated context; any
  client-supplied `tenantId` is ignored. Another tenant's id in a path/body is a 404.
- **Dates** are calendar dates in the tenant's `GENERAL.default_timezone` (default `UTC`).
  Report ranges are inclusive `from`/`to`; both omitted = current month; `from > to` or a range
  over 731 days is 400.
- **Money** is `NUMERIC(12,2)`, serialized as JSON numbers, single platform currency
  (`PlatformCurrency.DEFAULT_CURRENCY`).

Envelope: `ApiResponse<T>`; paging: `PageResponse<T>` (see `docs/api/course-management.md`).

## Expense categories — `/api/v1/finance/expense-categories`

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `?includeArchived=false` | VIEW | Ordered by name |
| POST | body `{name, description?}` | CREATE_EDIT | 201; name unique per tenant, case-insensitive → 409 |
| PUT | `/{id}` body `{name, description?}` | CREATE_EDIT | rename; 409 on clash; 404 cross-tenant |
| POST | `/{id}/archive`, `/{id}/unarchive` | CREATE_EDIT | never deleted; archived categories can't take new expenses |

`CategoryView`: `id, name, description, archived, createdAt, updatedAt`.

## Expenses — `/api/v1/finance/expenses`

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `?from&to&categoryId&method&includeVoided=false&page&size&sort` | VIEW | default sort `expenseDate,createdAt DESC`; voided excluded unless `includeVoided` |
| GET | `/{id}` | VIEW | 404 cross-tenant |
| POST | multipart (below) | CREATE_EDIT | 201 |
| POST | `/{id}/void` body `{reason}` | DELETE | 200; blank reason 400; already voided 409; row lock serializes concurrent voids |
| GET | `/{id}/attachment-url` | VIEW | `{url, expiresAt}`, 5-minute signed URL; 404 if no receipt or cross-tenant |

**Create (multipart/form-data):** `categoryId` (UUID, required, must be active in the caller's
tenant — archived → 400, foreign/unknown → 404), `expenseDate` (ISO date, required, not in the
future), `description` (≤500, required), `amount` (> 0, ≤ 9,999,999,999.99, at most 2 decimals —
400 otherwise), `method` (`CASH|BANK_TRANSFER|CARD|CHEQUE|ONLINE|OTHER`), `reference` (≤255,
optional), `attachment` (file part, optional). There is no `tenantId`/`currency`/`createdBy`
field — all server-resolved.

**Receipt upload gate** (mirrors `SlipUploadService`): bounded streaming read, 10 MB limit
(`app.finance.expense.max-attachment-size-bytes`) → 413; magic-byte sniffing, PDF/PNG/JPEG only,
client `Content-Type`/extension ignored → 415; the object is stored before the DB write and
deleted again if the write fails (no partial write). The storage object key is never returned.

`ExpenseView`: `id, expenseDate, categoryId, categoryName, description, amount, currency,
method, reference, hasAttachment, attachmentFilename, attachmentMimeType, attachmentSizeBytes,
createdBy, createdByEmail, createdAt, voided, voidedAt, voidedBy, voidedByEmail, voidReason`.

Audit (same transaction): `expense.created`, `expense.voided` (reason + amount/date/category).

## Reports — `/api/v1/finance/reports` (all VIEW, all `?from&to`)

| Path | Response |
|---|---|
| `/summary` | `{from, to, timezone, currency, income:{gross, refunds, net, paymentCount, refundCount}, expenses:{total, count, byCategory[{categoryId, categoryName, total, count}], byMethod[{method, total, count}]}, netResult}` |
| `/course-revenue` | `{from, to, currency, rows[{courseId, courseTitle, teacherId, gross, refunds, net, paymentCount, refundCount}], totals}` — sorted by net desc |
| `/teacher-revenue` | `{from, to, currency, rows[{teacherId, teacherEmail, courseCount, gross, refunds, net, paymentCount, refundCount}], totals}` |
| `/periods` | `{from, to, timezone, currency, rows[{period:"YYYY-MM", from, to, incomeGross, refunds, incomeNet, expenses, netResult}]}` — one row per calendar month, clipped to the range |

Semantics: `gross` = confirmed payments whose ledger entry was recorded in the range; `refunds`
= refund magnitudes recorded in the range (a refund counts in the period it was recorded, even
if the payment was earlier — judgment call 6); `net = gross − refunds`; expenses use
`expenseDate` and exclude voided rows; `netResult = income.net − expenses.total`. Teacher revenue
groups by each course's **current** `teacher_id` (no assignment history exists — judgment call 4).

## Tests

`ExpenseIntegrationTest`, `FinanceReportIntegrationTest` (hand-computed fixture incl. month
boundary, refund-in-later-month, voided expense, cross-tenant exclusion), `FinanceUnitTest`;
frontend `e2e/finance.spec.ts`.

## Finance account book (FIN-01) — `/api/v1/finance/{accounts,transactions,schedules,budget,student-income}`

Plan and posting contract: `docs/specs/finance-module-klass-parity.md`. Operating note:
`docs/operations/finance-module-operating-note.md`. Migration `V125__finance_accounts.sql`.

Principles (in addition to the ones above):

- **The account book posts facts; it is never a second source of truth.** Student payments and
  refunds are posted from `ledger_entry` (one row per payment, via `LedgerEntryRecordedEvent`, same
  transaction); Finance expenses are `expense` rows plus a posting; a linked wallet Pay Out is one
  posting (`WalletPayoutLinkedEvent`, ADR-036 §6). Corrections of a source-linked posting go through
  the owning workflow (Payments refund/undo, Expenses void); the Finance "reverse" action is refused
  (409) for `LEDGER_ENTRY` and `WALLET_ENTRY` rows and voids the expense for `EXPENSE` rows.
- **Append-only.** Monetary columns are immutable; corrections are `REVERSAL` rows. The only state
  changes are `UNPOSTED -> POSTED` (a student payment finally routed to an account) and
  `POSTED -> REVERSED`. Nothing is deleted; accounts, categories and schedules are archived/cancelled.
- **Balances are derived** (`Σ credits − Σ debits` of non-UNPOSTED rows, see
  `FinanceTransactionRepository.balanceOf`) — never stored, so they cannot drift. The account row is
  the per-account lock for adjustments, transfers and archive.
- **Capabilities** (all `FINANCE_EXPENSES`-gated at the static level; Tenant Admin + Finance Staff
  by default unless noted): reads `FINANCE_DASHBOARD_TRANSACTIONS` (accounts, transactions, routing
  view), `FINANCE_DASHBOARD_ANNUAL_STATS` (budget), `FINANCE_VIEW_INCOME_SUMMARY` (student income),
  `FINANCE_VIEW_SCHEDULES`; writes `FINANCE_ACCOUNTS_MANAGE`, `FINANCE_RECORD_TRANSACTION` (also posts
  unposted rows and due occurrences), `FINANCE_ADJUST_ACCOUNT_BALANCE`, `FINANCE_REVERSE_TRANSACTION`
  (same gate as Void Expense), `FINANCE_MANAGE_SCHEDULES`, and `FINANCE_ROUTING_CONFIGURE` (+ reconcile;
  **Tenant Admin only** by default — routing decides where every future payment lands). Read-only
  Auditor: reads only. Checked in the service (`requireCapability`) and at the controller.
- **Idempotency.** `POST /transactions` and `POST /accounts/{id}/adjustments` accept an optional
  `Idempotency-Key` (unique per tenant; a replay returns the original row with 200/201). Source-linked
  postings are unique on `(tenant, source_type, source_id)`; occurrences on `(tenant, schedule, due_date)`.
- Dates: `transactionDate` is a tenant-local calendar date, never in the future; `postedAt` is the
  instant. Money: `NUMERIC(14,2)`, positive, tenant currency only (`currency` in a body must match).

### Accounts — `/api/v1/finance/accounts`

| Method | Path | Capability | Notes |
|---|---|---|---|
| GET | `?includeArchived=false` | DASHBOARD_TRANSACTIONS | `AccountView[]` ordered by name |
| GET | `/{id}` | DASHBOARD_TRANSACTIONS | 404 cross-tenant |
| POST | body `{name, description?, openingBalance}` | ACCOUNTS_MANAGE | 201; name unique per tenant (409); `openingBalance ≥ 0` (zero allowed) → one `OPENING` row |
| PUT | `/{id}` body `{name, description?}` | ACCOUNTS_MANAGE | metadata only; the balance is never written |
| POST | `/{id}/adjustments` body `{targetBalance, expectedBalance?, reason}` + `Idempotency-Key` | ADJUST_ACCOUNT_BALANCE | 201 `TransactionView` (type `ADJUSTMENT`, `balanceBefore/After`); `expectedBalance` ≠ current → 409; blank reason → 400 |
| POST | `/{id}/archive`, `/{id}/unarchive` | ACCOUNTS_MANAGE | archive is 409 when the account is routed, used by an ACTIVE schedule, or its balance ≠ 0 |
| GET | `/routing` | DASHBOARD_TRANSACTIONS | `RoutingView` |
| PUT | `/routing` body `{cashAccountId?, cardAccountId?, bankOnlineAccountId?, otherAccountId?}` | ROUTING_CONFIGURE | atomic replace; audited `finance_routing.changed` (before/after) |

`AccountView`: `id, name, description, currency, balance, archived, mappedMethods[], createdAt, updatedAt`.

`RoutingView`: `cashAccountId, cardAccountId, bankOnlineAccountId, otherAccountId, methods{CODE: accountId},
updatedAt`. Real method codes: `CASH`, `CARD`, `BANK_TRANSFER`, `GATEWAY`, `MANUAL_SLIP`, `OTHER`. The
Bank/Online slot writes `BANK_TRANSFER + GATEWAY + MANUAL_SLIP`. Resolution for a confirmed payment:
exact code → `OTHER` → none (row stays `UNPOSTED`). Routing applies to future postings only.

### Transactions — `/api/v1/finance/transactions`

| Method | Path | Capability | Notes |
|---|---|---|---|
| GET | `?search&type&accountId&categoryId&status&from&to&page&size` | DASHBOARD_TRANSACTIONS | `PageResponse<TransactionView>`, newest first (`transactionDate`, `createdAt`); `search` matches note/reason |
| GET | `/{id}` | DASHBOARD_TRANSACTIONS | 404 cross-tenant |
| GET | `/unposted` | DASHBOARD_TRANSACTIONS | student payments/refunds waiting for routing |
| POST | body `{type: INCOME\|EXPENSE\|TRANSFER, amount, transactionDate, note?, categoryId?, accountId?, fromAccountId?, toAccountId?, currency?}` + optional `Idempotency-Key` | RECORD_TRANSACTION | 201 (200 on replay). INCOME credits `accountId` (category required); EXPENSE debits `accountId` (category required; also creates an `expense` row, method from the account's routing slot, OTHER if unmapped); TRANSFER needs two different active accounts and is one row (both legs atomic) |
| PATCH | `/{id}` body `{note?, categoryId?}` | RECORD_TRANSACTION | non-monetary fields only; category not allowed on TRANSFER/OPENING/ADJUSTMENT |
| POST | `/{id}/reverse` body `{reason}` | REVERSE_TRANSACTION | 201 mirror `REVERSAL` row, original → `REVERSED`; twice → 409; OPENING/REVERSAL → 409; `EXPENSE` source → voids the expense (same transaction); `LEDGER_ENTRY` / `WALLET_ENTRY` → 409 (`correctionOwner` names the workflow) |
| POST | `/{id}/post` body `{accountId}` | RECORD_TRANSACTION | posts an `UNPOSTED` student payment once (and any `UNPOSTED` refund waiting on it, to the same account); again → 409 |
| POST | `/reconcile` body `{from?}` | ROUTING_CONFIGURE | opt-in cutover/backfill: confirmed cash payments and refunds recorded on/after `from` (default: 1st of this month) that have no posting are posted through the current routing (`UNPOSTED` when unrouted); already-posted entries are skipped; `{from, to, posted, unposted, skipped}` |

`TransactionView`: `id, type, status, amount, currency, fromAccountId, fromAccountName, toAccountId,
toAccountName, categoryId, categoryName, transactionDate, postedAt, note, sourceType, sourceId,
reversesTransactionId, reversedByTransactionId, balanceBefore, balanceAfter, reason, createdBy,
createdByEmail, createdAt, reversedAt, reversibleHere, correctionOwner (PAYMENTS | TEACHER_WALLET | EXPENSES | null)`.

Types: `OPENING` (credit), `INCOME` (credit), `EXPENSE` (debit), `TRANSFER` (debit from, credit to),
`ADJUSTMENT` (either side, reason + before/after), `REFUND` (debit the original payment's account),
`REVERSAL` (mirror). Sources: `MANUAL`, `LEDGER_ENTRY`, `EXPENSE`, `WALLET_ENTRY`, `SCHEDULE`.

Student payments: FREE and STAFF_GRANTED confirmations and zero amounts are never posted (no cash).
A refund whose original payment was never posted (pre-cutover) is not posted either, so the book never
shows a refund without its income. Audit rows (`finance_transaction.posted|reversed|updated`) are written
when an actor is present; a gateway-webhook posting has no actor and relies on the source link plus the
payments module's own audit.

### Schedules — `/api/v1/finance/schedules`

| Method | Path | Capability | Notes |
|---|---|---|---|
| GET | `?search&status&page&size` | VIEW_SCHEDULES | also materialises due occurrences for the tenant (so the tab never depends on the job alone) |
| GET | `/due` | VIEW_SCHEDULES | open (`DUE` / `FAILED`) occurrences across schedules |
| GET | `/{id}`, `/{id}/occurrences` | VIEW_SCHEDULES | |
| POST | body `{name, type: INCOME\|EXPENSE\|TRANSFER, amount, currency?, accountId?/fromAccountId?/toAccountId?, categoryId?, note?, scheduleType: ONE_TIME\|MONTHLY, scheduledDate?, dayOfMonth? (1–31)}` | MANAGE_SCHEDULES | 201 |
| PUT | `/{id}` | MANAGE_SCHEDULES | active schedules only; type cannot change; POSTED history untouched |
| POST | `/{id}/cancel` | MANAGE_SCHEDULES | open occurrences → `SKIPPED`; posted ones stay |
| POST | `/occurrences/{id}/post` | RECORD_TRANSACTION | 201 posts exactly once (`SCHEDULE` source for income/transfer; `EXPENSE` source + occurrence link for expense); again → 409; a validation failure (archived account/category) marks the occurrence `FAILED` with `lastError`, counts an attempt and returns 409 — fix and retry |
| POST | `/occurrences/{id}/skip` | MANAGE_SCHEDULES | |

**Policy (LMS, differs from Klass):** the scheduler never moves money. `FinanceScheduleJob`
(`app.finance.schedules.materialize-cron`, default 00:20 daily) only creates `DUE` occurrences per tenant
(tenant time zone); an authorised user records each one. Calendar rules: a MONTHLY schedule falls due on
`dayOfMonth` of each month from its creation date; months without that day are skipped (`skippedMonths`
in the view, shown in the UI); missed runs are caught up (every past due date gets an occurrence);
duplicate runs create nothing (unique due dates).

`ScheduleView`: `id, name, type, amount, currency, from/toAccountId(+Name), categoryId, categoryName, note,
scheduleType, scheduledDate, dayOfMonth, startsOn, status (ACTIVE|COMPLETED|CANCELLED), nextDueDate,
openOccurrences, postedOccurrences, failedOccurrences, skippedOccurrences, lastExecution, skippedMonths[],
createdAt, updatedAt, cancelledAt`. `OccurrenceView`: `id, scheduleId, dueDate, status
(DUE|POSTED|FAILED|SKIPPED), transactionId, attempts, lastError, executedAt, executedBy`.

### Budget dashboard — `GET /api/v1/finance/budget?from&to` (DASHBOARD_ANNUAL_STATS)

Reads the **account book** only. `{from, to, timezone, currency, totalBalance (current, all active
accounts), incomeForPeriod (INCOME − REFUND), grossIncomeForPeriod, refundsForPeriod, expensesForPeriod,
netActivity (= income − expenses), adjustmentsForPeriod, transfersForPeriod, incomeCount, expenseCount,
incomeByCategory[], expensesByCategory[] ({categoryId, label, total, count}; uncategorised student payments
are labelled "Student payments", wallet payouts "Teacher payouts"), accountBalances[], trendGranularity
(DAY under 92 days, else MONTH), cashFlowTrend[{period, income, expenses, net}], notes[]}`. Transfers and
opening balances are excluded from period income/expenses; a REVERSAL cancels the row it reverses in every
bucket; refunds reduce income in the period they are recorded. Same range rules as the finance reports.

### Student income — `GET /api/v1/finance/student-income?year` and `/student-income/months/{yyyy-MM}` (VIEW_INCOME_SUMMARY)

Reads the **payment ledger** (confirmed cash payments GATEWAY / MANUAL_SLIP / MANUAL_RECEIPT and their
refunds, by the ledger entry's tenant-local date). `{year, currency, timezone, generatedAt, totals
{grossIncome, refunds, netIncome, teacherShare, instituteCommission, serviceFees, unratedRevenue, expenses,
netAfterExpenses, paymentCount, averagePayment}, months[12]{month, label, income, refunds, netIncome,
expenses (expense table, non-voided), commission, teacherShare, paymentCount}, methods{onlineCount,
onlineTotal, manualCount, manualTotal, onlinePercent, byCode[{code, label, online, count, total}]},
teacherRows[{teacherId, teacherName, month, monthLabel, income, forInstitute, forTeacher, rateMissing}],
wallet (null unless the caller holds TEACHERS_VIEW_WALLET and their own tenant_user owns a wallet),
accountBook{postedStudentIncome, unpostedCount}, notes[]}`. Commission follows RPT-01 (settled snapshot,
else the share rate effective on the entry date; facility fees + postage are institute-only service fees);
the Teacher Income report remains the authoritative statement. The month endpoint adds `payments[]`
(`ledgerEntryId, orderId, paymentId, refund, amount, currency, method, methodCode, date, recordedAt,
courseCount` — no student names; the UI links to the Payments dashboard). Over 50,000 entries → 409.

### Tests

`FinanceAccountBookIntegrationTest` (accounts/routing/adjustments/archive guards, one posting per
multi-course payment, pending payments post nothing, refund follows the original account after a routing
change, unposted → post once → reconcile idempotent, manual income/expense/transfer + reversals + expense
void in both directions, linked wallet payout once with student income untouched, schedules incl. failure
and retry, cross-tenant on every surface), `FinanceScheduleRulesTest` (short months, catch-up, next due).
Frontend: `e2e/finance-hub.spec.ts`.
