# Orders and Payments

**Domain:** `payment-management` (Module 12), with ledger entries owned by `ledger-settlement-management` from day one · **Portal(s):** Student, Tenant Admin, Platform Admin

## 1. Business purpose

Central, tenant-aware collection point for all student course payments in Phase 1 of the payment
roadmap — one platform-level integration surface rather than N per-tenant gateway integrations,
deferring settlement/routing complexity to later phases.

Sources: `CLAUDE.md` "Payment roadmap" §1; `docs/adr/ADR-003-centralized-payments-first.md`;
`docs/requirements/source-requirements.md` line 292.

## 2. Actors

- **Student** — creates order, initiates payment
- **Finance Staff, Tenant Admin/Institute Owner** — `V/C/E/A` on payments
- **Read-only Auditor** — view only
- **Platform Admin** — cross-tenant payment dashboard oversight only, never mixes tenant data
- **`integration-management`** — owns gateway credentials/webhook verification (not a UI-facing actor)

## 3. Preconditions

- Student authenticated and tenant context resolved server-side (never client-supplied)
- Course/pricing exists and is published
- Order is created server-side from the authenticated session

## 4. Normal flow

1. Student browses catalog/storefront, selects "Enroll."
2. Backend creates `Order` server-side, tenant-aware.
3. Student chooses payment method: gateway payment or manual slip (see [08-manual-payment-slips.md](./08-manual-payment-slips.md)).
4. Gateway path: student redirected to/embeds gateway; frontend shows a loading/"awaiting confirmation" state on return — it does **not** mark enrollment active on gateway redirect return.
5. Gateway sends a verified server-to-server webhook/callback to the backend; backend persists `Payment` row transitioning to `CONFIRMED`.
6. Enrollment activation and payment confirmation occur **in the same transaction**.
7. Frontend re-fetches and reflects confirmed state; course access unlocks only once backend returns an active enrollment record.

## 5. Alternative flows

- Payment fails/rejected by gateway: `Payment History` shows a failed/rejected state (`role="alert"`), retry path back to checkout; access remains locked.
- Payment still pending beyond a reasonable interval: distinct "pending confirmation" state (not the same copy as failure), with a Support link.
- Duplicate webhook delivery for the same payment: must be idempotent — same ledger/enrollment outcome on retry.
- Refund: new `payment`/`payment_refund` row linked to original payment ID; original terminal-state row is never mutated.
- Payment expiry/reactivation: see [18-smart-expiry.md](./18-smart-expiry.md) — reactivation always creates a new order/payment, never resurrects the old one.

## 6. Authorization rules

Order/Payment creation is student-initiated but `tenant_id` is always server-resolved, never
trusted from client. Per `docs/requirements/user-roles-and-permissions.md` §2 "Payments/slips"
row: Institute Owner and Finance Staff = `V/C/E/A`; Student Support = `V`; Read-only Auditor =
`V`. Platform Admin cross-tenant dashboard is read-oriented oversight; no destructive/state-
changing payment action may be submitted without the target tenant visibly named.

## 7. Tenant rules

Every `Order` and `Payment` row carries `tenant_id` from trusted authenticated context; never
mixed across tenants even in platform-admin views — cross-tenant aggregation is a
reporting-layer concern only. Composite index leading with `tenant_id` required (e.g.
`(tenant_id, status, created_at)` for dashboards).

## 8. Acceptance criteria

- [ ] `Order`/`Payment` schema enforces `tenant_id NOT NULL` with FK to tenant.
- [ ] Payment `status` column has a DB `CHECK` constraint over a fixed enum (`PENDING`, `CONFIRMED`, `REJECTED`, `REFUNDED`).
- [ ] Money columns are `NUMERIC`, never float.
- [ ] Test proving activation only occurs after a persisted, verified payment/approval record exists — never from a client-supplied "payment succeeded" payload.
- [ ] Idempotency test: a duplicate webhook delivery for the same payment does not double-activate enrollment or double-write ledger entries.
- [ ] Cross-tenant negative test: Tenant A staff cannot read/list Tenant B's orders/payments/dashboard.
- [ ] Enrollment row carries a `NOT NULL` FK trail to the specific confirming `Payment` row.
- [ ] Async status (submitting/processing/confirmed/failed) announced via the shared toast/live-region wrapper; status badges pair color with text/icon.

## 9. Audit requirements

**Mandatory.** `.claude/rules/security.md`'s list names "payment approvals/rejections." Entry
must capture actor, tenant, target payment/order ID, timestamp, before/after status. No code
path may activate enrollment from a client-reported payment status — such a path is a defect,
not merely a missing audit log.

## 10. MVP or later-phase classification

**MVP / Phase 1**, explicitly confirmed. `CLAUDE.md` Payment roadmap item 1; ADR-003 Decision
"Phase 1 (MVP)"; `functional-requirements.md` FR-PM-1 "MVP." Settlement (Phase 2), tenant-
specific payment accounts (Phase 3), split payments (Phase 4) are out of scope — must not be
scaffolded into Phase 1 schema/code ahead of their own approved design.

## UI-state and portal notes

- **Portal placement**: Student `Payments > Payment History`, `Outstanding Payments`, checkout; Tenant Admin `Payments > Payment Dashboard`, `Refunds`, `Payment Reports`; Platform Admin `Payments > Cross-Tenant Payment Dashboard`, `Settlement Runs`.
- Empty state: "no payments have been made yet" is explicitly distinguished from "no payments match the selected date range/filter."
- **Documentation note**: `docs/ui-ux/user-journeys.md` uses a "Checkout" screen name not actually enumerated in `docs/ui-ux/screen-map.md`'s Student Portal screen list — worth reconciling.

## Open decisions

- No payment gateway is named anywhere in the source material — gateway selection remains an open procurement decision (`payment-ledger.md` §10). Do not invent a vendor.
- Refund window/eligibility policy is unresolved.
