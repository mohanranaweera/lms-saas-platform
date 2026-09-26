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
