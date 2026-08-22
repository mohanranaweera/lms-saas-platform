# Open Decisions

This is the consolidated tracking log for every unresolved business/product decision surfaced
while building the feature specifications under `docs/requirements/specifications/` (produced by
a five-agent review of `docs/requirements/`, `docs/architecture/`, and `docs/ui-ux/` in
2026-08). Nothing here was invented — each item is either an explicit open question already
flagged in the source documents, or a gap identified by cross-checking documents against each
other during that review. Items are grouped by category; each cites the affected feature(s) and
source.

**How to use this log**: when an item below is resolved, update the relevant spec file(s) under
`docs/requirements/specifications/` and either remove the entry here or mark it resolved with a
date and a pointer to the decision record (an ADR, if the item touches a change-controlled area).

---

## 1. Registration / access model

- **Tenant self-registration**: whether the initial institute-registration entry point is
  public/self-serve or invite-only (Platform Admin outreach) is unresolved.
  Affects: [01-tenant-onboarding.md](specifications/01-tenant-onboarding.md).
  Source: `docs/ui-ux/user-journeys.md` line 205-207; `docs/requirements/user-roles-and-permissions.md` Open Q4.
- **Student self-registration**: same public-vs-invite-only question, unresolved.
  Affects: [03-student-management.md](specifications/03-student-management.md).
  Source: `docs/requirements/user-roles-and-permissions.md` §1, Open Q4; `docs/ui-ux/user-journeys.md` Journey 4.
- **Teacher registration mechanism**: self-register-then-approve vs. Tenant-Admin-invited-only is not specified.
  Affects: [04-teacher-management.md](specifications/04-teacher-management.md).
- No rejection-reason/notification workflow, duplicate-registration handling, or re-application flow is specified for tenant onboarding.
- Uniqueness scope for tenant subdomain/custom domain is implied but never explicitly stated as a constraint.

## 2. Permission-matrix gaps (no row, or an ambiguous row, in `docs/requirements/user-roles-and-permissions.md` §2)

- **Notifications/Communications**: no dedicated permission-matrix row exists for templates, bulk/segment messaging, or delivery logs. Which staff sub-role(s) may perform these actions is unspecified.
  Affects: [12-notifications.md](specifications/12-notifications.md), [21-sms.md](specifications/21-sms.md), [22-whatsapp.md](specifications/22-whatsapp.md).
- **Zoom scheduling**: no explicit row covers "who may schedule a Zoom session / manage recordings."
  Affects: [19-zoom-live-classes.md](specifications/19-zoom-live-classes.md).
- **Settlement-run triggering**: exact split of who can trigger a run (Platform Admin only vs. tenant-level Finance Staff/Institute Owner) is not fully specified.
  Affects: [24-settlements.md](specifications/24-settlements.md).
- **Expense approval**: the matrix gives Finance Staff/Institute Owner plain `V/C/E/D` on Finance & Expenses with no distinct `A` (approve) column, unlike Payments/Courses/Exams — a gap if the recommended expense-approval workflow is built.
  Affects: [23-finance-and-expenses.md](specifications/23-finance-and-expenses.md).
- **Teacher approval** — **RESOLVED 2026-08-13.** No explicit `A` (approve) column exists for
  Course Coordinator on "Teachers," unlike Courses/Payments/Exams which do have one. Confirmed:
  Tenant Admin only. Implemented as a service-layer defense-in-depth role check
  (`TeacherService.requireTenantAdmin()`, checking `AuthenticatedPrincipalHolder.get().role()`
  literally equals `"TENANT_ADMIN"`) layered on top of the existing shared `TEACHERS`/
  `CREATE_EDIT` grant, which Course Coordinator also holds for teacher *creation*. The shipped
  `PermissionCheckServiceImpl` matrix itself is unchanged — no new `TEACHERS`/`APPROVE` grant was
  added — mirroring the same defense-in-depth pattern already applied to `StaffService`
  (commit `d265597`). Course Coordinator can create teacher accounts but is rejected `403` on
  approve/reject, verified end-to-end in `TeacherManagementIntegrationTest`
  (`courseCoordinatorCanCreateButCannotApproveOrRejectTeachers`) and unit-tested in
  `TeacherServiceTest`. See `docs/plans/MVP-007 Teacher Management.md` §2/§21 item 1 for the two
  options that were weighed before this was confirmed.
  Affects: [04-teacher-management.md](specifications/04-teacher-management.md).
