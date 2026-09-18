# Database Impact Analysis (Wave 0)

Status: analysis only. No migration was created, edited, or reverted to produce this document.

## 1. Current migration inventory (V1–V35)

All 35 migrations are additive; none was found to edit an already-applied migration's original
statements. `V32`→`V33`→`V34` is a good example of the correct pattern: `V32` relaxes an audit-log
actor FK for platform-admin actors, `V33` restores a stricter trigger, `V34` tightens it further —
three additive corrections layered on top of each other, not a rewrite of `V32`.

| Migration | Tables/objects | Klass domain |
|---|---|---|
| V1 | baseline conventions (comment-only / extension setup) | infra |
| V2 | `tenant` | tenant-onboarding |
| V3 | `tenant_user` | identity-access-service |
| V4 | `platform_admin_user` | identity-access-service |
| V5 | `device_session` | identity-access-service (auth session, not yet device-limit) |
| V6 | `platform_admin_session` | identity-access-service |
| V7 | `role` (catalog) | identity-access-service |
| V8 | repoint `tenant_user.role` to catalog | identity-access-service |
| V9 | forbid `PLATFORM_ADMIN` on `tenant_user.role` | identity-access-service |
| V10 | `staff_profile` | staff-management |
| V11 | course-management schema (`course`, `course_module`, `course_lesson`, price history) | course-management |
| V12 | course price history survives course deletion | course-management |
| V13 | drop redundant course-structure indexes | course-management |
| V14 | cascade delete course structure | course-management |
| V15 | `course_lesson` tenant-scoped unique constraint | course-management |
| V16 | content-management schema (`material`) | lessons-and-materials |
| V17 | `student_profile` | student-management |
| V18 | `teacher_profile` | teacher-management |
| V19 | payment-management schema (`order`, `payment`) | orders-and-payments |
| V20 | payment-management review fixes | orders-and-payments |
| V21 | payment slip schema (`payment_slip`, `payment_slip_flag`) | manual-payment-slips / duplicate-slip-detection |
| V22 | enrollment expiry + reactivation schema | enrollments / smart-expiry |
| V23 | reactivation/enrollment indexes | enrollments |
| V24 | strengthen reactivation-request live uniqueness | enrollments |
| V25 | attendance-management schema | attendance |
| V26 | exam-management schema | exams |
| V27 | cap exam-answer manual score at one point | exams |
| V28 | notification-management schema | notifications |
| V29 | notification outbox claimed-at reconciliation | notifications |
| V30 | audit-log action index | audit-logs |
| V31 | platform-admin cross-tenant dashboard indexes | audit-logs / reporting (ad hoc) |
| V32 | relax audit-log actor FK for platform-admin actors | audit-logs |
| V33 | restore audit-log actor-integrity trigger | audit-logs |
| V34 | tighten audit-log actor-tenant-match trigger | audit-logs |
| V35 | drop unused payment `created_at`/tenant index | orders-and-payments |

## 2. Tenant-isolation posture of existing tables

Per `.claude/rules/tenancy.md` and `.claude/rules/backend.md`, every tenant-owned table needs
`tenant_id NOT NULL`, a tenant-leading index, and tenant-scoped (not global) unique constraints.
Based on the migration inventory and the domain specs' own explicit acceptance-criteria language
(each of which asserts these properties as already-shipped facts for MVP-scope tables — e.g.
"`Order`/`Payment` schema enforces `tenant_id NOT NULL`", "`UNIQUE (tenant_id, email)` — never a
global unique constraint"), the shipped tables (`tenant_user`, `staff_profile`,
`student_profile`, `teacher_profile`, `course`/`course_module`/`course_lesson`, `material`,
`order`/`payment`, `payment_slip`/`payment_slip_flag`, `enrollment`/`reactivation_request`,
`attendance_record`, exam-management's tables, notification-management's tables,
`audit_log`, `device_session`, `ledger_entry`) are recorded by their owning specs as already
satisfying this posture. **This Wave 0 pass did not re-derive each constraint from the raw SQL
file-by-file** — that would require re-reading all 35 files in full, which was judged lower
value than the domain/API/frontend/RBAC inventory given the existing specs already assert
constraint-level compliance per acceptance criterion. If a deeper schema audit is wanted before
Wave 1, it should be scoped as its own task using `database-architect`/`security-reviewer`
against the raw SQL rather than folded into this Wave 0 pass.

