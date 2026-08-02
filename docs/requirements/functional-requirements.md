# Functional Requirements

Status: Draft — consolidated from `docs/requirements/source-requirements.md` (module
list, §5 "Suggested MVP vs Phase 2 vs Phase 3") and `docs/requirements/module-catalog.md`
(domain ownership + phase mapping). This document is the requirement-level breakdown
`module-catalog.md` refers to as "the full requirement-level phase breakdown" — it does
not redecide domain ownership or phase boundaries, both of which are already fixed in
those two documents; it makes each domain's requirements individually listed,
phase-tagged, and testable.

Related: `docs/requirements/source-requirements.md`, `docs/requirements/module-catalog.md`,
`docs/requirements/non-functional-requirements.md`, `docs/requirements/
user-roles-and-permissions.md`, `.claude/rules/architecture.md`

## How to read this document

- **ID scheme:** `FR-<domain-abbrev>-<number>`, stable once assigned — do not renumber
  existing IDs when adding new requirements, append instead.
- **Phase:** MVP / Phase 2 / Phase 3 / Phase 4, per `source-requirements.md` §5 and
  `module-catalog.md`'s per-domain phase notes. A requirement's phase is a scope
  boundary, not a priority hint within a phase.
- **Acceptance criteria:** written to be testable (see `.claude/rules/testing.md`), not
  exhaustive test code — QA/dev use these as the basis for concrete test cases.
- **Source:** the `source-requirements.md` module number the requirement traces to.
- Requirements already fully specified as their own architecture documents (multi-tenancy,
  authentication, payment/ledger, enrollment/access) are **not repeated verbatim** here —
  each domain section links out and lists only the requirement-level items not already
  covered at that level of detail.

---

## identity-access-service

| ID | Requirement | Phase | Acceptance criteria | Source |
|---|---|---|---|---|
| FR-IAS-1 | Student, Teacher, Tenant Admin, and Platform Admin all authenticate through one shared login mechanism | MVP | A login request from any role resolves through the same backend path; no role has a parallel/separate auth stack | Module 3/4/5, `authentication-authorization.md` §3 |
| FR-IAS-2 | Session/token issued on successful login | MVP | A valid session artifact is issued and is required for every subsequent protected request | Module 3/4/5 |
| FR-IAS-3 | Device registration at login | Phase 2 | Server generates/verifies device fingerprint at login; a client-supplied device id alone is never trusted | Module 17 |
| FR-IAS-4 | Device limit enforcement with override precedence (student > course > tenant > plan) | Phase 2 | Login beyond the resolved limit is rejected server-side (401/403); the most specific configured override wins in a test with multiple levels configured | Module 17, `authentication-authorization.md` §6 |
| FR-IAS-5 | Device reset by authorized admin/support, with cooldown | Phase 2 | Reset requires device-reset permission for that tenant/student; a persisted cooldown blocks immediate reuse of the freed slot; exactly one audit row is written per reset | Module 17 |
| FR-IAS-6 | Device history and login activity retrievable for admin/support review | Phase 2 | History is tenant-filtered like any other tenant-owned data | Module 17 |
| FR-IAS-7 | Suspicious-login detection (impossible travel, rapid device churn, many distinct IPs) | Phase 2 | Detection runs server-side; at minimum produces an audit/security log entry | Module 17 |

## tenant-management

| ID | Requirement | Phase | Acceptance criteria | Source |
|---|---|---|---|---|
| FR-TM-1 | Institute registration and tenant approval workflow | MVP | A new tenant enters a pending-approval state; only Platform Admin can approve/reject; approval provisions tenant-scoped default config | Module 1 |
| FR-TM-2 | Tenant profile (logo, colors, contact info, domain/subdomain) | MVP | Tenant profile fields are editable by Tenant Admin, tenant-scoped, and resolvable at request time from subdomain/custom domain | Module 1 |
| FR-TM-3 | Tenant subscription plan and status lifecycle (trial/active/suspended/cancelled) | MVP | Status transitions are Platform-Admin-only, audit-logged, and immediately affect login/access (`Suspended Tenant` behavior) | Module 1, `authentication-authorization.md` |
| FR-TM-4 | Tenant feature limits and usage tracking | MVP core, Phase 2 enforcement UI | Usage vs. limit is queryable per tenant; enforcement of individual limits lives in the owning domain per `module-catalog.md`'s Module D gap note | Module 1, Module D |
| FR-TM-5 | Custom LMS name, custom color theme, custom domain, tenant-specific login/public pages | Phase 2 | Branding resolves at runtime from tenant-scoped config, never hardcoded per build | Module 2 |
| FR-TM-6 | Branding preview panel, theme presets, per-tenant favicon/certificate/invoice branding | Phase 2 | Preview renders through the same theming pipeline as the live site (no separate preview-only path) | Module 2 (recommended) |