- **Custom-domain approval**: whether Platform Admin must approve/verify a tenant's custom domain is not addressed.
  Affects: [15-custom-domains.md](specifications/15-custom-domains.md).
- **Staff sub-role "own-area" audit scoping**: the matrix labels staff access to the audit log as "V (own-area actions)" but no document defines the enforcement mechanism for what counts as a sub-role's "own area."
  Affects: [13-audit-logs.md](specifications/13-audit-logs.md).

## 3. Provisional / unratified roles

- **Teacher Assistant's entire permission boundary is PROVISIONAL, unratified.** `docs/requirements/user-roles-and-permissions.md` §3 states explicitly: "No source document defines Teacher Assistant's permission boundary today... do not build a hard permission gate against it without sign-off." This is a reasonable default, not a confirmed decision.
  Affects: [04-teacher-management.md](specifications/04-teacher-management.md), [05-course-management.md](specifications/05-course-management.md), [06-lessons-and-materials.md](specifications/06-lessons-and-materials.md), [10-attendance.md](specifications/10-attendance.md), [11-exams.md](specifications/11-exams.md), [26-course-reviews.md](specifications/26-course-reviews.md).

## 4. Approval-precedence ambiguity

- **Reactivation approver**: whether Finance Staff or Institute Owner (or both, with what precedence) is the correct approver for reactivation requests, and manual payment slip approval generally, when both are eligible.
  Affects: [08-manual-payment-slips.md](specifications/08-manual-payment-slips.md), [09-enrollments.md](specifications/09-enrollments.md), [18-smart-expiry.md](specifications/18-smart-expiry.md).
  Source: `docs/requirements/user-roles-and-permissions.md` Open Q2.
- **Course-approval second approver**: whether Course Coordinator's course-approval authority (`A`) requires a second approver for high-value/published courses.
  Affects: [05-course-management.md](specifications/05-course-management.md).
  Source: `docs/requirements/user-roles-and-permissions.md` Open Q3.

## 5. Audit-logging gaps and inconsistencies

- **Exam-result publication audit gap (documentation inconsistency)**: `functional-requirements.md` FR-EX-2 states result publication is "a confirmable, audit-considered action," but exam-result publication is **not** in `.claude/rules/security.md`'s canonical mandatory-audit-action list (price changes, payment approvals/rejections, device resets, access/expiry extensions, reactivation approvals, material/course content deletions, settlement amount changes, impersonation). This should be settled explicitly — either extend the security-rule list or soften FR-EX-2's language.
  Affects: [11-exams.md](specifications/11-exams.md), [13-audit-logs.md](specifications/13-audit-logs.md).
- **Staff account creation/role changes**: whether these require an audit-log entry is unspecified, despite the high blast-radius of role assignment.
  Affects: [02-staff-management.md](specifications/02-staff-management.md).
- **Student status changes / Student Support edits**: whether these require an audit-log entry is unspecified.
  Affects: [03-student-management.md](specifications/03-student-management.md).
- **Teacher approval**: whether it requires an audit-log entry is unspecified (unlike tenant approval, which explicitly does).
  Affects: [04-teacher-management.md](specifications/04-teacher-management.md).
- **Tenant onboarding approval/status-change**: audit-logging is required per `functional-requirements.md`/`user-journeys.md`, but this action is **not** itself named in `.claude/rules/security.md`'s canonical list — a gap between the two documents' scope, not a missing requirement.
  Affects: [01-tenant-onboarding.md](specifications/01-tenant-onboarding.md).
- **Branding/theme changes**: whether these require an audit-log entry is unresolved.
  Affects: [14-white-labelling.md](specifications/14-white-labelling.md).
- **Custom-domain enable/disable or DNS/TLS config changes**: whether audit-logged is unresolved.
  Affects: [15-custom-domains.md](specifications/15-custom-domains.md).
