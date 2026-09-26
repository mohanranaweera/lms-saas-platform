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

> **Note (post-Wave 2):** the table above is the original Wave 0 point-in-time snapshot and is
> **not** recomputed here — several rows' classifications have since changed as Wave 1 and Wave 2
> shipped (e.g. PAR-05-02/07/08 moved to `MATCHES`; PAR-05-03/04/06 and PAR-07-04 moved to
> `PARTIAL` with new detail; PAR-26-01–04 were explicitly descoped, not resolved). Treat
> `klass-parity-matrix.md` itself as authoritative for current per-row status; this summary
> table is left as originally computed, as a dated record of the Wave 0 audit only.

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

1. Video/object storage provider selection (blocks PAR-20-05). **Update (Wave 5):** this
   no longer blocks all of Wave 5 — a real, vendor-agnostic `S3ObjectStorageApi` was
   wired against the already-provisioned dev MinIO, unblocking end-to-end testing
   without deciding the vendor. Only the **production** vendor choice remains blocked;
   see `docs/parity/waves/wave-05-plan.md` §10 item 1 and
   `docs/parity/klass-parity-matrix.md`'s PAR-20-05 row.
2. SMS provider selection (blocks PAR-21-03, Wave 11).
3. WhatsApp Business API provider selection (blocks PAR-22-03, Wave 11).
4. Staff-count vs. plan-limit enforcement's owning module ("Module D," unratified — blocks
   PAR-02-05).
5. Model Paper Library ownership, Teacher vs. Tenant Admin (blocks PAR-11-06).
6. WordPress migration's product scope, if it is ever to be treated as a phase-tagged feature at
   all (blocks PAR-28-01).
7. Whether YouTube/Vimeo-attached content is exempt from secure-video controls (blocked
   PAR-27-03). **Update (Wave 5):** resolved as a documented judgment call rather than
   left blocked — decided yes, exempt (no server-side control over a third-party
   player exists), implemented, and flagged in `docs/parity/waves/wave-05-plan.md` §10
   item 2 as a product-facing claim still worth explicit product-owner sign-off, not a
   silently final decision.

None of these block Wave 1, which is scoped entirely around items that do not depend on any of
the above.

## 6. Recommended Wave 1 scope — STATUS: DONE (see `klass-parity-matrix.md` for row-level detail)

Per master instruction §39, Wave 1 is "Tenant Admin navigation and tenant configuration
framework." Based on this Wave 0 audit, the concrete Wave 1 backlog was:

1. **Fix the two dead-end nav items** (`Profile`, `Settings` with no `href`) — **done.** Removed
   outright rather than wired, since neither ever had a real destination.
2. **Design and build the tenant-configuration framework's data shape and API contract** — **done.**
   Table-shape decision made explicitly (not deferred): one narrow `tenant_config_entry` table,
   one row per `(tenant, domain, key)`, JSONB value validated per-key against a code-side
   `ConfigPropertyRegistry` (type/default/validator/permission/`sensitive`) — not one arbitrary
   blob, and not 17 near-empty tables up front. See `klass-parity-matrix.md` PAR-XC-02. All 17
   domains from master instruction §7 are registered; only `GENERAL`/`BRANDING` have real
   properties this wave.
3. **Restructure `TenantAdminNav`** — **done.** Five populated groups (Communication omitted,
   nothing built for it yet); all existing `canView*` gates preserved, just re-bucketed.
4. **Build `tenant-admin/staff` and `tenant-admin/roles-permissions`** — **done** against the
   existing `StaffController`/`RoleCatalogController`, no new backend endpoints needed.
   `roles-permissions` is intentionally read-only (RBAC grants are code-fixed, not
   tenant-editable); a per-staff-member detail/edit page is still open (PAR-02-02, no backend
   update/delete endpoint exists yet to build it against).
5. **Build the Branding Settings + Preview Panel screens** — **done** for
   primary/secondary color, logo URL, favicon URL, with server-side WCAG AA contrast validation
   (PAR-14-03) and a values-in preview panel. No file-upload pipeline yet (logo/favicon are URL
   references only) and no shared production theming pipeline exists yet for the preview to
   plug into — both flagged as open in `klass-parity-matrix.md`, not silently skipped.
6. Course pricing-model work (Wave 2), video/secure-content work (Wave 5), and Phase-2/3
   communication/finance/settlement work were correctly **not** started in Wave 1.

**Two judgment calls made explicitly during Wave 1's plan step** (flagged for product-owner
review, not silently decided): (a) all 15 Institute Configuration menu domains share the single
existing `BRANDING_SETTINGS` RBAC grant (Tenant Admin write, Read-only Auditor read, nobody
else) rather than inventing 14 new `DomainArea` enum values, since that is the only matrix row
in `docs/requirements/user-roles-and-permissions.md` §2 covering configuration at all; (b)
`GENERAL`'s five properties (institute_name/support_email/support_phone/default_timezone/
default_currency) are a conservative starter set, since no spec document enumerates a fuller
"General settings" field list anywhere in this repo.