## user-management

| ID | Requirement | Phase | Acceptance criteria | Source |
|---|---|---|---|---|
| FR-UM-1 | Student registration, manual creation by admin/staff, bulk import | MVP | Manual/bulk-created accounts carry a "must change password" flag; self-registration is tenant-scoped | Module 3 |
| FR-UM-2 | Student profile: guardian/parent info, school/grade/stream, status | MVP | Fields are editable by the student (own profile) and Tenant Admin/Staff (any student in-tenant) | Module 3 |
| FR-UM-3 | Student enrollment/payment/attendance/exam/device/communication history | MVP | History views are read-only, tenant-scoped, and each history type sources from its owning domain (not duplicated data) | Module 3 |
| FR-UM-4 | Student tags, risk indicators, inactive-student detection, timeline/activity feed | Phase 2 | Computed/derived, not manually re-enterable state that could drift from source data | Module 3 (recommended) |
| FR-UM-5 | Teacher registration, approval, profile, assigned-courses view | MVP | A Teacher's course list is backend-filtered to their own assignments, never client-filtered from a full dataset | Module 4 |
| FR-UM-6 | Teacher revenue/commission settings | Phase 2 | Ties into `ledger-settlement-management`'s settlement calculation, not duplicated locally | Module 4 |
| FR-UM-7 | Teacher availability, payout profile, performance analytics, public profile page | Phase 2/3 | Public profile is read-only and only shows Teacher-approved public fields | Module 4 (recommended) |
| FR-UM-8 | Staff accounts, separate logins, role-based access, activity logs, permission management | MVP | See `docs/requirements/user-roles-and-permissions.md` for the concrete role list and permission boundaries | Module 5 |
| FR-UM-9 | Staff count enforcement vs. SaaS plan limit | Phase 2 | Creating a staff account beyond the plan limit is rejected server-side, not just hidden in the UI | Module 5 |

## course-management

| ID | Requirement | Phase | Acceptance criteria | Source |
|---|---|---|---|---|
| FR-CM-1 | Course creation: category, subject/stream/grade/year, modules, lessons, sessions | MVP | A course is not visible on the public storefront until explicitly published | Module 6 |
| FR-CM-2 | Pricing, enrollment rules, access duration, visibility (draft/private/public) | MVP | Pricing changes on a published course are audit-logged via a single non-bypassable code path | Module 6, `.claude/rules/security.md` |
| FR-CM-3 | Teacher assignment to courses | MVP | Only Tenant Admin (or a permitted staff sub-role) can assign/reassign a course's teacher | Module 6 |
| FR-CM-4 | Course materials attachment (see content-management for the materials themselves) | MVP | A course's material list reflects only materials explicitly attached to it | Module 6 |
| FR-CM-5 | Course landing-page builder, trial/free lesson support, bundles, prerequisites, cloning, archive, SEO fields | Phase 2 | Cloning a course never carries over enrollment/payment history | Module 6 (recommended) |
| FR-CM-6 | Course reviews (student-submitted, moderated) | Phase 2 | Only verified-enrollment students may submit a review; only approved reviews are publicly visible | Module 19 |

## content-management

| ID | Requirement | Phase | Acceptance criteria | Source |
|---|---|---|---|---|
| FR-CNT-1 | Upload PDFs, images, notes; attach videos, Zoom recordings, YouTube/Vimeo links | MVP (PDF/image/notes), Phase 3 (YouTube/Vimeo) | Every upload is server-side validated for MIME type, size, and uploader permission before acceptance | Module 7 |
| FR-CNT-2 | Organize materials by lesson/module/session; set visibility | MVP | A material's visibility is enforced at fetch time, not just hidden in navigation | Module 7 |
| FR-CNT-3 | Material expiry, view/download limits | Phase 2 | Expired/limit-exceeded materials return a distinct denied state, not a generic error | Module 7 |
| FR-CNT-4 | Static watermarking | Phase 2 | Watermark includes student-identifying info sufficient to discourage redistribution | Module 7 |
| FR-CNT-5 | Material versioning, bulk upload, folder structure, drag-and-drop ordering | Phase 2 | Drag-and-drop ordering has a keyboard-operable equivalent | Module 7 (recommended) |
| FR-CNT-6 | Dynamic watermarking, document analytics | Phase 3 | — | Module 7 (recommended) |

