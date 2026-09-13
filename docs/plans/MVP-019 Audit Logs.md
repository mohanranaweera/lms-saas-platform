# MVP-019 Audit Logs

**GitHub issue:** [#19](https://github.com/mohanranaweera/lms-saas-platform/issues/19) — "[MVP] Module 19: Audit logs"
**Backlog stories covered:** `AUDIT-2` (event capture wiring) + `AUDIT-3` (Tenant Admin Audit Log Viewer)
**Spec source:** `docs/requirements/specifications/13-audit-logs.md` (domain `audit-log-management`, "Module A" in the spec's own lettering — do not confuse with spec file `19-zoom-live-classes.md`, an unrelated module that happens to share the number "19" in a different numbering scheme)
**Explicitly NOT covered here:** `AUDIT-1` (schema, append-only repository, `AuditLogApi` write contract) — **already shipped** under MVP-011, approved retroactively by `docs/adr/ADR-012-audit-log-slice-and-slip-enrollment-activation.md`. This plan does not redesign or re-migrate that work; it only adds new consumers of the existing write path and a new read path.

Status: **PROPOSED — pending product-owner sign-off on §21 open decisions before implementation begins**, mirroring `ADR-012`'s own precedent of presenting scope-crossing decisions before code is written.

---

## 1. Business goal

Close the loop between "a privileged action happened" and "it produced a durable, tamper-proof record" for the platform's already-shipped canonical mandatory-audit actions, and give Tenant Admin, staff, and Read-only Auditor a tenant-scoped, read-only window into that trail. `audit-log-management`'s schema and write contract already exist (MVP-011/ADR-012) specifically so this module could be built with "zero rework," per that ADR's own stated intent — this module is that follow-through: wire the domain events that were deliberately published-but-left-unconsumed by `course-management`, `content-management`, and `payment-management`, and ship the first read surface onto `audit_log`.

## 2. Roles and permissions

| Role | Access |
|---|---|
| Institute Owner (Tenant Admin) | Full read access to own tenant's audit log via the new viewer. |
| Read-only Auditor | Full read access to own tenant's audit log (this role has no mutating capability anywhere in the platform). |
| Staff sub-roles (Finance Staff, Course Coordinator, Exam Manager, Attendance Operator, Student Support, Content Manager, etc.) | **Open decision — see §21.** The spec (`13-audit-logs.md` §6) and backlog (`AUDIT-3`) call for "own-area actions only," but no enforcement mechanism for "own area" is defined anywhere in this codebase or its requirements docs. Two concrete options are presented in §21 for explicit product-owner selection before implementation — this plan does not pick one. |
| Teacher, Teacher Assistant, Student | No access (no `DomainArea.AUDIT_LOG` grant in the existing role matrix; unchanged by this module). |
| Platform Admin | **Out of scope for this module.** Platform-level audit log + per-tenant drill-down is `PADASH-2`, Module 20 — a separate module with its own explicit cross-tenant bypass method. Nothing in this plan builds or previews that surface. |

Authorization mechanism (no change to existing infrastructure): `PermissionCheckService.requirePermission(DomainArea.AUDIT_LOG, PermissionAction.VIEW)`, called from the new service method (not the controller), consistent with the `LedgerQueryService`/`ExamAccessGuard` precedent. `DomainArea.AUDIT_LOG` and the `VIEW` grants already exist in `PermissionCheckServiceImpl` — no RBAC data-model change.

## 3. Preconditions

- A privileged action (course/session price change, material/course content deletion, payment refund) has already been implemented in its owning domain and already publishes a domain event describing it — confirmed true for all three (see §9).
- The actor performing the audited action is an authenticated, tenant-resolved `tenant_user` with a real row in `tenant_user` (required because `audit_log.actor_id` is a `NOT NULL` FK to `tenant_user(tenant_id, id)` — see §21 for the one case, webhook-driven payment confirm/reject, where this precondition does not hold).
- The viewer's caller is an authenticated tenant user holding `DomainArea.AUDIT_LOG`/`VIEW`.

## 4. User flows

**Flow A — event capture (system-triggered, no direct user interaction with this module):**
1. A teacher/tenant admin changes a published course's price → `CourseService.changePrice` commits `course_price_history` and publishes `CoursePriceChangedEvent` inside its existing `@Transactional` method.
2. The new `AuditLogEventListener` (in `audit-log-management`) receives the event synchronously, in the same transaction, and calls `AuditLogApi.record(...)`.
3. If the audit write fails, the exception propagates back through `publishEvent(...)` and rolls back the price change too — the mutation and its audit row are genuinely all-or-nothing.
4. Same flow for a teacher/staff deleting course material (`MaterialDeletedEvent`) and Finance Staff/Institute Owner processing a refund (`PaymentRefundedEvent`).

**Flow B — Tenant Admin/Read-only Auditor views the audit log:**
1. User navigates to `Tenant Admin > Audit Log > Audit Log Viewer` (or `Staff > Activity Log`, same underlying screen).
2. Backend resolves `tenant_id` from `TenantContext` (never from a request parameter) and returns a paginated, tenant-scoped page of `audit_log` rows, most-recent-first.
3. User filters by date range, action type, and/or target entity; results update, tenant scoping is unaffected by any filter value.
4. If the tenant has zero audit rows ever, the "no audit events yet" empty state renders. If rows exist but the current filter matches none, the distinct "no events match your filter/date-range" empty state renders instead.
5. No row, anywhere in the screen, offers an edit or delete action — none exists in the DOM, not merely disabled.

**Flow C — cross-tenant attempt (negative):**
1. An authenticated user from tenant A cannot cause tenant B's rows to appear in the viewer under any filter combination — the tenant predicate is unconditionally AND-ed into every query, never optional, never a client-suppliable parameter.

## 5. Acceptance criteria

Restated to separate already-shipped (do not re-claim as new work) from genuinely new criteria for this module.

**Already satisfied by AUDIT-1/prior modules (verify via regression tests only):**
- [ ] `audit_log` schema: `tenant_id`/`actor_id`/`action`/`target_entity`/`target_id`/`occurred_at` all `NOT NULL`. (MVP-011)
- [ ] No update/delete endpoint or repository method exists for `audit_log`, for any actor. (MVP-011, `AuditLogRepository`)
- [ ] Manual payment slip approve/approve-with-override/reject write exactly one audit row each, including the reject-if-no-reason override gate. (MVP-011, `SlipReviewService`)
- [ ] Reactivation request approve/reject write exactly one audit row each. (MVP-013, `ReactivationRequestService`)
- [ ] Exam scheduling, structured-answer marking, and results publishing each write an audit row. (MVP-017 — note: these three actions are **not** on `security.md`'s canonical mandatory list; exam-management chose to self-audit them anyway, which is permitted but not required, and is unrelated to this module's own wiring work.)

**New for this module (AUDIT-2 — event wiring):**
- [ ] Given a published course's price changes, then exactly one `audit_log` row is written (`action = "course.price_changed"`, `target_entity = "course"`) with the correct `actor_id` (`changedBy`), `tenant_id`, and `target_id`, written by a new listener in `audit-log-management` — never by `course-management` calling `AuditLogApi` directly.
- [ ] Given course material is deleted, then exactly one `audit_log` row is written (`action = "material.deleted"`, `target_entity = "material"`) with the correct `actor_id` (`deletedBy`).
- [ ] Given a payment refund is processed, then exactly one `audit_log` row is written (`action = "payment.refunded"`, target entity per §7) with the correct `actor_id` (`actorUserId`).
- [ ] Given any of the three listener-driven writes above fails (e.g., a forced constraint violation), then the triggering mutation (price change / material delete / refund) is rolled back too — proven by a dedicated rollback test per listener, mirroring `PaymentConfirmationRollbackIntegrationTest`'s existing technique.
- [ ] Given a webhook-driven payment confirmation or rejection occurs, then **no audit row is written and none is expected** — this is a deliberate, flagged gap (§21), not a defect to silently fix.
- [ ] No new `audit_log` row's `tenant_id` is ever taken from the event payload's own `tenantId` field — `AuditLogService.record` continues to resolve `tenant_id` exclusively from `TenantContext`, matching `AuditLogApi`'s existing contract.

**New for this module (AUDIT-3 — viewer):**
- [ ] Given a Tenant Admin or Read-only Auditor opens the Audit Log Viewer, then only their own tenant's rows are shown, paginated, filterable by date range / action / target entity, most-recent-first.
- [ ] Given a Tenant Admin of tenant A attempts to retrieve tenant B's rows (via any filter value referencing a tenant-B entity), then the response contains zero tenant-B rows — never a distinguishing error, never partial leakage.
- [ ] Given no audit events exist yet for the tenant, then the "no audit events yet" empty state renders; given events exist but the active filter matches none, then the distinct "no events match your filter/date-range" empty state renders.
- [ ] Given any user without `DomainArea.AUDIT_LOG`/`VIEW` (e.g., Student, Teacher), then the viewer's endpoint rejects with `403`.
- [ ] Given the viewer UI in any state, then no update/delete affordance exists anywhere in the DOM.
- [ ] **Not claimed as satisfied by this module:** impersonation start/end producing distinct dual-identity audit rows (impersonation isn't built yet — explicitly excluded, see §6); staff sub-role "own-area" scoping (open decision, §21).

## 6. Out-of-scope items

- Platform Admin's platform-level audit log and per-tenant drill-down (`PADASH-2`, Module 20) — a separate module with its own cross-tenant bypass method and non-dismissible tenant-context-banner UI requirement.
- Device resets and settlement-amount changes — both Phase 2/later per the issue; no source feature exists yet to audit.
- User impersonation start/end — not built yet.
- Access/expiry extension wiring — `ENR-2`'s course-level automatic expiry exists, but the *admin bulk-extension/per-student-override* action this canonical list item refers to (`FR-EM-4`) is explicit Phase 2 scope; there is no MVP-scope action to wire yet.
- Any audit wiring for actions explicitly flagged as open decisions and not on the canonical mandatory list, which this module must not silently add or silently omit: staff account creation/role changes, student status changes, teacher approval, tenant onboarding approval/status-change, branding/theme changes, custom-domain enable/disable or DNS/TLS changes, bulk-messaging (SMS/WhatsApp) actions, course review moderation actions, exam-result publication (already self-audited by exam-management per §5, but still undecided as *mandatory*), expense-record deletion (tension between a literal hard-delete permission grant and "never delete financial history" — flagged, not resolved here), and Zoom link-sharing/recording-attach actions (tagged against spec file `19-zoom-live-classes.md`, a `live-class-management` concern, not this module's).
- Audit-log retention/purge policy — none exists; rows are retained indefinitely by default per spec, unchanged by this module.
- Any new `web`/read surface for platform-level or cross-tenant audit queries.
- Any change to the already-shipped `audit_log` schema (V21), `AuditLogApi` contract, or `AuditLogRepository`'s append-only enforcement.

## 7. Domain model

No new aggregate. `AuditLog` (existing entity, `com.lms.auditlogmanagement.domain`) is unchanged. This module adds:
- A new listener component, `AuditLogEventListener` (`com.lms.auditlogmanagement.service`), depending only on `CoursePriceChangedEvent`/`MaterialDeletedEvent`/`PaymentRefundedEvent` (each already in their owning domain's public `api` package) and the existing `AuditLogApi`.
- A new read-side service method, e.g. `AuditLogQueryService.search(AuditLogSearchCriteria, Pageable)` (`com.lms.auditlogmanagement.service`), and its DTOs (`AuditLogSearchCriteria` request, `AuditLogEntryResponse` response) in `com.lms.auditlogmanagement.api`/`web` per the existing DTO-not-entity convention.
- No change to `AuditLog`, `AuditLogRepository`'s existing method set (beyond the additive `Specification`-based query already inherited from `TenantAwareRepository`), or `AuditLogApi`.

Target-entity/action naming for the three newly wired events (final naming confirmed at implementation time against any existing convention collision):
- `course.price_changed` / `target_entity = "course"`
- `material.deleted` / `target_entity = "material"`
- `payment.refunded` / `target_entity = "payment_refund"` (the refund row, not the original payment, is the more specific and traceable target — confirm against `payments.md` §4's "traceable link to the order/payment or settlement run" requirement at implementation time; either `payment_refund` or `payment` is defensible, but pick one and keep it consistent with any existing `target_entity` values already in use for payment-adjacent audit rows).

## 8. Database design

**No new table.** The existing `audit_log` table (V21, already applied) is reused as-is for both the new writes and the new read path.

**One new, additive migration** (new file, e.g. `V30__add_audit_log_action_index.sql` — confirm exact next available version number at implementation time against `main`'s latest state):
```sql
CREATE INDEX idx_audit_log_tenant_action_occurred_at
    ON audit_log (tenant_id, action, occurred_at DESC);
```
Rationale (database-architect review): the three existing indexes (`(tenant_id, id)` unique, `(tenant_id, target_entity, target_id)`, `(tenant_id, occurred_at DESC)`, `(tenant_id, actor_id)`) do not efficiently support the viewer's "filter by action type, sorted by time, paginated" query shape — that combination would otherwise degenerate into a full tenant-partition scan plus filesort as the table grows platform-wide, per `.claude/rules/architecture.md`'s scalability guidance. This is a new, additive index file — it does not edit `V21`.

No change to `audit_log.actor_id`'s `NOT NULL`/FK constraint (see §21 — deliberately not resolved here). No change to `metadata`'s unbounded `JSONB` column (flagged as a longer-term, non-blocking scalability note by database-architect review). Longer-term, non-blocking note also raised in review: no partitioning/retention strategy exists yet for `audit_log` (or `ledger_entry`/`payment`/`notification_outbox`) — worth a future retention/partitioning ADR as volume grows, not addressed in this module.

## 9. Backend design

**New event listener (`com.lms.auditlogmanagement.service.AuditLogEventListener`):**
- Plain `@EventListener` methods (explicitly **not** `@TransactionalEventListener(phase = AFTER_COMMIT)`) for `CoursePriceChangedEvent`, `MaterialDeletedEvent`, `PaymentRefundedEvent`.
- Verified precondition: no `@EnableAsync`/custom `ApplicationEventMulticaster` exists anywhere in this backend, so `ApplicationEventPublisher.publishEvent(...)` is synchronous and same-thread by default — a plain `@EventListener` therefore executes inside the publisher's still-open transaction, and a listener exception propagates and rolls back the source mutation. This is the correct choice specifically because it preserves the "audit write is a gate on the mutation, not a best-effort side call" property `.claude/rules/security.md` requires, which `AFTER_COMMIT` would violate (the mutation would already be committed before the audit write is even attempted).
- Each listener method constructs an `AuditLogEntry` from the event's own carried actor field (`changedBy`/`deletedBy`/`actorUserId`) and calls `AuditLogApi.record(...)`. The event's own `tenantId` field is never threaded into the entry — `AuditLogService.record` resolves `tenant_id` from `TenantContext` exclusively, unchanged.
- This reconciles `.claude/rules/architecture.md`'s "audit-log-management should be a consumer of events, not reached into directly" preference for these 3 events, without retrofitting the 5 already-shipped direct-injection call sites (`SlipReviewService`, `ReactivationRequestService`, `ExamSchedulingService`, `MarkingQueueService`, `ResultsPublishingService`) — those are out of scope and stay as-is.
- `PaymentConfirmedEvent`/`PaymentRejectedEvent` get **no listener** in this module (§21).

**New read path:**
- `AuditLogQueryService` (new, `com.lms.auditlogmanagement.service`) — calls `permissionCheckService.requirePermission(DomainArea.AUDIT_LOG, PermissionAction.VIEW)` first (service-layer enforcement, not just a controller annotation, matching `LedgerQueryService`/`ExamAccessGuard` precedent), then queries via a `Specification<AuditLog>` (date-range/action/target-entity predicates) passed into `AuditLogRepository`'s existing inherited `findAll(Specification, Pageable)` — **not** a new hand-written `@Query`/derived finder with its own `tenant_id` clause, since `.claude/rules/backend.md` explicitly forbids ad hoc tenant-filter WHERE clauses. The tenant predicate is auto-AND-combined by `TenantAwareRepositoryImpl`, exactly as every other tenant-scoped repository in this codebase already relies on.
- `AuditLogController` (new, `com.lms.auditlogmanagement.web`) — thin: `@PreAuthorize("isAuthenticated()")` coarse gate (mirroring `ExamController`'s convention — the real authorization is the service-layer `requirePermission` call), delegates to `AuditLogQueryService`, maps to `PageResponse<AuditLogEntryResponse>` wrapped in `ApiResponse<T>` (existing platform envelope).
- No controller or service method anywhere accepts `tenantId` as a parameter.

**Module boundary check:** no new dependency crosses domain `api` boundaries other than the 3 existing event types (already public `api` records in their owning domains) — consistent with `.claude/rules/architecture.md`.

## 10. API contract

New file: `docs/api/audit-log-management.md` (per `docs/api/README.md`'s one-file-per-domain convention).

**`GET /api/v1/audit-log`**
- Auth: `@PreAuthorize("isAuthenticated()")` at the controller; `PermissionCheckService.requirePermission(DomainArea.AUDIT_LOG, PermissionAction.VIEW)` enforced in `AuditLogQueryService` before any query executes. No non-`AUDIT_LOG`/`VIEW` role reaches `200`.
- Query params: `page`, `size` (server-clamped to 100, platform convention), `sort` (default `occurredAt,desc`), `from`/`to` (ISO-8601 instant, optional, inclusive date range on `occurred_at`), `action` (optional, exact-match string), `targetEntity` (optional, exact-match string).
- `tenantId` is never an accepted parameter, in any form.
- Response `200`: `ApiResponse<PageResponse<AuditLogEntryResponse>>` where `AuditLogEntryResponse` = `{ id, actorId, actorDisplayName (resolved via user-management's api, best-effort — null if unresolvable), action, targetEntity, targetId, reason, metadata, occurredAt }`.
- Response `403`: caller lacks `DomainArea.AUDIT_LOG`/`VIEW` — standard platform error envelope.
- A `targetEntity`/`action`/date-range filter that matches nothing (including a value that only exists for another tenant) returns `200` with an empty `content` array — never a distinguishing error, per the cross-tenant-read-safety requirement.
- No `PUT`/`PATCH`/`DELETE` route exists for this resource, anywhere, for any role.

## 11. Frontend screens

New route: `frontend/src/app/(tenant-admin)/tenant-admin/audit-log/` — "Audit Log Viewer" (also surfaced as `Staff > Activity Log` per the spec's portal-placement note; same screen, same data, until §21's own-area decision changes that).

- Reuse the existing `QueryStateBoundary` + `EmptyState` pattern (`frontend/src/components/states/`) for the two distinct empty states already required: a whole-page swap for "no audit events yet" (zero rows ever, all filters at default), and an inline `EmptyState` + "reset filters" affordance for "no events match your filter/date-range" (rows exist, current filter yields none).
- Table: extend the existing `DataTable` component (`frontend/src/components/ui/data-table.tsx`) with an expandable-row-detail capability for `reason`/`metadata` — these don't fit a compact cell or the existing card-fallback view at phone width. Metadata renders via a new shared `Accordion` component (already spec'd in `docs/ui-ux/component-library-spec.md` §2.8 but not yet implemented in code — this page is its first consumer): collapsed to a short summary (e.g. field count), expanding to a `<pre>`-formatted block, never an inline stringified JSON blob. Needs `aria-expanded`/`aria-controls` on the toggle.
- Date-range filter needs a new shared `DateInput` component (spec'd in `docs/ui-ux/component-library-spec.md` §1.10, also not yet implemented — first consumer here): two labeled native date inputs, not a placeholder-only hint.
- Wire the existing `components/ui/live-region.tsx` to announce filter-result-count changes (`"N results found"`) for screen-reader users — no `aria-live` region currently exists for this pattern anywhere in the codebase; this page needs one since filtering silently swaps table content otherwise.
- No row action column, no bulk-action checkbox column, no context menu with any mutating option — verified absent from the component tree, not merely disabled/hidden via CSS.
- Because the "own-area" scoping open decision (§21) means every viewer with `AUDIT_LOG`/`VIEW` currently sees the *full* tenant-scoped log with no partial-view indication, the page must not display any label implying a scoped/partial view (no fake "your area" badge) until a real mechanism exists — add a code comment stating this tradeoff explicitly (mirroring the existing `slip-review/page.tsx` convention for documented interim behavior), so it isn't later mistaken for an oversight.
- Permission-denied: a real `403` flows through the existing `QueryStateBoundary`, consistent with every other tenant-admin screen — no client-side role branching decides visibility on its own.

## 12. Validation rules

- `from`/`to` query params: `from` must not be after `to` when both are supplied (`400` on violation); each must be a valid ISO-8601 instant.
- `action`/`targetEntity` filters: no format constraint beyond matching the same `VARCHAR(100)` values already written server-side — an unrecognized value simply yields zero matches, not a validation error (avoids leaking the internal set of valid action names as an enumerable list).
- `page`/`size`: standard platform pagination validation (size clamped to 100, page ≥ 0).
- No request body exists for this endpoint (`GET` only) — no DTO-level validation beyond query-param parsing.
- Event-listener side: each listener trusts its event's own constructor-enforced non-null fields (all 3 events already carry non-null `tenantId`/target id/actor id per their existing record definitions) — no additional null-checking beyond what `AuditLogEntry`'s existing compact constructor already enforces (throws on null `actorId`/blank `action`/blank `targetEntity`/null `targetId`).

## 13. Error cases

| Case | Response |
|---|---|
| Caller lacks `DomainArea.AUDIT_LOG`/`VIEW` | `403` |
| Unauthenticated request | `401` (standard filter chain behavior, unchanged) |
| `from` after `to` | `400 VALIDATION_ERROR` |
| Filter matches zero rows (including a cross-tenant-only value) | `200`, empty `content` |
| Audit write fails inside a wired listener (e.g., a hypothetical future `NOT NULL` violation) | Exception propagates, source transaction (price change/material delete/refund) rolls back entirely — no partial state, no silently-dropped audit obligation |
| Attempt to reach a `PUT`/`PATCH`/`DELETE` route for `audit_log` | No such route exists — `404`/`405` at the routing layer, not an authorization decision |
| Webhook-driven payment confirm/reject occurs | No audit row is written; expected behavior for this module's scope, not an error condition to surface to the caller (the webhook's own existing response/logging is unaffected) |

## 14. Tenant-isolation rules

- `AuditLogEventListener` writes go through the existing `AuditLogApi.record(...)` → `AuditLogService.record` path, which resolves `tenant_id` exclusively from `TenantContext`, never from the event payload's own `tenantId` field — verified this stays true for the 3 newly wired events (each event carries a `tenantId` field for other purposes, e.g. notification-management, but it is not read by the audit listener).
- The new read path is built on `TenantAwareRepository.findAll(Specification, Pageable)`, which auto-AND-combines the tenant predicate into every query regardless of filter combination — no hand-rolled `WHERE tenant_id = ...` clause is introduced.
- No new endpoint or repository method in this module accepts a caller-supplied `tenant_id` in any form (path, query, body, header).
- Mandatory cross-tenant negative test (new, at the web/service layer — AUDIT-1's existing repository-level isolation test does not cover this new query surface): tenant A's authenticated request never returns tenant B's rows under any filter combination, including a filter value that happens to reference a tenant-B entity id.
- Event consumption crosses a domain (not a thread/async) boundary but stays within the same request/transaction — no background-job tenant-context propagation concern applies here (unlike, e.g., `notification-management`'s async dispatch), since the listener runs synchronously in the publisher's own thread and transaction.

## 15. Security rules

- Append-only enforcement is already structurally guaranteed (`AuditLogRepository` overrides every delete-shaped method to throw `UnsupportedOperationException`; `AuditLog` has no setters beyond construction) — this module adds a regression test confirming that guarantee still holds, but does not change it.
- **Interim access-scope decision required before implementation — see §21.** Security review found that shipping the viewer with the full existing `AUDIT_LOG`/`VIEW` grant (already held by every staff sub-role in `PermissionCheckServiceImpl`) means, once this module's event wiring lands, any staff sub-role (e.g., Attendance Operator) could read refund amounts, actor identities, and material-deletion history — a least-privilege gap the source spec itself already flags as unresolved ("own-area" undefined). This plan does not silently resolve it; §21 presents the two live options for explicit product-owner selection.
- The audit write for each of the 3 newly wired listeners must be proven, by a dedicated integration test, to happen inside the same transaction/service boundary as the triggering privileged action (per `.claude/rules/security.md`'s "never a separate, skippable call" rule) — not merely eventually-consistent.
- No update/delete route or admin "fix" tool is introduced anywhere for `audit_log` by this module.
- This module changes no authentication mechanism, no multi-tenancy resolution mechanism, and no RBAC data model — no ADR is required for AUDIT-2/AUDIT-3 as scoped (confirmed by both solution-architect and security review).

## 16. Audit requirements

This module *is* the audit-log-management build-out — its own "audit requirements" are the acceptance criteria in §5. There is no "audit log of the audit log": `audit_log` is the terminal record, not itself an audited entity, consistent with the spec's framing.

## 17. Payment impact

- **No mutation** of `payment`, `payment_refund`, `ledger_entry`, or `payment_slip` — this module only consumes `PaymentRefundedEvent` to write a new, additive `audit_log` row referencing the refund. `AuditLogRepository`'s structural append-only enforcement means the listener cannot touch ledger/payment state even by mistake.
- Wiring `PaymentRefundedEvent` fulfills `PAY-4`'s existing "exactly one audit row per refund" acceptance criterion (previously unfulfilled, since the event had no consumer).
- **Payment gateway confirm/reject (`PAY-2`) will have no audit trail after this module ships.** This is a real, currently-unresolved gap against `.claude/rules/security.md`'s canonical "payment approvals/rejections" mandatory-audit item — see §21. It is not addressed by inventing a synthetic actor, per payment-ledger review's confirmation that doing so would silently extend `ADR-012`'s deliberately-minimal scope without the review that ADR's own "Required follow-up" section calls for.
- No change to the payment roadmap phases, no new `ledger_entry.entry_type`, no settlement logic touched.

## 18. Tests

**JUnit unit (`audit-log-management`):**
- Each new `AuditLogEventListener` method maps its event to exactly one correctly-populated `AuditLogApi.record(...)` call (actor/action/target fields).
- Explicit test asserting **no listener is registered** for `PaymentConfirmedEvent`/`PaymentRejectedEvent` — documents the gap as intentional, not silently passing by omission.
- `AuditLogSearchCriteria`/`Specification` construction: date-range/action/target-entity predicate combinations build the expected `Specification`.

**Spring Boot Testcontainers integration:**
- Per newly-wired event (course price change, material deletion, payment refund): triggering action commits → exactly one `audit_log` row with correct `actor_id`/`tenant_id`/`action`/`target_id`/`occurred_at`.
- Same-transaction atomicity: force an audit-write failure → assert the source mutation is rolled back too (mirroring `PaymentConfirmationRollbackIntegrationTest`'s technique), for each of the 3 listeners.
- Regression: `AuditLogRepository` still exposes no `update`/`delete`/`deleteById` (re-run/extend the existing append-only test, don't re-derive it from scratch).
- Viewer endpoint: pagination correctness, each filter (date range / action / target entity) in isolation and combined, default sort order.
- **Mandatory cross-tenant negative test:** tenant A's query never returns tenant B's rows, including when a filter value references a tenant-B entity id — assert row count and every returned row's `tenant_id`.
- Authorization: a role holding `DomainArea.AUDIT_LOG`/`VIEW` succeeds (`200`); a role without it (Student/Teacher fixture) is rejected (`403`). Explicitly **not** asserting a staff-sub-role "own-area" restriction — flagged as untested-because-undefined, not silently green-checked as passing.
- Idempotency: confirm whether this platform's in-process, synchronous `ApplicationEventPublisher` usage can ever redeliver an event; if genuinely single-delivery by construction (expected), document that finding instead of adding a no-op test; if not, add a duplicate-delivery test.

**Playwright (frontend):**
- Two distinct empty states render with distinct copy, not shared/generic text.
- No update/delete affordance anywhere in the DOM (assert via query absence, not visual inspection) — no edit icon, no delete button, no bulk-action checkbox, no context menu mutation option.
- Filter interactions (date range, action, target entity) reflect correctly in results and, ideally, the URL/query string.
- Metadata accordion expand/collapse behaves correctly and is keyboard-operable.
- Permission-denied state renders correctly for a fixture role without `AUDIT_LOG`/`VIEW` (if any such role can otherwise reach the route).

## 19. Documentation changes

- `docs/api/audit-log-management.md` — **new file** (per `docs/api/README.md`'s convention), documenting `GET /api/v1/audit-log` per §10 above.
- `docs/architecture/database-architecture.md` — record the new `idx_audit_log_tenant_action_occurred_at` index and the rationale (§8).
- `docs/architecture` (a relevant existing file, or a short new note under `docs/architecture/`) — record the event-to-audit-row wiring per domain (which of the 5 canonical-list actions are now wired vs. still gapped), so this doesn't only live in source-file javadocs.
- `docs/ui-ux/tenant-admin-dashboard-conventions.md` — add the Audit Log Viewer's conventions (two distinct empty states, no-mutation-affordance rule, metadata accordion pattern).
- `docs/requirements/open-decisions.md` — update the existing "audit-log-management central scoping" and "staff sub-role own-area" entries to reflect this module's actual resolution state, and add the new payment-gateway-webhook-actor gap as its own tracked entry (per payment-ledger review's explicit recommendation) so it doesn't only live in this plan file or in code comments.
- `docs/planning/product-backlog.md` — mark `AUDIT-2`/`AUDIT-3` status once shipped (existing project convention for backlog tracking, confirm exact mechanism used by prior modules at implementation time).

## 20. Implementation order

Per root `CLAUDE.md`'s development workflow (plan → backend → backend tests → frontend → frontend/E2E tests → security/tenant/integration review → docs → one logical commit), and this project's git-workflow rule against bundling backend and frontend into one commit without explicit "full-stack implementation approved":

1. **Resolve §21 open decisions with the product owner** (viewer access scope for staff sub-roles at minimum — this gates §7/§9/§11's exact shape; the payment-gateway-webhook-actor gap can be resolved later without blocking the rest of this module, since it only means deferring 2 of 5 canonical actions).
2. Backend: new migration (`idx_audit_log_tenant_action_occurred_at`), `AuditLogEventListener` (3 new listener methods), `AuditLogQueryService`/`AuditLogSearchCriteria`/`AuditLogEntryResponse`, `AuditLogController`.
3. Backend tests: unit + Testcontainers per §18, including the mandatory cross-tenant negative test and the 3 atomicity/rollback tests.
4. Run and review backend tests; fix before proceeding.
5. Frontend: Audit Log Viewer page, extended `DataTable`, new `Accordion`/`DateInput` shared components, `live-region` wiring.
6. Frontend/E2E tests per §18.
7. Security, tenant-isolation, and integration review passes (dedicated skill invocations: `security-review`, `tenant-isolation-review`, `review-api-contract`).
8. Documentation updates per §19.
9. One logical commit per this workflow's step-grouping convention (likely 2 commits total: backend, then frontend — mirroring this project's established pattern of not bundling the two without explicit approval).

## 21. Risks and unresolved decisions

These are presented for explicit product-owner decision, not resolved by this plan, per root `CLAUDE.md`'s planning requirement and mirroring `ADR-012`'s own precedent of a pre-implementation decision request.

1. **Staff sub-role viewer access scope (blocks §2/§7/§9/§11 exact shape).** No enforcement mechanism for "own-area actions" exists anywhere. Two live options:
   - **(A)** Ship the viewer using the existing coarse `DomainArea.AUDIT_LOG`/`VIEW` grant as-is — every staff sub-role holding that grant (which is currently *all* of them) sees the full tenant-scoped log, with no area restriction. Matches the literal current permission-matrix state; ships fastest; carries the least-privilege over-exposure risk security review flagged (e.g., Attendance Operator can read refund/material-deletion history).
   - **(B)** *(Security review's recommendation)* Restrict the viewer, for this MVP, to Institute Owner + Read-only Auditor only — an explicit allowlist check independent of the generic `hasPermission` grant — deferring all other staff sub-role access until "own area" is actually defined. Narrower than the literal spec's role table, but avoids the over-exposure risk; the spec's own staff-sub-role row is already caveated as unenforceable-as-written.
   - This plan does not choose between (A) and (B); implementation must not begin on §7/§9/§11 until one is selected.
   - **RESOLVED (2026-09-12):** Product owner approved **(B)** as shipped — the frontend and backend allowlist audit log viewer access to Institute Owner + Read-only Auditor only, independent of the generic `AUDIT_LOG`/`VIEW` grant. Broader staff sub-role access remains deferred until "own area" is defined; tracked as a follow-up, not further blocking this module. Recorded as a durable artifact in `docs/adr/ADR-014-audit-log-viewer-access-scope.md`.

2. **Payment-gateway webhook actor gap (blocks whether `PAY-2` audit coverage ships at all in this module).** `PaymentConfirmedEvent`/`PaymentRejectedEvent` have no real `tenant_user` actor (webhook-driven, system-initiated), but `audit_log.actor_id` is a `NOT NULL` FK to `tenant_user`. This module's recommendation is to **not** wire these two events and to track the gap explicitly (per payment-ledger review) rather than invent a synthetic actor or alter the schema unilaterally. If the product owner wants `PAY-2` audit coverage now, the live options (not decided here) are: (a) add a reserved per-tenant "system" `tenant_user` row as a well-known FK target for automated actions (database-architect's suggested schema-safe alternative, requires its own migration/seeding design), or (b) make `actor_id` nullable with a documented "null = system/automated" convention (reverses a documented schema-enforced invariant in `.claude/rules/backend.md` — a bigger change, needs its own review), or (c) defer entirely (this plan's default). Either (a) or (b) is new scope requiring its own review, not an assumed extension of this plan.

3. **`target_entity` naming for the payment-refund audit row** (`"payment"` vs `"payment_refund"`) — a small, low-stakes naming choice deferred to implementation time rather than decided here, since it has no architectural consequence either way as long as it's applied consistently.

4. **Migration version number** — this plan names `V30` illustratively; confirm the actual next available version against `main` at implementation time (another module may have landed a migration in between).

5. **Event-redelivery idempotency** — flagged in §18 as needing a one-time check of whether this platform's in-process `ApplicationEventPublisher` usage can ever redeliver an event (it should not, being synchronous and non-persisted, but this should be confirmed rather than assumed before signing off on "no idempotency test needed").

6. **Carry-forward, not new to this module, but worth restating so they aren't lost:** the exam-result-publication audit gap (`FR-EX-2` vs. `security.md`'s canonical list — a documentation inconsistency, not something this module resolves), and the Zoom link-sharing/recording-attach audit gap (tagged to spec `19-zoom-live-classes.md`, a `live-class-management` concern) — both remain open per `docs/requirements/open-decisions.md` and are out of this module's scope, but should not be silently dropped from tracking once this module ships (§19 documents where to record them).

7. **Longer-term, non-blocking scalability note** (architecture review): no partitioning/retention strategy exists yet for `audit_log`, `ledger_entry`, `payment`, or `notification_outbox` — worth a future retention/partitioning ADR as platform-wide volume grows, but not addressed by this module.