- **Zoom link-sharing enforcement failures / recording-attach actions**: whether these require a dedicated audit entry is unresolved.
  Affects: [19-zoom-live-classes.md](specifications/19-zoom-live-classes.md).
- **Bulk-messaging actions (SMS/WhatsApp)**: whether these need an audit entry distinct from delivery logs is unresolved.
  Affects: [21-sms.md](specifications/21-sms.md), [22-whatsapp.md](specifications/22-whatsapp.md).
- **Expense record deletion**: the permission matrix grants Finance Staff/Institute Owner literal `D` (hard delete) on Finance & Expenses, which is in tension with `non-functional-requirements.md` §9's "financial history... is never deleted" principle and the append-only pattern used elsewhere in the payment/ledger cluster. Whether this is an intentional exception or an inconsistency to fix is unresolved — **flagging, not resolving unilaterally.**
  Affects: [23-finance-and-expenses.md](specifications/23-finance-and-expenses.md).
- **Course review moderation actions**: whether approve/reject requires an audit entry is unresolved.
  Affects: [26-course-reviews.md](specifications/26-course-reviews.md).
- **Audit-log retention/purge policy**: no policy exists beyond "retained indefinitely by default; a future retention/purge policy is a separate approved process."
  Affects: [13-audit-logs.md](specifications/13-audit-logs.md).
  Source: `docs/requirements/non-functional-requirements.md` §9, Open Q5.

## 6. Domain-ownership gaps (unratified per `docs/requirements/module-catalog.md`)

- **Public Storefront (Module C)**, **Feature Flag & Plan Limit Engine (Module D)**, and **AI
  Assistant (Module F)** are explicitly unowned/unratified cross-cutting items per
  `module-catalog.md`'s "Cross-Cutting / Unowned Items" section. Module D specifically is called
  "needed from day one" in source requirements yet has no owning domain — it gates plan-based
  entitlements referenced throughout this catalog. See
  [portals-overview.md](portals-overview.md) "Cross-cutting / not yet owned by a portal."
  Affects (directly depend on Module D): [02-staff-management.md](specifications/02-staff-management.md) (FR-UM-9 staff-count limit), [14-white-labelling.md](specifications/14-white-labelling.md), [15-custom-domains.md](specifications/15-custom-domains.md) (plan entitlement gating).
- **Course-reviews moderation-workflow ownership**: whether `course-management` or a not-yet-named domain (possibly `support-management`) owns the review submission/moderation *workflow*, versus just the course-level toggle.
  Affects: [26-course-reviews.md](specifications/26-course-reviews.md).
  Source: `module-catalog.md` Open Question #1.
- **`finance-expense-management` vs. `ledger-settlement-management` domain-count question**: whether these should remain two domains, or whether the tutor-payout linkage is thin enough to fold into one. Flagged only as a documentation question in `module-catalog.md`, not a proposal to merge — current default (two domains) stands unless changed via the confirmed-domain-list process.
  Affects: [23-finance-and-expenses.md](specifications/23-finance-and-expenses.md), [24-settlements.md](specifications/24-settlements.md).
  Source: `module-catalog.md` Open Question #2.
- **Attendance-based access restrictions ↔ enrollment expiry boundary**: "attendance-based access restrictions" (Phase 2) is owned entirely by `attendance-management` per `module-catalog.md`, but restricting course/material *access* is architecturally `enrollment-management`'s concern. No document resolves which domain enforces the restriction. This is a new gap identified during this review, analogous to the Module C/D/F gaps.
  Affects: [10-attendance.md](specifications/10-attendance.md), [09-enrollments.md](specifications/09-enrollments.md).
- **Model Paper Library ownership**: between Teacher and Tenant Admin, unresolved.
  Affects: [11-exams.md](specifications/11-exams.md).
  Source: FR-EX-5; `docs/ui-ux/screen-map.md` line 150-151.

## 7. Payment/ledger business decisions (explicitly deferred, not invented — per `docs/architecture/payment-ledger.md` §10)

- Which specific payment gateway will be integrated, and what payment methods it supports.
- Refund window/eligibility policy.
- Exact commission percentage(s), and whether commission varies by tenant plan/tier.
- Exact gateway-fee handling (pass-through deduction vs. platform-absorbed; variance by payment method).
- Settlement run cadence (weekly/monthly/on-demand) and exact "settlement period" boundary definition.