## video-access-management

| ID | Requirement | Phase | Acceptance criteria | Source |
|---|---|---|---|---|
| FR-VAM-1 | Secure video playback via signed, short-lived, single-session-scoped URLs/tokens | MVP baseline | A playback URL is never stable/predictable; expiry enforced server-side/by the storage provider, never only by a frontend countdown | Module 8, `.claude/rules/security.md` |
| FR-VAM-2 | Access token validation on every playback request | MVP | An expired/revoked/cross-tenant token is rejected even if it was valid at issuance | Module 8 |
| FR-VAM-3 | View limits per video/session, watch-time tracking, session expiry, student name watermark, device restriction | Phase 2 | Concurrent-session blocking uses real Redis-backed session state in tests | Module 8 |
| FR-VAM-4 | Playback abuse detection, concurrent-session blocking, video access audit logs, suspicious-activity alerts | Phase 2 | A second concurrent session from a different device/IP triggers server-side revocation and an audit/security log entry | Module 8 (recommended) |
| FR-VAM-5 | External secure video storage provider integration, dynamic watermark | Phase 3 | No binary video is streamed/stored through the Spring Boot app itself | Module 8 |

## live-class-management

| ID | Requirement | Phase | Acceptance criteria | Source |
|---|---|---|---|---|
| FR-LCM-1 | Tenant Zoom account integration, schedule live classes, generate unique join URLs | Phase 2 | Join URLs are not guessable/reusable across tenants or sessions | Module 9 |
| FR-LCM-2 | Prevent link sharing, standardize participant names, sync attendance | Phase 2 | Attendance sync produces records consumable by `attendance-management` via its `api`, not a direct table join | Module 9 |
| FR-LCM-3 | Manage recordings, attach to lesson/session, cloud storage tracking | Phase 2 | — | Module 9 |
| FR-LCM-4 | Multiple Zoom accounts per tenant, auto-recurring meetings, auto-import/convert recordings, reminder automation | Phase 3 | — | Module 9 (recommended) |

## enrollment-management

See `docs/architecture/enrollment-access.md` for the full activation/expiry rules (not
repeated here — this domain's correctness rules are change-controlled and documented at
the architecture level, not the feature-inventory level).

| ID | Requirement | Phase | Acceptance criteria | Source |
|---|---|---|---|---|
| FR-EM-1 | Course access activation strictly from confirmed payment/approved slip | MVP | Activation has a FK/NOT NULL trail to the specific confirmed payment or approved slip row | Module 13, `enrollment-access.md` §2 |
| FR-EM-2 | Course/session/material/video expiry | MVP (course), Phase 2 (session/material/video) | Expired access renders a distinct "access expired" state, not a generic error | Module 18 |
| FR-EM-3 | Reactivation request + admin approval | MVP | Reactivation always creates a new order/payment, never mutates the original | Module 18 |
| FR-EM-4 | Expiry rules engine, grace period, auto reminder before expiry, bulk expiry extension, student-specific override | Phase 2 | Bulk extension and per-student override are both audit-logged admin actions | Module 18 (recommended) |

## payment-management

See `docs/architecture/payment-ledger.md` for the full state-machine and append-only
rules.

