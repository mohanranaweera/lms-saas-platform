# Audit Logs

**Domain:** `audit-log-management` (Module A) · **Portal(s):** Tenant Admin, Platform Admin

## 1. Business purpose

Provide an immutable, queryable trail of privileged/security-sensitive actions (price changes,
payment approvals, device resets, access extensions, material deletions, settlement changes,
impersonation) for admin, finance, security, and support accountability.

Source: `docs/requirements/source-requirements.md` §4A.

## 2. Actors

- Every role that performs a privileged action is an audit *subject* (`actor_id`)
- **Tenant Admin / Institute Owner** — `V` (full, own tenant)
- **Staff sub-roles** — `V` ("own-area actions" only — enforcement mechanism unspecified, see Open Decisions)
- **Read-only Auditor** — `V` (full, tenant-scoped)
- **Platform Admin** — platform-level log + per-tenant drill-down with a persistent tenant-context banner

## 3. Preconditions

A privileged/state-changing action is being performed by an authenticated, tenant-resolved actor.

## 4. Normal flow

1. A privileged action occurs in its owning domain inside a service-layer `@Transactional` boundary.
2. The owning domain publishes a domain event describing the action.
3. `audit-log-management` consumes the event and persists its **own** audit row (never written directly into a shared table by the triggering domain) — `tenant_id`, `actor_id`, `action`, `target_entity`/`target_id`, `occurred_at`, all `NOT NULL`.
4. Tenant Admin/Read-only Auditor views the tenant-scoped, read-only `Audit Log Viewer`.
5. Platform Admin views platform-level actions plus a per-tenant drill-down (tenant-context banner shown for the duration).

## 5. Alternative flows

- Attempted edit/delete of an audit row by any actor, including a platform admin: no such endpoint/repository method exists — rejected structurally, not just by policy.
- Impersonation session: start and end each produce a distinct audit log entry, with the impersonating admin's identity and the impersonated user's identity recorded distinctly.
- Override of a payment-slip duplicate/suspicious flag: writes an audit entry per the rules in [08-manual-payment-slips.md](./08-manual-payment-slips.md).
- Cross-tenant: a Tenant Admin of tenant A attempts to list/search tenant B's audit log — rejected 403/404.

## 6. Authorization rules

Per `docs/requirements/user-roles-and-permissions.md` §2, row "Audit log": Institute Owner = `V`
(full, own tenant); each staff sub-role = `V` ("own-area actions") — i.e. Finance Staff sees only
finance-domain rows, Course Coordinator only course-domain rows, etc.; Read-only Auditor = `V`
(full, tenant-scoped).

## 7. Tenant rules

Audit rows are tenant-owned data like any other — an admin of tenant A must never list/search
tenant B's log. Schema requires `tenant_id` (or an explicit **platform-scope marker** for
genuinely platform-level events, e.g. tenant approval) plus `actor_id`/`action`/`target_entity`/
`target_id`/`occurred_at` all `NOT NULL`.

## 8. Acceptance criteria

- [ ] Given any action on the mandatory audit-logged list, then exactly one audit row is written with correct `actor_id`, `tenant_id`, action type, and target entity.
- [ ] Given an audit row exists, then no update or delete endpoint/repository method can target it (append-only enforcement test).
- [ ] Given a Tenant Admin of tenant A, when they query tenant B's audit log, then the request is rejected 403/404.
- [ ] Given an impersonation session, then start and end both produce distinct audit rows, and every action during the session records both identities distinctly.
- [ ] Given a slip-approval override with no reason supplied, then the system rejects the override before any state change or audit row is written.
- [ ] `tenant_id`/`actor_id`/`action`/`target_entity`/`target_id`/`occurred_at` are `NOT NULL` at the schema level.
- [ ] No update/delete UI affordance exists anywhere in the Audit Log Viewer.

## 9. Audit requirements

Self-referential: audit rows are append-only; every audit write happens server-side, inside the
same transaction/service boundary as the privileged action it records — never a separate,
skippable call.

## 10. MVP or later-phase classification

**MVP** — the audit-log module itself exists at MVP (FR-ALM-1; `source-requirements.md` §5 MVP
list "Audit logs"), but **individual event types only "activate" as their source feature ships**
— e.g. the settlement-amount-changed audit event cannot fire until `ledger-settlement-management`'s
Phase 2 scope exists.

## Documentation inconsistency to flag

As noted in [11-exams.md](./11-exams.md): FR-EX-2 characterizes exam-result publication as
"audit-considered," but the canonical mandatory audit-log action list in `.claude/rules/security.md`
does not include it — meaning `audit-log-management`'s MVP scope (per the canonical list) would
not produce an audit row for result publication even though the exam-management requirement
implies one should exist.

## UI-state and portal notes

- **Portal placement**: Tenant Admin `Audit Log > Audit Log Viewer`, `Staff > Activity Log`; Platform Admin `Audit Log > Platform Audit Log`, `Tenant Audit Log Drill-down`.
- Empty state: "no audit events yet" vs. "no events match your filter/date-range."
- Platform Admin's Tenant Audit Log Drill-down requires a persistent, non-dismissible tenant-context banner for the duration of the drill-down.

## Open decisions

- Whether exam-result publication requires an audit-log entry.
- The enforcement mechanism for staff sub-roles' "own-area actions" audit-log scoping is undefined.
- No retention/purge policy exists beyond "retained indefinitely by default; a future policy is a separate approved process."
