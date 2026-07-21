# Module Catalog — Backend Domain Mapping

Status: Draft for review. Maps each confirmed backend domain from `.claude/rules/architecture.md`
/ `CLAUDE.md` to the source-requirements.md module(s)/features it owns, and the phase(s)
that apply. Cross-references `docs/requirements/functional-requirements.md` for the
full requirement-level phase breakdown.

## How to read this document

- **Owns** — the domain is the primary/sole owner of this requirement's data and logic.
- **Consumes** — the domain reads/reacts to another domain's `api`/events but does not
  own the underlying data (per the cross-module communication rules in
  `.claude/rules/architecture.md`).
- Phases listed are the union of phases that apply to any requirement mapped to that
  domain, per `docs/requirements/functional-requirements.md`.

---

## identity-access-service

- **Owns:** Student/Teacher/Admin/staff login and authentication (Modules 3, 4, 5);
  session/token issuance underlying video access tokens (Module 8's token-validation
  requirement is implemented against identity-access-service-issued sessions); Device
  Authentication & Account-Sharing Prevention (Module 17) — device registration, limit
  enforcement, suspicious-login detection.
- **Consumes:** Tenant identity resolved once per request from `tenant-management`
  (never re-derives it).
- **Phases:** MVP (login), Phase 2 (device authentication, Module 17).

## tenant-management

- **Owns:** Tenant & Institute Management (Module 1) in full; tenant identity
  resolution (subdomain/custom-domain → tenant context) at the auth/edge layer; tenant
  subscription plan assignment and tenant status lifecycle; the config data underlying
  White-Labeling & Branding (Module 2) and Feature Flag & Plan Limit Engine (Module D) —
  see Open Questions on Module D ownership.
- **Consumes:** Nothing business-domain-specific (foundational module per
  `.claude/rules/architecture.md`).
- **Phases:** MVP (Module 1 core), Phase 2 (Module 2 branding/custom domain).

## user-management

- **Owns:** Student Management (Module 3), Teacher Management (Module 4), Staff
  Management (Module 5) — profile/data model and role assignment (role *enforcement*
  is identity-access-service's concern; user-management owns the role/permission data
  model).
- **Consumes:** Tenant context from `tenant-management`.
- **Phases:** MVP (all three modules' core), Phase 2/3 for each module's recommended
  additions (student risk indicators, teacher performance analytics, etc. — see
  functional-requirements.md).

## course-management

- **Owns:** Course Management (Module 6) in full; course-level review toggle/data
  model portion of Reviews & Testimonials (Module 19, though the review submission/
  moderation workflow may be a shared concern — see Open Questions); the course-listing/
  detail data consumed by the Public Website/Storefront (Module C).
- **Consumes:** Teacher assignment data from `user-management`; enrollment/access state
  from `enrollment-management` for "is this student allowed to see this" checks.
- **Phases:** MVP (Module 6 core), Phase 2 (recommended additions, reviews, storefront
  rendering).

## content-management

- **Owns:** Learning Materials Management (Module 7) in full — uploads, organization,
  visibility, versioning, folder structure.
- **Consumes:** `integration-management`'s object-storage/YouTube/Vimeo API interfaces
  rather than embedding storage-provider SDKs directly; `video-access-management` for
  any material that is itself a protected video.
- **Phases:** MVP (basic uploads/organization), Phase 2 (expiry, view/download limits,
  static watermarking, versioning), Phase 3 (YouTube/Vimeo attachment, dynamic
  watermarking, document analytics).

## video-access-management

- **Owns:** Video & Session Protection (Module 8) in full — signed URL/token issuance
  and validation, view limits, watch-time tracking, session expiry, watermarking
  application, concurrent-session blocking, playback-abuse detection.
- **Consumes:** `enrollment-management` for current access validity; `identity-access-service`
  for device/session state; `integration-management` for the external secure video
  storage provider (Phase 3).
- **Phases:** MVP (baseline signed-URL/token/session-logging controls — see
  functional-requirements.md's Module 8 phase-resolution note), Phase 2 (view limits,
  session expiry, device restriction, static watermark), Phase 3 (dynamic watermark,
  secure external video storage integration).

## live-class-management

- **Owns:** Live Class / Zoom Management (Module 9) in full.
- **Consumes:** `integration-management`'s Zoom API interface (never embeds the Zoom
  SDK/credentials directly); publishes attendance-sync events consumed by
  `attendance-management`.
