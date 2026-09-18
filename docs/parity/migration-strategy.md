# Migration Strategy (Wave 0)

Status: analysis only. No migration was created or modified to produce this document.

## 1. Governing constraints (restated, not new)

- Additive only. Never edit `V1`–`V35`. Next migration is `V36`.
- Every new tenant-owned table: `tenant_id NOT NULL`, tenant-leading index, tenant-scoped unique
  constraints, same-tenant composite FKs.
- Append-only for anything touching `ledger-settlement-management`, `audit-log-management`, or
  payment terminal states.
- Prefer extending an existing table/domain over creating a parallel one — checked explicitly
  per item below.

## 2. Low-risk additive migrations (safe to schedule as soon as their wave starts)

These introduce genuinely new tables for domains that have no current tables and no relationship
to reshape — pure additions:

| New table(s) | Domain | Wave |
|---|---|---|
| Tenant configuration table(s) (one per config domain, or one typed table with a `config_domain` discriminator — see §4) | cross-cutting (PAR-XC-02) | 1 |
| Tenant branding/theme table | White Labelling (14) | 1 |
| `expense_category`, `expense`, account tables | Finance & Expenses (23) | 7 |
| `settlement_run`, settlement line/adjustment tables | Settlements (24) | 7 |
| `course_review` | Course Reviews (26) | 2 |
| Notification template / delivery-log / provider-config tables | SMS (21), WhatsApp (22) | 11 |
| Tenant custom-domain + verification-status table | Custom Domains (15) | 13 |
| `video_asset`, `video_playback_policy`, `video_watch_session`, `video_watch_progress` | Secure Video (20) | 5 |
| `class_session`, meeting-provider-reference table | Zoom/Live Classes (19) | 4 (see §3 for the attendance interaction risk) |

None of these require touching an existing table's meaning or FK targets, so they carry
standard additive-migration risk only (index/lock considerations at write time, not data-model
ambiguity).

## 3. Higher-risk migrations requiring explicit design before Wave 1/4/2 starts

### ClassSession introduction (affects Wave 4, touches an already-shipped table)

`attendance_record.session_id` is currently a composite FK directly into `course_lesson
(tenant_id, id)`, by an explicit decision made during MVP-016 planning (recorded in the
attendance spec itself). Introducing a real `class_session` table (master instruction §12) means
one of two paths:

- **Path A (recommended)**: `class_session` becomes a new, independent table for Zoom/live-class
  scheduling only; attendance continues referencing `course_lesson` unchanged for MVP-shipped
  manual attendance, and a *new*, separate attendance-marking path is added for
  `class_session`-scoped attendance (Phase 2, Zoom-synced) without touching the existing FK.
  This avoids any migration of existing `attendance_record` rows and defers the "recurring
  session" fix (an explicit accepted MVP limitation, not a blocker) to a later, dedicated
  decision.
- **Path B (higher risk, not recommended without explicit sign-off)**: repoint
  `attendance_record.session_id` to `class_session` and backfill one `class_session` row per
  existing distinct `(course_lesson)` combination. This is a genuine data migration
  (backfill + FK repoint), not a pure additive change, and risks breaking the recurring-session
  semantics further rather than fixing them, since a naive backfill would create exactly one
  `class_session` per lesson — the same ambiguity as today, just moved to a different table.

**Recommendation**: adopt Path A for Wave 4. Do not attempt to retroactively fix the
recurring-session limitation as a side effect of introducing `ClassSession` — treat that as its
own, separately-scoped decision if it proves necessary later (as the attendance spec itself
already anticipates).

### Pricing model expansion on `course` (affects Wave 2)

Master instruction §8 requires `FREE / ONE_TIME / MONTHLY / SESSION / CUSTOM` pricing models and
billing periods. Today's `course` table almost certainly has a single `price` column (mutable,
but change-audited per PAR-05-05). Recommended migration shape:

1. Add `pricing_model` (enum, `NOT NULL DEFAULT 'ONE_TIME'`) and a nullable `billing_period`
   concept as new, additive columns — every existing row backfills as `ONE_TIME` with its
   current `price` value, which is semantically correct (no data loss, no reinterpretation of
   existing rows).
2. Introduce a separate `course_billing_plan`-shaped table only if `MONTHLY`/`SESSION` pricing
   needs more than one column (e.g. multiple session-based price points) — do not force this
   into `course` itself if it turns out to be a one-to-many relationship.
3. **Do not** repurpose the existing `price` column's meaning for `MONTHLY`/`SESSION` courses in
   a way that silently changes what "the price" means for checkout code that hasn't been updated
   yet — this is exactly the "mutable `course.price` field" pattern master instruction §8
   explicitly warns against relying on for payment integrity. Checkout (`OrderController`) must
   be updated in the same wave as the schema change, not left reading a stale/ambiguous field.
4. Price-change audit logging (already shipped, PAR-05-05) must be extended to cover whichever
   new column(s) actually drive the checkout amount — the audit's current scope (presumably just
   `price`) would otherwise silently stop capturing the value that actually matters once
   multiple pricing models exist.

### Device authentication extension (affects Wave 10)

Extend `device_session` additively (new nullable columns for device-slot identity and limit
context) rather than creating a parallel `device` table — the table's own migration comment
already earmarks `reset_at` for this purpose, so the intended extension point already exists.

## 4. Tenant configuration table shape — a decision to make explicitly in Wave 1's plan, not here

Two reasonable shapes exist and Wave 0 deliberately does not pick one, since master instruction
§7 explicitly requires "typed configuration domains," not "one unvalidated arbitrary JSON blob":

- **One table per config domain** (`tenant_branding_config`, `tenant_payment_config`, etc.) —
  most type-safe, most migrations, clearest per-domain validation/ownership.
- **One `tenant_config` table with `(tenant_id, config_domain, key)` and typed value columns**
  (or narrow JSON validated against a domain-specific schema server-side) — fewer tables, but
  requires disciplined server-side validation per domain to avoid becoming the "arbitrary JSON
  blob" the master instruction explicitly forbids.

**Recommendation carried into `implementation-roadmap.md`**: Wave 1's plan step (per master
instruction §40 Step 2) must make this decision explicitly, with a one-paragraph rationale,
before any config migration is written — this is exactly the kind of architectural uncertainty
that master instruction §40 says should STOP the wave at the plan step if unresolved.

## 5. Settlement idempotency constraint (affects Wave 7)

Per the settlement spec's own acceptance criteria, the `(tenant_id, settlement_period, run
marker)` uniqueness must be a **DB constraint**, not an application-level "already settled?"
check. This should be designed as part of the initial `settlement_run` migration, not added
later as a follow-up fix — building it without the constraint first and adding it after an
idempotency bug surfaces would itself be a violation of `.claude/rules/backend.md`'s
"schema-enforced invariants over service-layer discipline" rule for this exact domain.

## 6. Migration sequencing recommendation (maps to `implementation-roadmap.md` waves)

1. Wave 1: tenant-configuration table(s) + branding table — foundational, unblocks the most
   downstream items.
2. Wave 2: `course` pricing-model columns (co-migrated with checkout code) + `course_review`.
3. Wave 4: `class_session` (Path A, additive only) + meeting-provider-reference table.
4. Wave 5: video-access-management's four new tables.
5. Wave 6: no new domain tables expected — this wave centralizes expiry logic in code
   (`AccessPolicyService`), reading existing tables rather than adding new ones, aside from
   whatever bulk-extension/override audit trail needs a small additive table.
6. Wave 7: `expense_category`/`expense`/account tables + `settlement_run` (with the mandatory
   uniqueness constraint from §5) together, since finance-expense-management explicitly consumes
   settlement's API.
7. Wave 10: `device_session` additive extension.
8. Wave 11: notification template/delivery-log/provider-config tables.
9. Wave 13: custom-domain table.

No wave in this sequence requires editing a pre-existing migration file.