| ID | Requirement | Phase | Acceptance criteria | Source |
|---|---|---|---|---|
| FR-PM-1 | Centralized payment collection, tenant-aware orders/payments | MVP | Every Order/Payment carries `tenant_id` from trusted context; never mixed across tenants even in platform-admin views | Module 12 §Phase 1 |
| FR-PM-2 | Manual payment slip upload, approval workflow (`SUBMITTED → UNDER_REVIEW → APPROVED\|REJECTED`) | MVP | Transitions are one-directional; `SUBMITTED` never triggers activation | Module 12/13 |
| FR-PM-3 | Duplicate reference-number check, duplicate image-hash check | MVP (exact-match), Phase 3 (OCR-based) | Both checks are mandatory gates before `APPROVED`; no code path skips them | Module 13, Payment Slip Intelligence |
| FR-PM-4 | Manual override of a duplicate/suspicious flag | MVP | Override requires a recorded reason and writes an audit log entry; an override with no reason is rejected | Module 13 |
| FR-PM-5 | Payment history, receipt generation, refund handling, admin payment dashboard | MVP | Dashboard/history is derived from ledger entries + slip state, not order/upload records | Module 12/13 |
| FR-PM-6 | Payment expiry, reactivation payment | MVP | Reactivation is always a new payment/order | Module 13 |
| FR-PM-7 | Suspicious slip flagging (additive, never cleared) | MVP | Re-running checks adds a new flag record, never clears/deletes a prior one | Payment Slip Intelligence |

## ledger-settlement-management

| ID | Requirement | Phase | Acceptance criteria | Source |
|---|---|---|---|---|
| FR-LSM-1 | Append-only ledger entries, traceable to tenant + order/payment or settlement run | MVP (ledger exists as of Phase 1 payments), Phase 2 (settlement runs) | No repository method exposes delete/deleteById on ledger entities | Module 12, `payment-ledger.md` §5 |
| FR-LSM-2 | Tutor/tenant settlement calculation, commission %, gateway-fee tracking, settlement status, export | Phase 2 | Re-running a settlement for an already-settled period/tenant does not create duplicate payout entries (idempotency, DB-uniqueness-guarded) | Module 12 §Phase 2 |
| FR-LSM-3 | Historical settlement figures immutable after payout; corrections are new adjustment entries | Phase 2 | A rate-config change never alters a previously computed settlement's stored figures | `payment-ledger.md` §6 |

## attendance-management

| ID | Requirement | Phase | Acceptance criteria | Source |
|---|---|---|---|---|
| FR-ATT-1 | Class/session attendance, manual marking | MVP | Attendance records are tenant-scoped and course/session-scoped | Module 10 |
| FR-ATT-2 | Student/course/teacher attendance reports | MVP | — | Module 10 |
| FR-ATT-3 | Zoom attendance sync, absent-student alerts | Phase 2 | Alerts are dispatched asynchronously via `notification-management`, not inline with the sync job | Module 10 |
| FR-ATT-4 | Late/early-leave tracking, attendance-based access restrictions | Phase 2 | — | Module 10 (recommended) |
| FR-ATT-5 | QR attendance, smart card attendance | Phase 3 | — | Module 10 (recommended) |

## exam-management

| ID | Requirement | Phase | Acceptance criteria | Source |
|---|---|---|---|---|
| FR-EX-1 | Create exams, question bank (MCQ + structured), scheduling, time limits | MVP | — | Module 11 |
| FR-EX-2 | Auto marking (MCQ), manual marking (structured), results publishing, student answer review | MVP | Result publication is a confirmable, audit-considered action (see `docs/requirements/user-roles-and-permissions.md`) | Module 11 |
| FR-EX-3 | Exam analytics | MVP | — | Module 11 |
| FR-EX-4 | Negative marking, randomized questions, question pools, attempt limits, rank lists | Phase 2 | — | Module 11 (recommended) |
| FR-EX-5 | Anti-cheating controls, paper discussion videos, model paper library | Phase 3 | Ownership of Model Paper Library between Teacher and Tenant Admin is an **open question** — see `docs/ui-ux/screen-map.md` | Module 11 (recommended) |

## finance-expense-management

| ID | Requirement | Phase | Acceptance criteria | Source |
|---|---|---|---|---|
| FR-FEM-1 | Income/expense dashboards, category/account-wise expenses, multiple bank/cash accounts | Phase 2 | — | Module 14 |
| FR-FEM-2 | Scheduled payments, tutor payouts, wallet transactions, financial reports | Phase 2 | Tutor payouts consume `ledger-settlement-management`'s settlement records rather than computing independently | Module 14 |
| FR-FEM-3 | Profit/loss report, cashflow forecast, expense approval workflow, receipt attachment, monthly closing, export | Phase 2/3 | — | Module 14 (recommended) |

## notification-management