- **Phases:** Phase 2 (core), Phase 3 (recommended additions — multi-account,
  auto-recurring meetings, auto-import/convert recordings).

## enrollment-management

- **Owns:** Enrollment rules/access duration (part of Module 6), course access
  activation (Module 13), Smart Expiry/Access Control (Module 18) in full.
- **Consumes:** `payment-management`'s status-check `api` interface synchronously,
  within the same transaction, to confirm payment/approval before activating —
  never trusts `Order` state directly (per `.claude/rules/backend.md` transaction-
  boundary rule).
- **Phases:** MVP (activation, payment-based/course expiry, reactivation core), Phase 2
  (session/material/video expiry, expiry rules engine, grace period, bulk extension).

## payment-management

- **Owns:** Payment Management (Module 12) in full, Student Payments (Module 13)
  including the Payment Slip Intelligence sub-module.
- **Consumes:** `integration-management`'s payment-gateway `api` interface (never
  embeds gateway SDK/credentials); publishes payment-confirmed events consumed by
  `notification-management`, `audit-log-management`, `reporting-analytics`.
- **Phases:** MVP (Phase 1 payment scope, manual slip approval, exact-match duplicate
  checks), Phase 2 (n/a — settlement math lives in `ledger-settlement-management`),
  Phase 3 (tenant-specific payment accounts/routing, OCR-based slip intelligence),
  Phase 4 (split payments, gateway-permitting).

## ledger-settlement-management

- **Owns:** The settlement/commission/gateway-fee portion of Module 12 (Phase 2/3/4),
  and the ledger-entry data model underlying both Module 12 and the tutor-payout
  portion of Finance & Expenses Management (Module 14).
- **Consumes:** `payment-management`'s confirmed-payment events/records as the source
  of truth for what may be settled; never mutates a `payment-management` row.
- **Phases:** Phase 2 (tutor/tenant settlement, commission, gateway-fee tracking,
  settlement status/export), Phase 3/4 (tenant-payment-account-aware settlement,
  split-payment-aware settlement).

## attendance-management

- **Owns:** Attendance Management (Module 10) in full.
- **Consumes:** `live-class-management`'s Zoom attendance-sync events (Phase 2);
  `notification-management` for absent-student alerts (via events, not direct calls).
- **Phases:** MVP (manual attendance, basic reports), Phase 2 (Zoom sync, alerts,
  late/early tracking, access restrictions), Phase 3 (QR/smart-card attendance).

## exam-management

- **Owns:** Exam Management (Module 11) in full.
- **Consumes:** `user-management` for student/teacher identity; publishes
  result-published events consumed by `notification-management`.
- **Phases:** MVP (core exam creation/marking/results), Phase 2 (negative marking,
  randomization, pools, attempt limits, rank lists), Phase 3 (anti-cheating, paper
  discussion videos, model-paper library).

## finance-expense-management

- **Owns:** Finance & Expenses Management (Module 14), excluding the tutor-payout
  ledger linkage itself (owned by `ledger-settlement-management`; finance-expense-
  management consumes settlement records to populate the tutor-payout line item).
- **Consumes:** `ledger-settlement-management`'s settlement/payout `api` interface.
- **Phases:** Phase 2 (all of Module 14), Phase 3 (cashflow forecast).

## notification-management

- **Owns:** Communication Module (Module 15) in full, Notification Automation Engine
  (Module E) in full.
- **Consumes:** Events published by every other domain (payment confirmed, exam result
  published, device limit exceeded, access expiring, material uploaded, etc.) — per
  `.claude/rules/architecture.md`, this domain is a consumer of events, never reached
  into directly by other domains' repositories/entities. Uses `integration-management`'s
  email/SMS/WhatsApp `api` interfaces.
- **Phases:** MVP (email/in-app channel), Phase 2 (SMS/WhatsApp channels, bulk/segment
  messaging, automation engine, preference center, delivery logs/retry).

## integration-management

- **Owns:** Integrations Center (Module 16) in full — all third-party credentials and
  webhook handling for Zoom, YouTube, Vimeo, secure video storage, SMS, WhatsApp,
  email, payment gateway, object storage.
