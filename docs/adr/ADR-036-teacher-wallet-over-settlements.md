# ADR-036 — Teacher wallet layered over teacher settlements

Status: Accepted 2026-11-12. The owner chose "wallet over settlements" and "wallet-only entries now"
for TEACHER-WALLET-01 (Klass parity: Teachers → Show Wallet). This ADR approves new
`teacher_settlement` kinds and a new status. That is a change to the change-controlled settlement
rules (`.claude/rules/payments.md` §4/§5). It adds **no** `ledger_entry` type.

## Context

Klass has a per-teacher wallet: an initial balance, free-form Pay Out and Pay In entries (optionally
linked to a Finance account and category) and an editable balance. The LMS already had teacher
settlements (Wave 7, V55): stored, append-only statements of the teacher's revenue share, with
one-way mark-paid and signed adjustments. These already represent "amount owed to the teacher".
A separate wallet ledger would create a second, conflicting figure. Tenant Finance Accounts
(bank/cash) do not exist; Wave 7 deferred them.

## Decision

1. **Balance meaning.** The wallet balance is the *amount payable to the teacher* =
   unpaid (`CALCULATED`) settlements and settlement adjustments + every wallet entry.
2. **Wallet entries are `teacher_settlement` rows** with new kinds and a new status `POSTED`.
   They are append-only and never marked paid:
   - `OPENING` (≥ 0). Recorded once at wallet creation; it is not a cash movement.
   - `PAY_IN` (> 0).
   - `PAY_OUT` (< 0). It may never take the balance below zero.
   - `BALANCE_ADJ` (≠ 0). The balance pencil: the user enters a target balance, the difference
     is appended with a mandatory reason, and nothing is overwritten.

   Each entry stores the balance before and after it. A `CHECK` constraint ties them together.
3. **One wallet per tenant and teacher** (`teacher_wallet`, unique `(tenant_id, teacher_id)`). The
   row holds no balance. It is the per-teacher lock: wallet postings, settlement calculation,
   settlement adjustment and mark-paid all take `SELECT … FOR UPDATE` on it first.
4. **Idempotency and staleness.** Pay In, Pay Out and Adjust require an `Idempotency-Key`. It is
   unique per tenant, stored with a SHA-256 hash of the payload. A replay returns the original
   entry; the same key with a different payload is 409. Adjust (and optionally Pay In/Out) sends
   the balance the user saw; a mismatch is 409.
5. **Double-payout guard.** Mark-paid on a settlement is refused (409) when the teacher has a
   wallet and the payment would make the balance negative. That means the money was already
   recorded as a wallet payout.
6. **No Finance posting yet.** Account- or category-linked wallet entries are rejected
   server-side (400 on `accountId`) and never silently dropped. When Finance Accounts exist:
   - A linked Pay Out may post one Finance outflow in the same transaction.
   - A linked **Pay In needs a product decision first**: what it means for an account
     (cash received from the teacher? an internal transfer?) and in which direction it posts.
7. **No effect on income or reports.** Wallet entries never write `ledger_entry` rows. They do not
   change student income, commission or settlement figures, and they are excluded from the
   settlement list and from the RPT-01 paid-settlement report (that report reads only `PAID` rows).
8. **Permissions.** View uses `TEACHERS_VIEW_WALLET`. There are four new, independent
   capabilities: `TEACHERS_WALLET_CREATE`, `TEACHERS_WALLET_PAY_IN`, `TEACHERS_WALLET_PAY_OUT`
   and `TEACHERS_WALLET_ADJUST`. All have the primary gate `FINANCE_EXPENSES`/`CREATE_EDIT` —
   the same as the settlement actions — so no default access is broadened. There is no
   teacher-portal access.

## Consequences

- Settlements and wallet payouts are two ways to record paying a teacher. The guard in decision 5
  prevents paying the same money twice. A settlement that was paid out through the wallet stays
  `CALCULATED` in the settlements list.
- No opening balance is inferred from history, and no existing row is changed.
- Adding Finance Accounts, linked postings or automatic wallet credits needs its own decision.