| ID | Requirement | Phase | Acceptance criteria | Source |
|---|---|---|---|---|
| FR-NM-1 | Email + in-app notifications | MVP | Dispatch is async, does not share a transaction with the triggering privileged action | Module 15 |
| FR-NM-2 | SMS, WhatsApp channels; tenant-specific and module-specific templates; bulk/segment messaging | Phase 2 | — | Module 15 |
| FR-NM-3 | Payment/class/exam reminders, absence alerts | Phase 2 | Reminders are event/schedule-driven, not computed on every page load | Module 15 |
| FR-NM-4 | Notification preference center, delivery logs, failed-message retry, WhatsApp template approval, marketing/transactional separation | Phase 2 (recommended) | — | Module 15 (recommended) |
| FR-NM-5 | Notification automation engine (payment pending, class starting soon, absence, result published, access expiring, device limit exceeded, slip rejected, new material) | Phase 2 | Each trigger is event-driven from its owning domain, not polled | Module E |

## integration-management

| ID | Requirement | Phase | Acceptance criteria | Source |
|---|---|---|---|---|
| FR-INT-1 | Email/SMTP, object storage, payment gateway credential ownership | MVP | No other domain embeds provider SDKs/credentials directly | Module 16 |
| FR-INT-2 | Zoom, SMS, WhatsApp integration; per-tenant integration settings; health checks | Phase 2 | — | Module 16 |
| FR-INT-3 | YouTube, Vimeo, secure video storage | Phase 3 | — | Module 16 |
| FR-INT-4 | Webhook logs, API credential vault | MVP (vault/logs exist), ongoing per-integration | Credentials are never logged in plaintext | Module 16 (recommended) |

## reporting-analytics

| ID | Requirement | Phase | Acceptance criteria | Source |
|---|---|---|---|---|
| FR-RA-1 | Basic payment/platform reports | MVP | — | Module 20 |
| FR-RA-2 | Student/teacher/course/payment/attendance/exam/finance/tenant reports | Phase 2 | Built from domain events/scheduled aggregation, not live cross-domain joins at request time | Module 20 |
| FR-RA-3 | SaaS analytics: active students, revenue, churn risk, most-watched courses, bandwidth/storage usage, payment-pending count, device violations, support ticket volume | Phase 3 | Platform-level aggregate reports are reachable only by Platform Admin (tested) | Module 20 (recommended) |

## audit-log-management

| ID | Requirement | Phase | Acceptance criteria | Source |
|---|---|---|---|---|
| FR-ALM-1 | Immutable audit log covering price changes, payment approvals, device resets, access extensions, material deletions, settlement changes, impersonation | MVP (module exists), per-feature as source domains ship | No update/delete endpoint or repository method exists for audit rows | Module A, `.claude/rules/security.md` |

## support-management

| ID | Requirement | Phase | Acceptance criteria | Source |
|---|---|---|---|---|
| FR-SM-1 | Student/Teacher/Tenant support tickets, assignment, status, internal notes | Phase 2 | Internal notes are never exposed to the requesting student/teacher role | Module B |
| FR-SM-2 | Ticket linkage to related payment/course/student records | Phase 2 | Linkage is read-only via other domains' `api` interfaces, respecting tenant isolation | Module B |

## Cross-cutting (composition pending ratification — see `module-catalog.md` Open Questions)

| ID | Requirement | Phase | Acceptance criteria | Source |
|---|---|---|---|---|
| FR-XC-1 | Public tenant storefront: homepage, course listing/detail, teacher profile, reviews, FAQ, SEO fields, custom domain | Phase 2 | Composed from `course-management` + `tenant-management` + `user-management` public-read APIs pending explicit domain-ownership ratification | Module C |
| FR-XC-2 | Feature flag & plan limit engine (max students/teachers/staff, storage/bandwidth quota, white-label/custom-domain/advanced-exam/SMS-WhatsApp toggles) | MVP (basic limits), Phase 2 (full engine) | Each consuming domain enforces its own quota by reading `tenant-management`-owned config; ownership pending ratification | Module D |
| FR-XC-3 | AI-assisted features (quiz generation, summarization, weak-student suggestions, report/description generation, suspicious-slip augmentation) | Phase 3 | Never a replacement for the mandatory human-reviewed override rule in payment slip review | Module F |

## Related

- `docs/requirements/module-catalog.md`
- `docs/requirements/non-functional-requirements.md`
- `docs/requirements/user-roles-and-permissions.md`
