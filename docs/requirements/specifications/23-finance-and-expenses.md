# Finance and Expenses

**Domain:** `finance-expense-management` (Module 14), excluding the tutor-payout ledger linkage (owned by `ledger-settlement-management`) · **Portal(s):** Tenant Admin, Platform Admin (cross-tenant reports)

## 1. Business purpose

Give tenants operational financial visibility beyond payment collection — income/expense
dashboards, multi-account tracking, tutor payouts (consuming settlement records), and financial
reporting.

Source: `docs/requirements/source-requirements.md` Module 14.

## 2. Actors

- **Institute Owner / Tenant Admin** — `V/C/E/D`
- **Finance Staff** — `V/C/E/D`
- **Read-only Auditor** — `V`
- All other staff sub-roles — `—` (no access)

## 3. Preconditions

`ledger-settlement-management`'s settlement/payout `api` interface must exist, since tutor
payouts are **consumed** from settlement records, never computed independently. This requires
`payment-management` Phase 1 (confirmed payments) to exist.

## 4. Normal flow

1. Finance Staff/Institute Owner configures one or more bank/cash accounts.
2. Income and expenses are recorded, categorized (category-wise, account-wise).
3. Scheduled payments and wallet transactions are tracked.
4. Tutor payouts are populated by **consuming** `ledger-settlement-management`'s settlement/payout `api` interface — this domain does not compute payout amounts independently.
5. Financial reports are generated (income dashboard, expense dashboard, financial reports).

## 5. Alternative flows

- Recommended additions (not required baseline): profit/loss report, cashflow forecast, expense approval workflow, receipt attachment, monthly closing process, export to Excel/PDF.
- Cashflow forecast specifically deferred further to Phase 3.
- Monthly closing lock semantics/mutation-after-close behavior: not specified (Open Decision).

## 6. Authorization rules

Per `docs/requirements/user-roles-and-permissions.md` §2, "Finance & expenses" row: Institute
Owner `V/C/E/D`; Finance Staff `V/C/E/D`; Read-only Auditor `V`. No sub-role has an approve-specific
(`A`) permission distinct from `C/E`, unlike Payments/Courses/Exams — worth flagging as a gap if
the recommended expense-approval workflow is built, since it would need an explicit approver role
not yet defined in the matrix.

## 7. Tenant rules

Finance & expense records are tenant-owned; must carry `tenant_id` and be structurally
tenant-filtered. Tutor-payout figures in this domain must match `ledger-settlement-management`'s
settlement records exactly — no independent recomputation, and no reaching into
`ledger-settlement-management`'s repository/entities directly (must go through its `api`).

## 8. Acceptance criteria

- [ ] Tutor-payout figures match `ledger-settlement-management`'s settlement records exactly (no drift/duplication test).
- [ ] Idempotency test if this domain triggers any scheduled/tutor-payout ledger writes.
- [ ] Cross-tenant negative test on expense/income dashboards and account records.
- [ ] No repository/service method in this domain reaches into `ledger-settlement-management`'s repository/entities directly.
- [ ] Money columns are `NUMERIC`, never float — applies to every amount column across payment, ledger, settlement, and finance-expense tables without exception.
- [ ] Receipt uploads pass the standard upload-validation gate (MIME sniffing, size, ownership) before acceptance.

## 9. Audit requirements

**Open Decision.** Expense/income entries are not explicitly named in `.claude/rules/security.md`'s
mandatory audit-log list. Given `non-functional-requirements.md` §9 states "financial history
(payments, ledger entries, settlements) is never deleted," and the permission matrix grants
Finance Staff/Institute Owner literal `D` (delete) on this domain, this is a **candidate
inconsistency worth raising explicitly**: whether expense-record deletion is intended as a hard
delete or should follow the append-only/soft-delete pattern used elsewhere in the payment/ledger
cluster is unresolved, not something to decide unilaterally.

## 10. MVP or later-phase classification

**Phase 2**, not MVP. `CLAUDE.md` Payment roadmap does not name finance/expenses at Phase 1;
`functional-requirements.md` FR-FEM-1/FR-FEM-2 "Phase 2," FR-FEM-3 "Phase 2/3";
`module-catalog.md` "Phase 2 (all of Module 14), Phase 3 (cashflow forecast)."

## UI-state and portal notes

- **Portal placement**: Tenant Admin `Finance > Income Dashboard, Expense Dashboard, Accounts, Tutor Payouts, Financial Reports`; Platform Admin `Finance > Platform Finance Reports`.
- Empty states: "no income recorded this period" vs. filtered/date-range empty — should mirror the explicit payment-history empty-state differentiation pattern.
- Admin-heavy surface — needs md+ optimization with explicit mobile fallback (card view or sticky-column scroll) using the shared responsive data-table component.
- Whether "Finance & Expenses" is a top-level nav item or a tab under Payments is explicitly unresolved (`docs/ui-ux/information-architecture.md` Open Questions).

## Open decisions

- Whether expense-record deletion is a hard delete or should be append-only/soft-delete like the rest of the payment/ledger cluster — tension with the "never delete financial history" principle.
- Monthly-closing lock semantics.
- Whether expense approval (recommended addition) needs a distinct `A`-level permission not currently in the matrix.
- Whether "Finance & Expenses" is top-level nav or a Payments tab.
- The `finance-expense-management`/`ledger-settlement-management` domain-count question (whether the tutor-payout linkage is thin enough to fold into one domain) is flagged only as a documentation question in `module-catalog.md`, not a proposal to merge — current default (two domains) stands unless changed.