## 7. Full wave-by-wave cross-reference

| Wave | Scope (per master instruction §39) | Parity IDs primarily addressed |
|---|---|---|
| 1 | Tenant Admin nav + tenant configuration framework | PAR-XC-01, PAR-XC-02, PAR-02-01/02/03, PAR-14-01–04, PAR-26-04 (toggle only) |
| 2 | Course/Class expansion + billing model foundation — **STATUS: DONE, with caveats (see §8)** | PAR-05-02/03/04/06/07/08, PAR-XC-03, PAR-26-01/02/03, PAR-07-04 (course/teacher filter) |
| 3 | Student and Teacher operational profiles — **STATUS: DONE (see §10)** | PAR-03-01/02/03/04/05/06, PAR-04-03/04 |
| 4 | ClassSession and Zoom/meeting integration — **STATUS: DONE, with one deferral (see `docs/parity/waves/wave-04-plan.md` §11)** | PAR-19-01–05 (done), PAR-10-01 (verified unchanged), PAR-10-03 (event contract done, consumer deferred to Wave 8) |
| 5 | Materials, video and playback policies — **STATUS: DONE, with two explicit judgment calls (see `docs/parity/waves/wave-05-plan.md` §10)** | PAR-06-02 (fix — corrects a stale Wave 0 `MATCHES`), PAR-06-03/05 (done), PAR-06-04 (verified, not built), PAR-06-05/PAR-27-01 (done), PAR-17-01 (done), PAR-20-01–04 (done), PAR-20-05 (dev/test done, production vendor still BLOCKED), PAR-27-03 (resolved as a documented judgment call, product sign-off recommended) |
| 6 | Billing periods and Student Payment parity | PAR-09-04/05, PAR-18-02/03/04, PAR-XC-04 |
| 7 | Finance, expenses and settlement foundation — **STATUS: DONE, with explicit judgment calls and deferrals (see `docs/parity/waves/wave-07-plan.md` §10/§11)** | PAR-23-02/03/05, PAR-24-03/04 (done); PAR-23-01/04, PAR-24-02 (partial — accounts, export, platform commission settlement deferred) |
| 8 | Attendance parity using ClassSession — **STATUS: DONE, with explicit judgment calls and deferrals (see `docs/parity/waves/wave-08-plan.md` §10/§11)** | PAR-10-01/02 (done); PAR-10-03, PAR-10-04 (deferred — blocked on Zoom participant data / unspecified alert rules) |
| 9 | Exam parity expansion | PAR-11-05 |
| 10 | Device/access policy management | PAR-16-01–05 |
| 11 | Notifications, communications and integrations | PAR-12-03/04, PAR-21-01–03, PAR-22-01–03 |
| 12 | Dashboards and reporting | PAR-05-06 (Analytics tab), PAR-XC-05 |
| 13 | Public storefront, branding and domain parity | PAR-15-01/02/03 |
| 14 | Cross-role product parity regression | (regression pass over all MATCHES + newly shipped items) |
| 15 | Security, performance, staging and final readiness review | PAR-01-04, PAR-07-02, PAR-08-04, PAR-13-02/04, PAR-03-02, PAR-04-04, PAR-XC-05 (all remaining NEEDS_VERIFICATION items — PAR-06-04 removed from this list, verified in Wave 5) |
| Unscheduled | Confirmed gap, no wave slot in master instruction §39; recommend alongside/after Wave 1 | PAR-01-06 (Platform Admin tenant suspend/cancel — confirmed absent by code inspection, not merely unverified) |
| BLOCKED | Awaiting business/procurement decisions | PAR-02-05, PAR-11-06, PAR-20-05 (dev/test unblocked in Wave 5; **production vendor decision only** remains BLOCKED), PAR-21-03, PAR-22-03, PAR-28-01 |

## 8. Recommended Wave 2 scope — STATUS: DONE, with caveats (see `klass-parity-matrix.md` for row-level detail)

Per master instruction §39 and §7's cross-reference table, Wave 2 is "Course/Class expansion +
billing model foundation." It shipped as the Course/Class model + billing foundation module
(backend: 1644 tests, `mvnw verify` BUILD SUCCESS; frontend: lint/typecheck/build clean,
Playwright green; reviewed by architecture, security/tenant-isolation, and payment-ledger
reviewers, with a Phase E review finding — ADR-015 — fixed before sign-off). The concrete Wave 2
backlog, against the items `implementation-roadmap.md` §7 assigned to it:

1. **PAR-05-02 (staff course-creation entry point)** — **done.** `tenant-admin/courses/new`,
   posting to the existing `POST /api/v1/courses` contract.