Affects: [07-orders-and-payments.md](specifications/07-orders-and-payments.md),
[24-settlements.md](specifications/24-settlements.md).

## 8. Enrollment / expiry business decisions (per `docs/architecture/enrollment-access.md` §9)

- Exact grace period length(s) — no default specified.
- Exact expiry-rules-engine precedence order — **explicitly not assumed** to mirror the device-limit precedence pattern (student > course > tenant > plan) without a separate confirming decision.
- Whether reactivation always requires a full new payment or a prorated/partial payment is ever allowed.
- Whether bulk expiry extension requires a second-approver step beyond the acting admin's normal permission.
- Exact reminder timing before expiry.

Affects: [18-smart-expiry.md](specifications/18-smart-expiry.md).

## 9. Third-party provider selection (none named anywhere — do not invent a vendor)

- SMS provider — not selected.
- WhatsApp Business API provider — not selected.
- External video/object storage provider (for secure video) — not selected; evaluation criteria are recorded in `docs/adr/ADR-008-video-content-protection-mechanism.md` §2.
- OCR provider/mechanism for Phase 3 payment-slip intelligence — not named anywhere.

Affects: [21-sms.md](specifications/21-sms.md), [22-whatsapp.md](specifications/22-whatsapp.md),
[20-secure-video.md](specifications/20-secure-video.md),
[25-duplicate-payment-slip-detection.md](specifications/25-duplicate-payment-slip-detection.md).

## 10. Technical mechanism gaps not covered by an ADR

- **Custom-domain DNS/TLS provisioning**: no provider or automation approach is decided.
  Affects: [15-custom-domains.md](specifications/15-custom-domains.md).
  Source: `docs/architecture/deployment-architecture.md` §6.
- **Zoom link-sharing prevention mechanism**: the requirement exists (FR-LCM-2) but the concrete enforcement mechanism is not specified.
  Affects: [19-zoom-live-classes.md](specifications/19-zoom-live-classes.md).
- **Whether Zoom join-link protection follows the same signed-URL/short-lived-token rule as video**: `.claude/rules/security.md`'s "Video & Session Protection" section only names video explicitly.
  Affects: [19-zoom-live-classes.md](specifications/19-zoom-live-classes.md).
- **WhatsApp template-approval state machine and opt-in/consent handling**: not addressed in any source document.
  Affects: [22-whatsapp.md](specifications/22-whatsapp.md).
- **Whether YouTube/Vimeo-attached content is subject to the same view-limit/expiry/watermark/device-restriction controls as platform-hosted secure video, or exempt.** A raw YouTube/Vimeo embed URL is inherently more guessable/shareable than a signed platform token, and no document resolves the seam.
  Affects: [27-youtube-vimeo-integrations.md](specifications/27-youtube-vimeo-integrations.md).
- **Monthly-closing lock semantics** for Finance & Expenses (mutation-after-close behavior).
  Affects: [23-finance-and-expenses.md](specifications/23-finance-and-expenses.md).
- **Zoom-sync participant-name reconciliation rule**: no rule specified for when Zoom-sync attendance data doesn't match an enrolled student's standardized name.
  Affects: [10-attendance.md](specifications/10-attendance.md).
- **Bulk-import partial-failure behavior** (students, and by extension any future bulk-import flow): all-or-nothing vs. row-level partial success with an error report is not specified.
  Affects: [03-student-management.md](specifications/03-student-management.md).
- **No concrete visibility taxonomy** is defined for material visibility (what values it can take).
  Affects: [06-lessons-and-materials.md](specifications/06-lessons-and-materials.md).
- **The seam between `content-management`'s expiry/limits and `video-access-management`'s full playback-security stack** for "a video attached as a lesson material" is not spelled out.
  Affects: [06-lessons-and-materials.md](specifications/06-lessons-and-materials.md).

## 11. Documentation inconsistencies to reconcile (not open decisions, but contradictions between source docs)

