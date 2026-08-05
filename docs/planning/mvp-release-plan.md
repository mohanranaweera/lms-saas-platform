# MVP Release Plan

This plan sequences the 61 stories in `docs/planning/product-backlog.md` into release waves. **The
wave order deliberately deviates from the literal 1–21 module numbering** the backlog is organized
by — the module numbering is a good *reading/reporting* order, but the solution-architect review
found several hard dependencies that run backward against it. Building strictly in module-ID order
would leave multiple modules unable to reach Definition of Done until stories many sprints later
land. This plan reorders around dependencies while keeping the backlog itself numbered for
traceability.

See `docs/planning/dependency-map.md` for the full dependency graph this plan is derived from, and
`docs/planning/risk-register.md` for the risks concentrated in Waves 3–4.

## Why the wave order isn't the module order

Two findings from the architecture review drive most of the reordering:

1. **`AUDIT-1` (Module 19) is a hard blocker, retroactively, for `TEN-2` (Module 4), `CRS-2` (Module
   8), `PAY-2`/`PAY-4` (Module 10), `SLIP-3`/`SLIP-4` (Module 11), and `ENR-2`/`ENR-3` (Module 12) —
   all of which carry a *mandatory* audit-log requirement per `.claude/rules/security.md`. `SLIP-4`
   in particular cannot function at all without a working audit-write path, since the
   override-with-no-reason rejection is defined as happening in the same transaction as the audit
   write. None of those modules can reach Definition of Done with audit logging deferred to Module
   19's literal position. **`AUDIT-1`'s schema (not the full `AUDIT-2` event-wiring) is pulled
   forward into Wave 1.**
2. **`NOTIF-1` (Module 18) is the async dispatch backbone** nearly every payment, slip, enrollment,
   and exam-result story specifies as "must be async, must carry `tenant_id`." Deferring it to
   Module 18's literal position means Waves 3–4 would have to bolt on ad hoc dispatch logic and
   retrofit it later. **`NOTIF-1`'s infrastructure (not the templates/Notification Center UI in
   `NOTIF-2`) is pulled forward into Wave 1.**

Three smaller reorderings, also from the architecture review:
- **`TEN-1`/`TEN-3`** (Module 4) are pulled into Wave 0 alongside `AUTH-1`, because login cannot
  resolve which tenant's user to authenticate against without them, despite Module 4 being numbered
  after Module 2.
- **`CRS-3`** (teacher assignment) is sequenced before `TCH-2` (assigned-courses view) within Wave
  2, even though `TCH-2` is Module 7 and `CRS-3` is Module 8 — `TCH-2` has nothing to display
  without it.
- **`ENR-1`'s `api` contract** (Module 12) is designed concurrently with `PAY-2` and `SLIP-3`
  (Module 10/11), not strictly after — the three form one coordinated slice, since payment/slip
  confirmation and enrollment activation must commit in the same transaction.

Every other story stays in a wave consistent with its module-number position.

---

## Wave 0 — Platform bedrock

**Cannot be parallelized against anything else; nothing later can start meaningfully without this
wave complete.**

`APP-1`, `APP-2`, `APP-3`, `APP-4`, `TEN-1` *(pulled forward from Module 4)*, `TEN-3` *(pulled
forward from Module 4)*, `AUTH-3`, `AUTH-1`, `AUTH-2`, `RBAC-1`, `RBAC-2`, `RBAC-3`

**Exit criteria:** a user can log in against a resolved tenant subdomain, receive a correctly
tenant-scoped JWT, have every subsequent request authorized per-role, and see a permission-denied
state driven by a real backend signal. Every tenant-owned repository built after this wave extends
`TenantAwareRepository` (`APP-4`) with a passing cross-tenant negative test.

**Internal ordering:** `APP-1`/`APP-2`/`APP-3` in parallel → `APP-4` and `AUTH-3` (both depend only
on `APP-1`) in parallel → `TEN-1` → `TEN-3` → `AUTH-1` (needs `APP-4`, `AUTH-3`, `TEN-1`, `TEN-3`) →
`AUTH-2` → `RBAC-1` (minimal enum shippable alongside `AUTH-1`; full model completes here) →
`RBAC-2` → `RBAC-3`.

## Wave 1 — Cross-cutting infrastructure pull-forward

**The two stories every later wave silently assumes exist.**