2. **PAR-05-03 (draft → under-review → published lifecycle)** — **partially done.**
   Draft/published/archived shipped (`CourseStatus` unchanged, plus new `archived_at`, V37); the
   tenant-configurable "requires approval" under-review state remains blocked on `COURSE` domain
   config properties that Wave 1's framework registered but never populated — still open, no wave
   assigned.
3. **PAR-05-04 (pricing models + billing periods)** — **done as a foundation**, with two
   explicitly deferred downstream flows: `CUSTOM` pricing has no reachable checkout completion
   (no staff-on-behalf-of-student order endpoint), and `MONTHLY`/`SESSION` billing periods are
   manual/on-demand only (no recurring auto-charge scheduler) — both are documented,
   intentional limitations of a "foundation" wave, not bugs.
4. **PAR-05-06 (course detail workspace tabs)** — **partially done.** Fees & Billing, Settings,
   and Access tabs are real and functional. Schedule/Sessions/Recordings/Analytics are deliberate
   structural placeholders, blocked on Live Sessions (Wave 4) and `reporting-analytics`
   (Wave 12). Students/Teachers/Materials/Attendance/Exams were never in this wave's scope as
   course-scoped tabs and remain a pre-existing boundary, not a new gap.
5. **PAR-05-07 (course clone)** — **done.** Copies structure + pricing config only, now
   audit-logged (`CourseClonedEvent`, a Phase E review fix).
6. **PAR-05-08 (course archive)** — **done.** Pure listing-visibility flag, no data touched.
7. **PAR-XC-03 (Course/Class aggregate confirmation)** — **confirmed during planning**: Course
   remains the single aggregate root; billing configuration/billing-period history were added as
   new child tables (V38/V39), not a separate Class domain — matches the master instruction §8
   default this row called for confirming explicitly.
8. **PAR-26-01/02/03 (Course Reviews: submission, moderation queue, storefront display)** —
   **explicitly descoped from this wave**, with the user's sign-off, in favor of the Course/Class
   + billing foundation scope. Not started, not a regression — see the matrix rows for detail.
9. **PAR-07-04 (Payment Dashboard course/teacher filter)** — **explicitly descoped from this
   wave** for the same reason as the reviews items above; confirmed still absent from
   `tenant-admin/payments/dashboard` by direct inspection, not merely unverified as the Wave 0
   pass had recorded it.

**One decision surfaced and resolved mid-wave, via a formal Phase E review and ADR (not silently
decided by implementation):** the initial FREE-course `$0` checkout implementation gated
auto-activation on the *resolved amount* being zero rather than on the course's `pricing_model`
genuinely being `FREE`, and wrote no ledger entry for a FREE confirmation at all. Both gaps were
caught by the architecture/security/payment-ledger review pass, escalated to the product owner
as an explicit decision request, and resolved via `docs/adr/ADR-015-free-course-zero-amount-payment-and-ledger.md`
(narrower auto-activation gate + a new, additive `V42` migration correcting the ledger-entry
CHECK constraint) before the wave was considered done.

See `docs/api/course-billing.md` for the full new API contract and
`docs/plans/MVP-022 Course Billing and Lifecycle.md` for the reconstructed module plan (produced
retroactively — this module's own plan was never persisted as a repo artifact before
implementation began, a gap a Phase E review flagged, matching the same process gap
`docs/api/course-management.md`'s own "Process gap" note records for MVP-008).

## 9. What Wave 0 explicitly did not do

Per the master instruction, this pass performed no production code, migration, or test changes.
It also did not: re-derive every raw-SQL constraint file-by-file (relied on each spec's own
verified acceptance-criteria assertions instead, see `database-impact-analysis.md` §2); run the
application or execute Playwright/JUnit suites to empirically confirm behavior (relied on static
code/route/migration inspection); or resolve any of the unresolved business decisions in §5 —
all of those remain for the human reviewer and product owner, per master instruction's closing
instruction to STOP and wait for review before Wave 1 begins.

## 10. Recommended Wave 3 scope — STATUS: DONE (see `klass-parity-matrix.md` for row-level detail)

Per master instruction §39 and §7's cross-reference table above, Wave 3 is "Student and Teacher
operational profiles." It shipped as the Student/Teacher operational-profile module (backend and
frontend implemented, reviewed by architecture, security/tenant-isolation, and payment-ledger
reviewers across two fix passes; two change-controlled decisions — staff-granted enrollment and
staff-initiated revocation — were surfaced to and approved by the product owner before the
corresponding service/entity code was written, formally recorded in
`docs/adr/ADR-016-staff-granted-enrollment-and-revocation.md`). The concrete Wave 3 backlog,
against the items `implementation-roadmap.md` §7 assigned to it:

1. **PAR-03-01 (student self-registration)** — **done, and corrects a stale Wave 0 `MATCHES`.**
   The Wave 0 pass recorded `MATCHES` against a disabled placeholder shell with no backend at
   all; this wave replaced it with a real, tenant-configurable public registration workflow
   (`StudentRegistrationController`, `PublicStudentRegistrationPolicyController`, `ConfigDomain
   .STUDENT`'s eight registration-policy properties, email-only OTP).
2. **PAR-03-02 (manual single-student creation)** — **reverified, no code change needed.** Already
   correct before this wave; `mustChangePassword` is still hardcoded server-side.
3. **PAR-03-03 (bulk CSV import)** — **done.** Continue-on-error, per-row result, staff-gated.
4. **PAR-03-04 (Student Detail composition)** — **done for the available parts.** Profile/
   Enrollments/Payments/Attendance/Exams/Activity tabs are real; Devices and Notification-history
   tabs remain explicitly deferred (Wave 10/11 — the owning domains don't exist yet).
5. **PAR-03-05 (Student actions)** — **done for the available parts.** Edit/activate/deactivate/
   enroll/revoke/reset-password are all real, audit-logged actions; device reset (Wave 10) and
   generic access-extension beyond the existing reactivation-request flow (Wave 6) remain
   explicitly deferred.
6. **PAR-03-06 (Teacher course roster)** — **done.** A real, backend-filtered
   `GET /api/v1/courses/{courseId}/roster`, plus a Teacher-facing route and a mirrored Tenant
   Admin roster tab.
7. **PAR-04-03 (Teacher Detail composition)** — **done for the available parts.** Profile/
   Assigned Courses/Roster/Attendance/Exams/Activity tabs are real; Sessions (Wave 4) and
   Financial summary (Wave 7) tabs remain explicitly deferred — no nav item/tab exists for either,
   per master instruction §34's "don't add a tab for a workflow that isn't implemented."
8. **PAR-04-04 (Teacher SUSPENDED lifecycle)** — **done.** A genuine 4th `ApprovalStatus` value,
   `suspend`/`reactivate` endpoints gated identically to approve/reject (carrying forward existing
   precedent rather than resolving `rbac-impact-analysis.md` §4's separate open question about a
   distinct `A`-level Teacher-approval permission — an explicit judgment call, not silently
   decided).

**Two decisions surfaced and resolved mid-wave, via a formal product-owner approval and ADR (not
silently decided by implementation), both change-controlled under `.claude/rules/payments.md` §7**
because `Enrollment` rows are structurally locked to a small, enumerable set of approved write
call sites: (1) staff "enroll student in course" — a new, explicit 5th `EnrollmentActivationApi`
call site (`fromApprovedManualEvidence`) that creates a real `Order`+`Payment(CONFIRMED,
gatewayReference="STAFF_GRANTED-"+paymentId)`+ledger entry, never a bypass of the payment/ledger
trail; (2) staff "revoke enrollment" — a new, narrow `EnrollmentActivationApi#revoke` call site
reusing the existing `Enrollment#supersede()` mutation, no new `EnrollmentStatus` value, no
ledger/payment write. Both are documented in full in
`docs/adr/ADR-016-staff-granted-enrollment-and-revocation.md`, including the alternatives
considered and rejected.

**A second, post-ship fix pass (security/payment-ledger/architecture review) found and closed three
further gaps before sign-off, without changing either ADR-016 decision's scope:** (a) the enroll
endpoint originally lived in `user-management` and called outward into `payment-management`,
closing a `payment-management → enrollment-management → user-management → payment-management`
cycle — moved to `payment-management`'s own `ManualEnrollmentController`/`StudentEnrollmentService`,
which resolves the path's `student_profile` id via `user-management`'s `StudentLookupApi` (an
already-approved `api`-only dependency direction) instead of the reverse; (b) `enroll`/`revoke`
originally wrote only a payment-/enrollment-targeted audit row, invisible on the student's own
Activity tab — both now additionally write a second audit row targeting the student's own
`student_profile` id, in the same transaction; (c) two near-simultaneous `revoke` calls for the
same enrollment could race past the `supersededAt == null` check — closed with an optimistic-lock
`@Version` column on `enrollment` (V48), surfaced as a clean `409`, never a `500`.

See `docs/api/user-management.md`, `docs/api/payment-management.md`,
`docs/api/enrollment-management.md`, `docs/api/ledger-settlement-management.md`,
`docs/api/attendance-management.md`, `docs/api/exam-management.md`,
`docs/api/tenant-configuration-management.md`, and `docs/api/audit-log-management.md` for the full
new/changed API contracts, and `docs/parity/waves/wave-03-plan.md` for the reconstructed module
plan (written before implementation began, unlike several earlier waves' retroactive contract
docs).