- **Consumes:** Nothing business-domain-specific; is consumed by nearly every other
  domain through its `api` interfaces, per the architecture rule that other domains
  must not embed provider SDKs/credentials directly.
- **Phases:** MVP (email, object storage, payment gateway, credential vault, webhook
  logs), Phase 2 (Zoom, SMS, WhatsApp, per-tenant integration settings, health checks),
  Phase 3 (YouTube, Vimeo, secure video storage).

## reporting-analytics

- **Owns:** Reports & Analytics (Module 20) in full.
- **Consumes:** Events/read-models published by every other domain — per
  `.claude/rules/architecture.md`, built from domain events/scheduled aggregation, not
  live cross-domain joins at request time.
- **Phases:** MVP (basic payment/platform reports), Phase 2 (student/teacher/course/
  attendance/exam/finance/tenant reports, advanced reports), Phase 3 (SaaS analytics:
  churn risk, most-watched courses, advanced analytics; consumes Module F's AI-assisted
  report generation as an augmentation, not a domain of its own).

## audit-log-management

- **Owns:** Audit Log Module (Module A) in full.
- **Consumes:** Events from every other domain for the tracked action list (price
  change, payment approval, device reset, access extension, material deletion,
  settlement amount change, impersonation) — a consumer domain per
  `.claude/rules/architecture.md`, never reached into by other domains' repositories.