`AUDIT-1` *(schema + repository only — pulled forward from Module 19)*, `NOTIF-1` *(dispatch
infrastructure only — pulled forward from Module 18)`, `TEN-2`

**Exit criteria:** the `audit_log` table exists, is append-only-enforced, and has a working
event-consumer registration surface any domain can publish to. The async, tenant_id-carrying
notification dispatch mechanism exists and is provably decoupled from triggering transactions.
Platform Admin can approve/suspend a tenant with the resulting status change audit-logged for real
(not stubbed).

**Note:** `AUDIT-2` (wiring every domain's specific events) and `NOTIF-2` (templates/Notification
Center UI) stay in their Module 19/18 position — only the foundational schema/infrastructure moves.

## Wave 2 — People management and course structure

`STAFF-1`, `STAFF-2`, `STU-1`, `STU-2`, `TCH-1`, `CRS-1`, `CRS-2`, `CRS-3` *(pulled ahead of `TCH-2`
within this wave)*, `CRS-4`, `TCH-2`, `MAT-1`, `MAT-2`

**Exit criteria:** Tenant Admin can staff their institute, students and teachers can be onboarded
(self-service and manual/bulk), teachers can build and publish courses with a correctly audited
price-change path (consumes Wave 1's `AUDIT-1`), and course structure/materials can be attached with
server-side upload validation.

**Deferred within this wave:** `STU-3`'s full cross-domain history view only reaches its complete
acceptance criteria once `ENR-1`, `PAY-3`, `ATT-1`, and `EXM-5` exist (Waves 4/6/7) — ship the
profile and available history sections here, backfill the rest incrementally. `MAT-3`'s
fetch-time visibility enforcement is deferred to Wave 4 (see below) since it structurally needs
`ENR-1`.

## Wave 3 — Payment, slip, and enrollment cluster

**The highest-risk wave in the plan — see risk register items 1–5.**

`PAY-1`, `ENR-1` *(api contract designed here, alongside `PAY-2`/`SLIP-1`)*, `PAY-2`, `SLIP-1`,
`SLIP-2`, `SLIP-3`, `SLIP-4` *(consumes Wave 1's `AUDIT-1`)*, `ENR-2`, `ENR-3`, `PAY-3`, `PAY-4`,
`MAT-3` *(re-enabled with real enrollment-state checks now that `ENR-1` exists)`

**Exit criteria:** an order can be placed, confirmed via a verified gateway webhook or an
approved+audited manual slip, and enrollment activates atomically and exclusively from that
confirmed evidence — never from a client-reported payload. Refunds and reactivations both create
new, linked rows without mutating history. Every idempotency and cross-tenant test named in the
backlog for these 11 stories passes before this wave is considered closed.

**Do not split payment confirmation and enrollment activation across separate sprints/PRs** — they
are one coordinated slice by design (`.claude/rules/backend.md`'s same-transaction requirement).

## Wave 4 — Dashboards

`SDASH-1`, `SDASH-2`, `TDASH-1`, `TDASH-2`, `TADASH-1`, `TADASH-2`, `STU-3` *(full cross-domain
history view completed here)*

**Exit criteria:** each portal's home/overview and primary list view render correctly-scoped,
backend-filtered data with proper empty/loading/error states. Low-risk wave — pure read composition
over Waves 0–3's domains; can be pulled earlier in parallel with Wave 3 if capacity allows, since
none of its stories are hard blockers for Wave 3.

## Wave 5 — Attendance and exams

`ATT-1`, `ATT-2`, `EXM-1`, `EXM-2`, `EXM-3`, `EXM-4`, `EXM-5`

**Exit criteria:** teachers can mark attendance and manage the full exam lifecycle
(bank → schedule → attempt → auto/manual mark → publish), all backend-filtered to course
assignment and gated on `ENR-1`'s enrollment state.

## Wave 6 — Notification and audit completion

`NOTIF-2`, `AUDIT-2`, `AUDIT-3`

**Exit criteria:** every MVP-mandatory audit action from Waves 2–5 is actually wired and produces a
verified audit row (this is where the retroactive audit-event contracts from `TEN-2`, `CRS-2`,
`PAY-2`/`PAY-4`, `SLIP-3`/`SLIP-4`, `ENR-2`/`ENR-3` get consumed — if any of those stories shipped
without publishing a usable event, that gap surfaces here and must be fixed before this wave closes,
not worked around). Email/in-app notifications are live for the MVP-scoped triggers, and Tenant
Admin can view the tenant-scoped Audit Log.

## Wave 7 — Platform Admin dashboard

`PADASH-1`, `PADASH-2`

**Exit criteria:** Platform Admin has a working tenant list/approval surface and a cross-tenant
payment/audit view with the mandatory tenant-context banner on drill-down.

## Wave 8 — MVP integration and staging

`INTG-1`, `INTG-2`, `INTG-3`

**Exit criteria:** the consolidated cross-tenant/idempotency test suite is green, staging deploys
and smoke-tests cleanly with synthetic data only, and the human-gated go-live review confirms every
prior wave's Definition of Done and every open decision affecting MVP scope has been resolved or
knowingly deferred. Production deployment remains a separate, explicitly human-approved action —
this wave does not perform it.

---

## Wave summary table

| Wave | Modules touched (by number) | Story count | Can start in parallel with |
|---|---|---|---|
| 0 — Platform bedrock | 1, 2, 3 (+ TEN-1/TEN-3 pulled from 4) | 12 | Nothing — first wave |
| 1 — Infra pull-forward | 4 (TEN-2), 18 (NOTIF-1 partial), 19 (AUDIT-1 partial) | 3 | Nothing — depends on Wave 0 |
| 2 — People & course structure | 5, 6, 7, 8, 9 | 12 | — |
| 3 — Payment/slip/enrollment | 10, 11, 12 (+ MAT-3 from 9) | 11 | — |
| 4 — Dashboards | 13, 14, 15 (+ STU-3 completion) | 7 | Wave 3 (low coupling) |
| 5 — Attendance & exams | 16, 17 | 7 | — |
| 6 — Notification/audit completion | 18 (NOTIF-2), 19 (AUDIT-2/3) | 3 | — |
| 7 — Platform Admin | 20 | 2 | — |
| 8 — Integration & staging | 21 | 3 | Nothing — final wave |

Total: 61 stories across 9 waves. Waves 0, 1, 3, and 8 are strictly sequential (each fully gates the
next); Waves 2, 4, 5, 6, 7 have internal parallelization opportunities noted above.