- **Zoom-recording-attachment phase contradiction**: `source-requirements.md` module 7 implies MVP scope includes attaching Zoom recordings to lessons, but `live-class-management` (which owns Zoom recording management) is entirely Phase 2 per `functional-requirements.md`/`module-catalog.md`. "Attach a Zoom recording to a lesson" cannot actually be delivered at MVP.
  Affects: [06-lessons-and-materials.md](specifications/06-lessons-and-materials.md).
- **Duplicate-slip-detection OCR timing narrative mismatch**: `docs/ui-ux/user-journeys.md` Journey 3 describes OCR reference extraction as already running in the near-term staff review flow, while `functional-requirements.md` FR-PM-3 explicitly phases OCR-based detection to Phase 3 (only exact-match checks are MVP). The FR table should be treated as authoritative, but this should be verified with the requirements owner rather than assumed.
  Affects: [25-duplicate-payment-slip-detection.md](specifications/25-duplicate-payment-slip-detection.md).
- **"Checkout" screen naming gap**: `docs/ui-ux/user-journeys.md` Journey 1 references a `Student > Payments > Checkout` screen that is not actually enumerated in `docs/ui-ux/screen-map.md`'s Student Portal screen list.
  Affects: [07-orders-and-payments.md](specifications/07-orders-and-payments.md).

## 12. WordPress migration — needs a full requirements pass, not a single open decision

Unlike every other item in this log, WordPress migration has **zero presence** in
`docs/requirements/`, `docs/architecture/`, or `docs/ui-ux/` — it exists only as an internal
engineering skill/agent definition (`.claude/skills/wordpress-migration/SKILL.md`,
`.claude/agents/legacy-migration-engineer.md`). See
[28-wordpress-migration.md](specifications/28-wordpress-migration.md) for what little is defined.
Recommend a dedicated requirements pass (business purpose, actor, scope, target-domain mapping,
phase) before treating it as a scoped feature, plus a dedicated ADR before any implementation
given it would populate change-controlled ledger/enrollment tables.

## 13. UI/UX documentation gaps (component-library and journey coverage)

- **Status Chip vocabulary** (`docs/ui-ux/component-library-spec.md` §2.10) has no entries for: device status (Active/Reset-Pending/Blocked), SMS/WhatsApp delivery status, WhatsApp template-approval status, or settlement-run status.
- **No documented UX journey exists for**: settlement runs, YouTube/Vimeo attach failures, custom-domain verification, or session-view-limit/concurrent-session-blocked states — these need journey-level documentation analogous to `docs/ui-ux/user-journeys.md` Journeys 1-6 before implementation.
- **Star-rating accessible input pattern** (course reviews) and **embedded third-party video player accessibility** (YouTube/Vimeo) are both gaps not covered in `docs/ui-ux/accessibility.md`.
- **Watermark overlay contrast/positioning** has no accessibility guidance for secure video.

## 14. Approval status of drafted ADRs

All three mechanism-level ADRs referenced throughout this catalog are **Accepted (2026-08-02)**:
`docs/adr/ADR-006-tenant-isolation-repository-mechanism.md`,
`docs/adr/ADR-007-authentication-token-and-device-mechanism.md`, and
`docs/adr/ADR-008-video-content-protection-mechanism.md`. Implementation may proceed against all
three domains' decisions — including
[17-session-view-limits.md](specifications/17-session-view-limits.md) and
[20-secure-video.md](specifications/20-secure-video.md) — without a further ADR-approval
prerequisite.

## 15. Student Management (MVP-006) — items surfaced during module planning/implementation

Surfaced by `docs/plans/MVP-006 Student Management.md` §21, not by the original five-agent
requirements review that produced sections 1–14 above — logged here per that plan's own §19
instruction. Only the items not already tracked elsewhere in this log at this level of detail
are listed (the plan's own self-registration public-vs-invite-only question is already covered
in §1 above; the bulk-import partial-row-failure question is already covered in §10 above).