- **Phases:** MVP (the module itself; individual event types "activate" as their
  source feature ships — e.g. the settlement-amount-changed event fires only once
  `ledger-settlement-management`'s Phase 2 scope exists).

## support-management

- **Owns:** Support / Helpdesk Module (Module B) in full.
- **Consumes:** Read-only linkage to payment/course/student records via other domains'
  `api` interfaces (never their repositories directly), respecting the same
  tenant-isolation rules as the linked records.
- **Phases:** Phase 2 (inferred — not named in the source's phase summary).

---

## Cross-Cutting / Unowned Items (Gaps to Resolve)

The confirmed backend domain list in `.claude/rules/architecture.md` has no dedicated
domain for the following source modules. Each is presented here as composed from
existing domains' `api` interfaces, but this should be explicitly ratified rather than
left implicit:

1. **Public Website / Course Storefront (Module C)** — no dedicated domain. Assumed
   composed from `course-management` (listings/detail), `tenant-management` (homepage/
   custom-domain config), and `user-management` (teacher public profile) public-read
   APIs. **Confirm this composition is intentional**, or that a storefront-owning
   domain (or a clearly designated "owner of the composition," likely
   `course-management`) should be named explicitly.
2. **Feature Flag & Plan Limit Engine (Module D)** — no dedicated domain, despite the
   source explicitly calling for it to be designed "from day one." Assumed to live
   inside `tenant-management` as tenant-scoped config, with each consuming domain
   responsible for enforcing its own limits by reading that config. **Confirm this
   ownership**, since an unowned cross-cutting enforcement concern is a known source of
   drift (e.g. one domain forgetting to check a quota).
3. **AI Assistant (Module F)** — no dedicated domain, and not mentioned in `CLAUDE.md`
   at all (only present in source-requirements.md's Phase 3 summary). Assumed to be a
   cross-cutting augmentation consumed by `exam-management` (quiz generation),
   `content-management` (summarization), `reporting-analytics` (weak-student
   suggestions, report generation), `course-management` (description generation), and
   `payment-management` (suspicious-slip detection augmentation — must remain
   additive to, never a replacement for, the mandatory human-reviewed override rule in
   `.claude/rules/payments.md` §3). **Confirm this is intentionally cross-cutting** and
   not meant to become its own domain (which would require an ADR per
   `.claude/rules/architecture.md`).

## Required tests per backend domain

Reference mapping from each confirmed backend domain to the required-test category
(or categories) it must satisfy per the Testability / Test Strategy section of
`docs/requirements/non-functional-requirements.md`. "Cross-tenant negative test"
applies to every domain below unless marked platform-level-only; it is omitted from
the notes column where it would be redundant to restate and is instead assumed as a
floor requirement for all tenant-owned data in that domain.

| Domain | Required test category (in addition to the cross-tenant floor) |
|---|---|
| identity-access-service | Cross-tenant test on auth/session boundaries; device-limit-exceeded + override-precedence test (student > course > tenant > plan) for the device-authentication sub-feature; login/session-anomaly logging test. |
| tenant-management | Cross-tenant test proving a Tenant Admin cannot read/modify another tenant's profile/config/plan/status; platform-admin-only bypass paths (tenant list, approval queue) must have a test proving only platform-admin role can reach them. |
| user-management | Cross-tenant test on staff/teacher/student account CRUD and role/permission assignment; tenant-scoped uniqueness test (e.g. email unique per tenant, not globally, except platform-admin accounts). |
| course-management | Cross-tenant test on course CRUD, enrollment-rule/pricing fields, and course listing/search. |
| content-management | Cross-tenant test on material upload/list/delete; upload-validation test (MIME/size/ownership); protected-content access must reject cross-tenant and cross-role ID-guessing. |
| video-access-management | Token-expiry test AND cross-tenant/cross-session replay test; concurrent-session-blocking test using real Redis. |
| live-class-management | Cross-tenant test on schedule/join-URL generation and recording attachment; join-URL must not be guessable/reusable across tenants. |
| enrollment-management | Activation test proving enrollment activates only from a persisted, verified payment/approval record, never from request payload; cross-tenant test on enrollment read/list. |
| payment-management | Idempotency test (ledger/enrollment state unchanged on repeated webhook/approval); payment-slip duplicate-detection test (reference number + image hash); cross-tenant test on order/payment read/list/dashboard. |
| ledger-settlement-management | Idempotency test on settlement run (no duplicate payout entries on re-run for an already-settled period/tenant); append-only enforcement test (no update/delete path exposed); cross-tenant test on ledger/settlement read/export. |
| attendance-management | Cross-tenant test on attendance marking/read and Zoom-sync ingestion. |
| exam-management | Cross-tenant test on exam creation, question bank, scheduling, and results/analytics read paths. |
| finance-expense-management | Cross-tenant test on expense/income dashboards and account records; idempotency test if this domain triggers scheduled/tutor payout ledger writes. |
| notification-management | Cross-tenant test proving a template/delivery-log/preference record for Tenant A is not readable by Tenant B; async-dispatch test confirming notification-send does not block or share a transaction with the triggering privileged action. |
| integration-management | Cross-tenant test on per-tenant credential/webhook config; webhook-log isolation test. |
| reporting-analytics | Cross-tenant test on any tenant-facing report; platform-level aggregate reports must have a test proving only platform-admin role can reach cross-tenant aggregation endpoints. |
| audit-log-management | Audit-row-written test (exactly one row, correct `actor_id`/`tenant_id`/action/target) for every privileged action; append-only enforcement test (no update/delete endpoint reachable); cross-tenant test proving Tenant A admin cannot list/search Tenant B's audit log. |
| support-management | Cross-tenant test on ticket creation/read/assignment; internal-notes visibility test (not exposed to the requesting student/teacher role). |

Note: "device-authentication" and "video/session protection" are sub-features of
`identity-access-service` and `video-access-management` respectively, per the
confirmed domain list — they are not separate top-level domains, but each still
requires its own row of targeted tests in addition to that domain's general
cross-tenant floor.

## Open Questions

1. Whether `course-management` or a not-yet-named domain owns the Review &
   Testimonials (Module 19) submission/moderation *workflow* versus just the
   course-level toggle — the source does not clearly separate "review data belongs to
   the course" from "review moderation is its own concern." Recommend confirming
   whether this stays inside `course-management` or should be called out as owned by
   `support-management` (moderation queues) with `course-management` only storing the
   toggle/published state.
2. Whether `finance-expense-management` and `ledger-settlement-management` should
   remain two domains or whether the tutor-payout linkage between them is thin enough
   to fold into one — flagged only as a documentation question, not a proposal to
   merge domains (which per `.claude/rules/architecture.md` would need to go through the
   confirmed-domain-list change process, not be decided in this catalog).
3. Confirm the three cross-cutting gaps above (Modules C, D, F) with the architecture
   owner before backend module scaffolding begins, since `.claude/rules/architecture.md`
   states new top-level domains must not be invented ad hoc — these should be resolved
   as "composed from existing domains" or escalated as a domain-list change request,
   not left ambiguous into implementation.

## Related

- `docs/architecture/modular-monolith.md`
- `docs/requirements/functional-requirements.md`
