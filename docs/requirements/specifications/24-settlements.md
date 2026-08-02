# Settlements

**Domain:** `ledger-settlement-management` (Payment roadmap Phase 2) · **Portal(s):** Platform Admin (runs), Tenant Admin (views own-tenant status)

## 1. Business purpose

Calculate and record what each tenant/tutor is owed from centrally-collected payments
(commission %, gateway fees), per Payment Roadmap Phase 2.

Sources: `CLAUDE.md` "Payment roadmap" §2; `docs/requirements/source-requirements.md` Module 12 §Phase 2.

## 2. Actors

- **Platform Admin** — runs/oversees settlement runs
- **Tenant Admin / Finance Staff** — views own-tenant settlement status
- **Teacher** — indirectly, via commission settings
- **`ledger-settlement-management`** backend

## 3. Preconditions

`payment-management` Phase 1 (centralized payments, confirmed `Payment` rows) must exist —
settlement consumes `payment-management`'s confirmed-payment events/records as source of truth
and never mutates a `payment-management` row. Append-only ledger-entry model must exist (ledger
data model itself begins in Phase 1, even though settlement *calculation* is Phase 2).

## 4. Normal flow

1. Settlement job/run triggers for a `(tenant_id, settlement_period)`.
2. `ledger-settlement-management` reads confirmed-payment events/records from `payment-management`, never mutating payment rows.
3. Commission % and gateway-fee amounts are computed and **stored** at the rate/config in effect at run time.
4. A new settlement record/ledger entry is written, referencing the source order/payment records and the settlement run.
5. Settlement status is tracked (calculated -> exported/paid) and export is available.
6. Finance dashboard surfaces settlement results.

## 5. Alternative flows

- Re-run for an already-settled period/tenant: must be idempotent — no duplicate payout ledger entries, guarded by a **DB uniqueness constraint** on `(tenant_id, settlement_period, run marker)`, not application logic alone.
- Correction needed after payout: issue a new **adjustment settlement entry** referencing the original run; never mutate the original settlement's stored figures.

## 6. Authorization rules

Settlement-run visibility/execution sits under Platform Admin and, at the tenant level, Finance &
Expenses domain permissions (Institute Owner `V/C/E/D`, Finance Staff `V/C/E/D`). **Exact split
of who can *trigger* a settlement run (Platform Admin only vs. tenant-level) is not fully
specified anywhere** (Open Decision).

## 7. Tenant rules

Settlement records carry `tenant_id`; a settlement/ledger row referencing a payment does so via
FK, with the *confirmed* state of that payment enforced by FK + service-layer guard. Cross-tenant
read/export must be isolated — a settlement run for Tenant A must never create/read ledger
entries belonging to Tenant B, even in a platform-admin aggregate view (cross-tenant aggregation
is explicitly a reporting-layer concern, not a relaxed query-layer default).

## 8. Acceptance criteria

- [ ] Idempotency test: re-running a settlement job for an already-settled `(tenant_id, settlement_period)` produces zero additional payout ledger entries and an unchanged ledger balance/row count on the second run.
- [ ] Uniqueness constraint exists on `(tenant_id, settlement_period, run marker)`, not just an application-level "already settled?" check.
- [ ] Append-only enforcement test: no repository method for settlement/ledger entities exposes `delete`/`deleteById`.
- [ ] Historical settlement figures are provably immutable after payout: a rate-config change does not alter a previously computed settlement's stored figures.
- [ ] Cross-tenant negative test on settlement read/export.
- [ ] Settlement status uses the shared Status Chip pattern, never color alone (currently unenumerated in `docs/ui-ux/component-library-spec.md` §2.10 — Open Decision).

## 9. Audit requirements

**Mandatory.** `.claude/rules/security.md` explicitly lists "settlement amount changes" in its
required audit-log action list. Every settlement correction/adjustment must be logged with actor,
tenant, before/after.

## 10. MVP or later-phase classification

**Phase 2** (not MVP). `CLAUDE.md` Payment roadmap item 2; `source-requirements.md` "Required
phase 2: Tutor/tenant settlements"; `functional-requirements.md` FR-LSM-2 "Phase 2";
`module-catalog.md` "Phase 2 (tutor/tenant settlement, commission, gateway-fee tracking,
settlement status/export)." Note: FR-LSM-1 (append-only ledger *existence*) is MVP-adjacent — the
ledger data model begins in Phase 1 even though settlement *calculation* is Phase 2.

## Change control flag

This is directly inside the "payment ledger rules" change-controlled area named in root
`CLAUDE.md`. `payment-ledger.md` §9 requires explicit approval + ADR before: adding/removing/
reinterpreting a ledger entry type, recomputing historical settlement amounts from live rate
config, or moving any Phase 3/4 concern into Phase 1/2 code or schema ahead of its own approved
design.

## UI-state and portal notes

- **Portal placement**: Platform Admin `Payments > Settlement Runs`; Tenant Admin `Finance > Tutor Payouts` (consumes settlement records).
- Settlement-run trigger/calculation is a long-running operation — needs `aria-busy` + likely async job status polling, analogous to the payment-processing pattern.
- Every cross-tenant list/action here must show tenant name/identifier on every row and require the target tenant visibly named next to any state-changing action.

## Open decisions

- Exact commission percentage(s) and whether commission varies by tenant plan/tier.
- Exact gateway-fee handling (pass-through deduction vs. platform-absorbed; variance by payment method).
- Settlement run cadence (weekly/monthly/on-demand) and exact "settlement period" boundary definition.
- Who may trigger a settlement run (Platform Admin only, or tenant-level Finance Staff/Institute Owner also).
- Which payment gateway will be integrated (indirectly affects settlement fee modeling).