- **Guardian/parent info and school/grade/stream field list is not itemized anywhere.** STU-1's
  own backlog entry proposes a concrete-looking schema sketch
  (`guardian_name, guardian_contact, school, grade, stream`), but this is an unratified
  placeholder, not a confirmed column list — the tension between the explicit "not itemized"
  note and the concrete-looking sketch should be resolved explicitly, not silently treated as
  final. Not one of the original 9 items plan §19 instructed appending here (it's already
  present in the spec's own Open Decisions list, `03-student-management.md`), but added as its
  own bullet regardless so `docs/architecture/database-architecture.md`'s cross-reference to
  this section for `student_profile`'s still-provisional column set points at something that
  actually exists here.
  Affects: [03-student-management.md](specifications/03-student-management.md).
  Source: `docs/plans/MVP-006 Student Management.md` §8.1 (provisional column list), §21 item 3.
- **`student_profile.status` column — recommended deviation from the backlog's literal text,
  needs explicit confirmation.** STU-1's own database-impact line in `product-backlog.md` lists
  `status` as a `student_profile` column, but the shipped migration deliberately omits it,
  reusing `tenant_user.status` instead — matching the `staff_profile`/STAFF-1 precedent and
  avoiding a second, independently-drifting copy of account state. This is a reasoned deviation,
  not a silent override, but was never separately ratified against the backlog's literal text.
  Affects: [03-student-management.md](specifications/03-student-management.md).
  Source: `docs/plans/MVP-006 Student Management.md` §8.1, §21 item 4.
- **Teacher roster — `course-management`'s teacher-course-assignment data model does not exist
  yet**, so there is no source-of-truth a roster query could filter against (distinct from the
  already-tracked bulk-import-partial-failure half of this same blocker in §10 above). Building
  it now would necessarily fake the filter or ship an unfiltered-roster fallback, both violating
  the spec's own §7 "backend-pre-filtered, never client-filtered" requirement in spirit —
  deferred explicitly rather than stopgapped.
  Affects: [03-student-management.md](specifications/03-student-management.md), [04-teacher-management.md](specifications/04-teacher-management.md), [05-course-management.md](specifications/05-course-management.md).
  Source: `docs/plans/MVP-006 Student Management.md` §9, §15(d), §21 item 2(b).
- **No password-strength policy exists anywhere in this backend** (only Argon2 hashing config,
  no minimum-length/complexity check). Student self-registration — the first public,
  unauthenticated, credential-issuing endpoint — is exactly the surface where this matters most,
  and remains unbuilt pending this decision.
  Affects: [03-student-management.md](specifications/03-student-management.md).
  Source: `docs/plans/MVP-006 Student Management.md` §9, §15(a), §21 item 6.
- **No rate-limiting/abuse-prevention mechanism exists anywhere in this backend** (confirmed: no
  bucket4j/resilience4j-ratelimiter dependency, no rate-limit code pattern anywhere). Already
  affects `/api/v1/auth/login` and `/api/v1/tenant-registrations`, but student self-registration
  is a materially larger abuse surface (mass fake-account creation, subdomain/tenant probing at
  volume) — a cross-cutting infrastructure gap this module inherits and surfaces sharply, not
  something to solve unilaterally inside `user-management`.
  Affects: [03-student-management.md](specifications/03-student-management.md).
  Source: `docs/plans/MVP-006 Student Management.md` §9, §15(a), §21 item 6.
- **Self-registration enumeration risk at the business-logic layer, distinct from the
  tenant-resolution layer.** `TenantResolutionFilter` already structurally prevents
  tenant-existence enumeration (an unresolved subdomain and a resolved-but-ineligible tenant
  collapse into one identical response before any controller runs), but the still-unbuilt
  registration endpoint's own business logic must not reintroduce the same vector — e.g. a
  differently-shaped error on a downstream failure for "real tenant" vs. "no such tenant," or a
  timing difference in the duplicate-email check.
  Affects: [03-student-management.md](specifications/03-student-management.md).
  Source: `docs/plans/MVP-006 Student Management.md` §15(a).
- **Role-collision on create/import is unaddressed** — no document says what happens if a
  manual-create or (future) bulk-import row targets an email already associated with a
  different role (Teacher, Staff) in the same tenant.
  Affects: [03-student-management.md](specifications/03-student-management.md).
  Source: `docs/plans/MVP-006 Student Management.md` §4.2, §21 item 13.
- **Precondition gap: interaction with a suspended/trial/cancelled tenant is unaddressed.**
  `FR-TM-3` states tenant-status transitions "immediately affect login/access," but no document
  says whether self-registration, manual creation, or (future) bulk import should be blocked
  when the tenant isn't `active`.
  Affects: [03-student-management.md](specifications/03-student-management.md), [01-tenant-onboarding.md](specifications/01-tenant-onboarding.md).
  Source: `docs/plans/MVP-006 Student Management.md` §3, §21 item 11.
- **`FR-UM-3` device/communication-history phase-tag contradiction.** `FR-UM-3` tags
  device/communication history as MVP, but the domains that would populate them
  (device-authentication: `FR-IAS-3`–`FR-IAS-7`; notification delivery logs: `FR-NM-4`) are both
  tagged Phase 2 — STU-3's own backlog dependency list doesn't even name a dependency for these
  two tabs despite them being named in the module's business purpose. Should be resolved
  (descope explicitly to Phase 2, or clarify a reduced MVP version of each tab) before the
  history-composition capstone story (STU-3, not yet built) is treated as fully scoped.
  Affects: [03-student-management.md](specifications/03-student-management.md).
  Source: `docs/plans/MVP-006 Student Management.md` §4.5, §21 item 7.
- **Same-tenant cross-student enumeration AC gap in the source spec, distinct from the
  already-tracked cross-tenant case.** The spec's own §8 acceptance-criteria checklist names
  only the cross-tenant enumeration test explicitly; the same-tenant case (student A viewing/
  editing student B's profile by ID, both in the same tenant) is required by
  `.claude/rules/security.md`'s general enumeration-testing mandate but was never its own named
  AC. **Now structurally addressed in the shipped implementation** (the self-service `/me`
  endpoints take no `{id}` parameter at all, making same-tenant cross-student access
  impossible by construction, not merely permission-denied — see
  `docs/api/user-management.md`) — logged here so the spec's own AC list reflects this
  explicitly rather than relying on the implementation having happened to get it right.
  Affects: [03-student-management.md](specifications/03-student-management.md).
  Source: `docs/plans/MVP-006 Student Management.md` §5 AC 16, §21 (cross-referenced from AC 16's own text).

## 16. Course Management (MVP-008) — carried-forward limitations

Surfaced by `docs/plans/MVP-008 Course Management.md` §21 and confirmed still open by a
post-implementation module review; none of the three are this module's to resolve
unilaterally.

- **Course-Coordinator-vs-teacher-reassignment authorization gap**: `DomainArea.COURSES`
  grants Course Coordinator `CREATE_EDIT`/`APPROVE`, but teacher reassignment
  (`POST /api/v1/courses/{id}/teacher`) is Tenant-Admin-only, decided by the product owner
  during MVP-008's planning as the narrowest option (matching Staff Management's precedent
  of keeping the highest-leverage actions Institute-Owner-only). Implemented and tested;
  flagged here only so the asymmetry between this endpoint and the rest of the `COURSES`
  matrix is documented somewhere durable, not just in the plan file.
  Affects: [05-course-management.md](specifications/05-course-management.md).
- **DRAFT-course price-change audit scope**: `course_price_history` is written on every
  price change regardless of course status (DRAFT included), not only for published
  courses — a deliberate simplification (a status-conditional branch in the one
  non-bypassable write path was judged more fragile than writing unconditionally, and
  unconditional writing is a strict superset of the spec's literal "published course"
  requirement). If a future review wants price history scoped to published-only, that is a
  product decision to make explicitly, not something to silently narrow.
  Affects: [05-course-management.md](specifications/05-course-management.md).
- **`course_price_history` is not a substitute for the canonical `audit-log-management`
  audit row**: it satisfies the spec's literal "one audit entry with before/after" text
  today, but it is a domain-local table, not the platform's compliance-grade audit log,
  which doesn't exist yet. `CoursePriceChangedEvent` is published in the same transaction
  so `audit-log-management` can persist its own canonical row from that event once built,
  with zero rework to `course-management`. Do not report MVP-008's audit requirement as
  "fully met" without this qualification until `audit-log-management` actually exists and
  consumes the event.
  Affects: [05-course-management.md](specifications/05-course-management.md),
  [13-audit-logs.md](specifications/13-audit-logs.md).
