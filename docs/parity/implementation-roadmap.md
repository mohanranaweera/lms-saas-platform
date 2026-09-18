# Implementation Roadmap (Wave 0)

Status: analysis only. This document plans future waves; it does not itself implement anything.

## 1. Wave 0 findings summary

Total parity items recorded in `klass-parity-matrix.md`: **131**, across the 28 Klass spec
domains plus 5 cross-cutting items. Counts by classification (computed by counting each
classification token in the matrix file, not estimated by hand):

| Classification | Count |
|---|---|
| MATCHES | 40 |
| MISSING_WORKFLOW | 33 |
| MISSING_SCREEN | 17 |
| NEEDS_VERIFICATION | 16 |
| MISSING_CONFIGURATION | 14 |
| PARTIAL | 8 |
| MISSING_FIELD_ACTION | 1 |
| ARCHITECTURAL_CONFLICT | 1 |
| WRONG_UX_IA | 1 |
| WRONG_BEHAVIOR | 0 |
| ROLE_MISMATCH | 0 |
| OBSOLETE_CURRENT_FEATURE | 0 |
| PLATFORM_ONLY_FEATURE | 0 |
| **Total** | **131** |

**Reading this distribution**: zero `WRONG_BEHAVIOR`, `ROLE_MISMATCH`, or
`OBSOLETE_CURRENT_FEATURE` findings means the 40 already-shipped MATCHES items are genuinely
correct, not superficially similar — the gap in this codebase is almost entirely **absence**
(MISSING_*, 64 items = 49% of the total) and **unresolved business/verification questions**
(NEEDS_VERIFICATION, 17 items = 13%), not rework of something built wrong. This is a materially
easier remediation posture than a matrix dominated by WRONG_BEHAVIOR/ROLE_MISMATCH would be.

## 2. Architectural conflicts identified

Only one item earned `ARCHITECTURAL_CONFLICT` in this pass:

- **PAR-19-02 — `ClassSession` introduction vs. attendance's existing `course_lesson.id`
  session-equivalent.** Not a conflict with an ADR or an already-wrong design — the current
  `course_lesson`-as-session choice was an explicit, accepted MVP-016 decision. The conflict is
  purely forward-looking: introducing a real `ClassSession` domain (required for Zoom/live
  classes) creates two candidate "what is a session" answers unless sequenced carefully. Fully
  addressed with a recommended resolution (Path A, additive-only) in `migration-strategy.md`
  §3 — this is a **manageable, already-scoped** conflict, not a blocking one.

One additional item is flagged as a **conflict risk to prevent, not one that exists yet**:
PAR-15-02 (custom-domain resolution) — if implemented as a parallel tenant-resolution mechanism
instead of extending the existing ADR-002/006 edge-resolution point, it would become an
architectural conflict. The spec itself recommends a lightweight ADR before implementation;
this roadmap defers Wave 13 until that ADR exists.

## 3. Migration risks identified

Full detail in `migration-strategy.md`. Two items carry genuine schema/data risk beyond routine
additive migrations:

1. **`ClassSession`/attendance interaction** (Wave 4) — resolved via Path A (keep two
   independent tables, no FK repoint, no backfill) rather than Path B (repoint + backfill),
   which would risk perpetuating or worsening the existing recurring-session ambiguity.
2. **Course pricing-model expansion** (Wave 2) — not a risky migration in isolation (purely
   additive columns with a safe backfill default), but risky if the schema change and the
   checkout-code change are not shipped in the same wave, since `OrderController` reading a
   stale/ambiguous price field would be a payment-integrity regression, not just a UX gap.

No migration in the recommended sequence requires editing an already-applied migration file or
performing a destructive backfill.

## 4. Security risks identified

None of the 131 parity items independently surfaced a *new* security vulnerability in
already-shipped code (the 40 MATCHES items were cross-checked against their own spec's
acceptance criteria, which already assert tenant-isolation/authorization test coverage). The
security-relevant findings from this pass are all **absence-of-control** risks tied to missing
features, not defects in existing controls:

- **PAR-20-01 (Secure Video baseline)** is the most consequential: the spec classifies
  signed/short-lived/single-use video playback tokens as **MVP baseline**, not Phase 2, yet
  `video-access-management` has zero implementation. If any course video content is currently
  being served through `content-management`'s general material-upload path without the intended
  video-specific protection, that would be a live gap between stated MVP security posture and
  actual behavior — **this should be verified as the first action of Wave 0 follow-up, before
  Wave 1 begins**, since it is a security question, not just a feature-completeness one: confirm
  whether any video content is currently servable at all, and if so, through what access-control
  path.
- **PAR-16 (Device Authentication)** and **PAR-17 (Session/View Limits)** absence means
  account-sharing and concurrent-session abuse are currently unmitigated by design (ADR-007/008
  accepted but not implemented) — an accepted, documented gap per the spec's own Phase 2
  classification, not a newly discovered one, but worth restating as a security risk rather than
  only a feature gap.
- **PAR-01-04 / PAR-13-04 / PAR-04-04 (audit-logging NEEDS_VERIFICATION items)** — if any of
  these turn out to be missing audit events in practice (not just undocumented), that is a
  compliance/security gap, not merely a documentation one. Recommend verifying these three
  specifically before Wave 1 sign-off.

## 5. Unresolved business decisions blocking specific items (marked BLOCKED in the matrix)

These cannot be resolved by engineering judgment and should be escalated to the product/business
owner before their wave starts, per root `CLAUDE.md`'s instruction not to silently decide
unresolved business requirements:

1. Video/object storage provider selection (blocks PAR-20-05, and therefore all of Wave 5).
2. SMS provider selection (blocks PAR-21-03, Wave 11).
3. WhatsApp Business API provider selection (blocks PAR-22-03, Wave 11).
4. Staff-count vs. plan-limit enforcement's owning module ("Module D," unratified — blocks
   PAR-02-05).
5. Model Paper Library ownership, Teacher vs. Tenant Admin (blocks PAR-11-06).
6. WordPress migration's product scope, if it is ever to be treated as a phase-tagged feature at
   all (blocks PAR-28-01).
7. Whether YouTube/Vimeo-attached content is exempt from secure-video controls (blocks PAR-27-03,
   and indirectly informs how urgently PAR-20 needs to ship first).

None of these block Wave 1, which is scoped entirely around items that do not depend on any of
the above.

## 6. Recommended Wave 1 scope

Per master instruction §39, Wave 1 is "Tenant Admin navigation and tenant configuration
framework." Based on this Wave 0 audit, the concrete Wave 1 backlog should be:

1. **Fix the two dead-end nav items** (`Profile`, `Settings` with no `href`) — smallest possible
   first change, directly resolves a named anti-pattern (master instruction §34).
2. **Design and build the tenant-configuration framework's data shape and API contract**
   (decision required first: one-table-per-domain vs. typed `tenant_config` — see
   `migration-strategy.md` §4). This is the single highest-leverage item: it unblocks Branding
   (PAR-14-01–04), publish-approval policy (PAR-05-03), review toggle (PAR-26-04), and is a
   prerequisite for device-limit overrides (PAR-16-02) and expiry precedence (PAR-18-03) even
   though those ship in later waves.
3. **Restructure `TenantAdminNav`** into the six target groups (Dashboard / Academic / Finance /
   Communication / Administration / Institute Configuration), keeping all existing
   `canView*`-style backend-permission-driven gating logic unchanged.
4. **Build `tenant-admin/staff` and `tenant-admin/roles-permissions`** against the
   already-existing `StaffController`/`RoleCatalogController` backend — no new backend domain
   required, purely a frontend build-out, and the fastest concrete parity win identified in this
   audit (PAR-02-01/02).
5. **Build the Branding Settings + Preview Panel screens** (PAR-14-01/02) as the first real
   consumer of the new tenant-configuration framework, proving the framework's contract works
   end-to-end before other config domains are built on top of it in later waves.
6. Explicitly **do not** start Course pricing-model work (Wave 2), video/secure-content work
   (Wave 5), or any Phase-2/3 communication/finance/settlement work in Wave 1 — those are
   correctly sequenced into their own waves per master instruction §39 and this roadmap's §6 of
   `migration-strategy.md`.

## 7. Full wave-by-wave cross-reference

| Wave | Scope (per master instruction §39) | Parity IDs primarily addressed |
|---|---|---|
| 1 | Tenant Admin nav + tenant configuration framework | PAR-XC-01, PAR-XC-02, PAR-02-01/02/03, PAR-14-01–04, PAR-26-04 (toggle only) |
| 2 | Course/Class expansion + billing model foundation | PAR-05-02/03/04/06/07/08, PAR-XC-03, PAR-26-01/02/03, PAR-07-04 (course/teacher filter) |
| 3 | Student and Teacher operational profiles | PAR-03-02/03/04/05/06, PAR-04-03/04 |
| 4 | ClassSession and Zoom/meeting integration | PAR-19-01–05, PAR-10-01 (interaction only), PAR-10-03 |
| 5 | Materials, video and playback policies | PAR-06-03/04/05, PAR-17-01–04, PAR-20-01–05, PAR-27-01/02 |
| 6 | Billing periods and Student Payment parity | PAR-09-04/05, PAR-18-02/03/04, PAR-XC-04 |
| 7 | Finance, expenses and settlement foundation | PAR-23-01–05, PAR-24-02/03/04 |
| 8 | Attendance parity using ClassSession | PAR-10-04 |
| 9 | Exam parity expansion | PAR-11-05 |
| 10 | Device/access policy management | PAR-16-01–05 |
| 11 | Notifications, communications and integrations | PAR-12-03/04, PAR-21-01–03, PAR-22-01–03 |
| 12 | Dashboards and reporting | PAR-05-06 (Analytics tab), PAR-XC-05 |
| 13 | Public storefront, branding and domain parity | PAR-15-01/02/03 |
| 14 | Cross-role product parity regression | (regression pass over all MATCHES + newly shipped items) |
| 15 | Security, performance, staging and final readiness review | PAR-01-04, PAR-07-02, PAR-08-04, PAR-13-02/04, PAR-03-02, PAR-04-04, PAR-06-04, PAR-XC-05 (all remaining NEEDS_VERIFICATION items) |
| Unscheduled | Confirmed gap, no wave slot in master instruction §39; recommend alongside/after Wave 1 | PAR-01-06 (Platform Admin tenant suspend/cancel — confirmed absent by code inspection, not merely unverified) |
| BLOCKED | Awaiting business/procurement decisions | PAR-02-05, PAR-11-06, PAR-20-05, PAR-21-03, PAR-22-03, PAR-27-03, PAR-28-01 |

## 8. What Wave 0 explicitly did not do

Per the master instruction, this pass performed no production code, migration, or test changes.
It also did not: re-derive every raw-SQL constraint file-by-file (relied on each spec's own
verified acceptance-criteria assertions instead, see `database-impact-analysis.md` §2); run the
application or execute Playwright/JUnit suites to empirically confirm behavior (relied on static
code/route/migration inspection); or resolve any of the unresolved business decisions in §5 —
all of those remain for the human reviewer and product owner, per master instruction's closing
instruction to STOP and wait for review before Wave 1 begins.