**No shared/global table holding multi-tenant rows without a `tenant_id` discriminator was
identified.** The two platform-level tables (`tenant`, `platform_admin_user`/
`platform_admin_session`) are correctly platform-scoped, not tenant-owned, and are not FK
targets from tenant-owned tables in the wrong direction.

## 3. Database objects entirely absent for missing domains

These Klass parity domains have **zero database footprint** today (cross-referenced against
`klass-parity-matrix.md`):

| Domain | Expected core tables (not yet migrated) |
|---|---|
| White Labelling (14) | tenant branding/theme config table |
| Custom Domains (15) | tenant custom-domain + verification-status table |
| Device Authentication (16, beyond auth) | device-slot/limit-policy table; `device_session` needs additive extension, not a new parallel table |
| Session/View Limits (17) | video watch-session/view-count table (Postgres-authoritative policy; Redis for ephemeral state) |
| Zoom/Live Classes (19) | `class_session`, meeting-provider-reference table |
| Secure Video (20) | `video_asset`, `video_playback_policy`, `video_watch_session`, `video_watch_progress` |
| SMS (21) / WhatsApp (22) | notification template, delivery-log, provider-config tables (tenant-scoped) |
| Finance & Expenses (23) | `expense_category`, `expense`, account tables |
| Settlements (24) | `settlement_run`, `settlement_line`/adjustment tables with `(tenant_id, settlement_period, run_marker)` uniqueness |
| Course Reviews (26) | `course_review`, moderation-status columns |
| Tenant Configuration (cross-cutting, PAR-XC-02) | a typed `tenant_config`-shaped table (or one table per config domain) — currently **nothing** exists; `common/config/TenantConfig.java` is only the tenant-context-resolution Spring bean, not a settings table |

## 4. Schema changes required on **existing** tables (not new domains)

These are additive changes to tables that already exist, flagged because they touch
high-integrity domains where `.claude/rules/backend.md`'s "schema-enforced invariants" rule
applies:

- **`course`**: adding a pricing-model enum + billing-period concept (PAR-05-04) is additive at
  the column level, but the *meaning* of `course.price` changes from "the price" to "the legacy
  ONE_TIME price," which downstream code (checkout, price-change audit) must not silently
  misinterpret. Treat as a schema-and-code co-migration, not a pure schema add — see
  `migration-strategy.md` §"Pricing model expansion."
- **`device_session`**: extending with a device-slot/limit concept (PAR-16-01/02) should reuse
  this table additively (new nullable columns + a follow-up backfill/constraint migration) rather
  than creating a parallel `device` table, per the existing table's own migration comment noting
  `reset_at` is "reserved" for this purpose.
- **`attendance_record`**: if `ClassSession` (PAR-19-02) is introduced, `attendance_record`'s
  `session_id` FK currently targets `course_lesson (tenant_id, id)` by an explicit MVP-016
  decision. Repointing it to a new `class_session` table is a **migration-risk item**, not a
  routine additive change — see `migration-strategy.md` §"ClassSession introduction" for the
  recommended dual-write/backfill approach.
- **`ledger_entry`**: settlement calculation (PAR-24-02) will add new row *types* referencing
  ledger entries; per root `CLAUDE.md`'s change control on "payment ledger rules," adding a new
  ledger entry type requires an ADR before implementation, not just a migration.

## 5. Constraints and invariants to carry into every new migration

Restated from `.claude/rules/backend.md` and `.claude/rules/tenancy.md` for direct use when
writing Wave 1+ migrations:

1. `tenant_id UUID NOT NULL REFERENCES tenant (id)` on every new tenant-owned table — no
   nullable "backfill later" pattern.
2. A composite index leading with `tenant_id` matching the table's actual query shape.
3. Tenant-scoped unique constraints (`UNIQUE (tenant_id, ...)`), never a bare global unique
   constraint, for anything conceptually "unique per tenant" (e.g. a future
   `UNIQUE (tenant_id, domain, key)` for tenant-config rows).
4. Same-tenant composite FKs (`FOREIGN KEY (tenant_id, parent_id) REFERENCES parent (tenant_id,
   id)`) wherever a child table references another tenant-owned table's row — not a bare FK to
   the parent's primary key alone. `device_session`'s existing composite FK to `tenant_user
   (tenant_id, id)` (V5) is the reference pattern to replicate.
5. Append-only design (insert + supersede, never `UPDATE`/`DELETE`) for any new
   ledger-settlement-management, audit-log-management, or payment-management table.
6. `NUMERIC` for every new money column — never floating point — across
   finance-expense-management and ledger-settlement-management additions.
7. New migration files only (`V36__...sql` onward) — never edit V1–V35.
