# Finance Module (FIN-01) — Operating Note

Contract: `docs/specs/finance-module-klass-parity.md`. API: `docs/api/finance-expense-management.md`
("Finance account book"). Tenant Admin screen: Finance → Student Income / Budget Dashboard / Accounts /
Transactions / Scheduled / Categories.

## What the account book is (and is not)

- It is a **cash book per tenant**: finance accounts (Cash, Bank, Card …) with derived balances, and
  postings of money facts. It is **not** a second payment ledger. Student payments stay authoritative in
  `ledger_entry`; expenses in `expense`; teacher wallet money in `teacher_settlement`. The book only
  posts those facts to accounts and holds manual income/expense/transfer/adjustment postings.
- Student Income reads the **payment ledger** (every confirmed cash payment). Budget Dashboard reads the
  **account book** (only payments already routed to an account). Both screens say so; the Student Income
  tab shows how much of the year's income has reached the book and how many rows are waiting.

## Going live for a tenant (cutover)

1. Tenant Admin creates the accounts with their **opening balances as of the cutover date** (zero is
   allowed). Opening balances are postings of type `OPENING`; they never count as period income.
2. Tenant Admin sets **Configure** (payment-method routing): Cash, Card, Bank/Online, Other. From that
   moment every newly confirmed cash payment is posted automatically to the routed account in the same
   database transaction as the ledger entry; refunds/undo/chargebacks are posted against the account the
   original payment was posted to, even if routing has changed since.
3. Historic payments are **not** replayed automatically (they are already inside the opening balances).
   If a tenant wants the book to start earlier than its opening balances, run **Reconcile** once with
   `from` = the day *after* the opening-balance date. Reconcile is idempotent (already-posted entries are
   skipped) and Tenant Admin only. Never reconcile a range that overlaps the opening balances: that would
   count the same cash twice.
4. Payments confirmed before routing existed (or whose method has no route) appear under
   **Transactions → Unposted**. Post each one to an account once; a waiting refund is posted with it.

## Day to day

- **Record Transaction**: Income credits one account (category required); Expense debits one account
  (category required, also creates an Expenses-page row with the method derived from the account's routing
  slot); Transfer moves money between two accounts in one row (both legs or nothing).
- **Corrections**: nothing is edited or deleted. Note/category may be changed; money is corrected by
  **Reverse** (mirror row with a reason) and re-recording. Source-linked rows are corrected in their owning
  workflow: student payments in Payments (refund/undo), Finance expenses by voiding the expense (either
  from Finance or from the Expenses page — both reverse the posting), teacher wallet payouts are
  append-only (record a wallet Pay In; if cash returns, a manual income).
- **Balance pencil** on an account = a reasoned adjustment: you state the balance you saw and the target;
  the difference is posted with before/after. A stale "balance you saw" is refused (409) so two people
  cannot both apply an adjustment.
- **Archive** an account only when it is unrouted, not used by an active schedule, and at zero balance.
- **Categories** are shared with Expenses (`expense_category`); archive instead of delete; archived
  categories disappear from pickers but historical labels still resolve.

## Scheduled transactions

- The job (`app.finance.schedules.materialize-cron`, default 00:20 daily, tenant time zone) only creates
  **due** occurrences. **It never posts money and never calls a payment gateway.** A user with Record
  Transaction posts each due occurrence (Scheduled tab → Record); duplicates are impossible (unique
  schedule + due date).
- Monthly schedules fall due on the chosen day of month from the creation date. A month without that day
  (31st in April, 29th/30th/31st in February) is skipped; the schedule card lists the skipped months.
  Missed days (job down, nobody recorded) are caught up as due items. A failed posting (archived account or
  category) stays FAILED with its error and can be retried after the fix. Editing or cancelling a schedule
  never changes posted occurrences.

## Permissions (Staff → Permissions)

Reads: View Transactions, View Annual Stats (budget), View Income Summary, View Scheduled Transactions.
Writes: Manage Finance Accounts, Record Transaction (also posts unposted rows and due occurrences), Adjust
Account Balance, Reverse Transaction (same gate as Void Expense), Manage Scheduled Transactions, and
**Configure Payment Account Routing** (Tenant Admin only by default; it also unlocks Reconcile). Finance
Staff holds everything except routing by default; Read-only Auditor reads only.

## Audit and troubleshooting

- Audit actions: `finance_account.created|updated|archived|unarchived`, `finance_routing.changed`
  (before/after codes), `finance_transaction.posted|reversed|updated`, `finance_schedule.created|updated|
  cancelled|occurrence_posted|occurrence_failed|occurrence_skipped`. Amounts and account ids are recorded;
  student identities and payment references are not.
- A balance can always be rebuilt: `SUM(credits) − SUM(debits)` of the account's non-UNPOSTED rows
  (`FinanceTransactionRepository.balanceOf`). The integration test asserts this for every account.
- Log keys: `finance.payment_unposted` (routing missing), `finance.refund_not_posted_no_original`
  (pre-cutover refund), `finance.schedule_occurrences_materialized`, `finance.schedule_materialize_failed`.
- Linked wallet payouts: if the Finance posting fails validation the whole wallet entry is rolled back
  (nothing half-written). A linked wallet **Pay In** is still refused (ADR-036 §6, product decision open).
