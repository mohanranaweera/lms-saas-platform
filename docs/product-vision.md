# Product Vision — Multi-Tenant SaaS LMS & Institute Management System

Status: Draft for review. Source: `docs/requirements/source-requirements.md`
(informal architecture memo), `CLAUDE.md`, `.claude/rules/*`.

## 1. Executive Summary

This product is a multi-tenant Software-as-a-Service platform that lets independent
tutoring institutes, coaching centers, and individual teacher-led academies run their
entire academic and business operation — courses, students, teachers, live classes,
exams, attendance, payments, and communication — from one branded, per-institute
instance of a shared system, without each institute needing its own servers, developers,
or IT staff.

Each purchasing organization ("tenant") gets an isolated, branded slice of the platform:
their own students, teachers, staff, courses, payments, and reports, never visible to or
reachable by any other tenant. The platform operator runs, bills, and supports all
tenants from a single shared, horizontally scalable application, per the modular-monolith
and shared-schema-with-tenant-isolation architecture fixed in `CLAUDE.md`.

## 2. Business Goals

1. **Enable non-technical institutes to launch a fully branded online academy** (own
   logo, colors, domain, student/teacher portals) without operating any infrastructure.
2. **Centralize payment collection safely** so the platform, not individual tenants, is
   the payment processor of record in the initial phase, with a clear, staged path to
   tenant/tutor settlement and eventually tenant-owned payment accounts — never skipping
   or reordering that sequence (see `.claude/rules/payments.md`).
3. **Protect paid video/content as a core differentiator**, not an afterthought — device
   authentication, session/view limits, watermarking, and signed-URL playback are treated
   as product features that justify the platform's pricing, not just internal security
   hygiene.
4. **Reduce institute administrative overhead** by replacing spreadsheets, manual
   WhatsApp/SMS reminders, and ad hoc payment-slip verification with automated,
   auditable workflows (attendance, exam management, payment-slip intelligence,
   notification automation).
5. **Grow platform revenue via SaaS plan tiers and feature gating** (student/teacher/staff
   caps, storage/bandwidth quotas, white-label and advanced-exam add-ons) rather than a
   flat per-tenant price, using a first-class feature-flag/plan-limit engine.
6. **Preserve trust and auditability** for money and access decisions — every payment
   approval, device reset, price change, and access extension must be traceable to a
   named actor and timestamp, permanently.

## 3. Target Users

- **Institute / Tenant Owners (Institute Admin)** — the paying customer. Runs one
  institute's entire operation: staff, courses, pricing, payments oversight, branding.
  Wants a professional-looking academy online with minimal manual admin work and
  confidence that student payments and access are handled correctly.
- **Teachers (and Teacher Assistants)** — deliver courses, hold live classes, set exams,
  mark attendance, and (optionally) earn commission/payout. Want a simple dashboard
  scoped to only the courses/students assigned to them.
- **Students** — the end learners. Want reliable access to the courses they paid for,
  a straightforward payment/enrollment experience (including manual bank-slip payment
  where card gateways aren't practical), and to be treated fairly if their access is
  restricted (device limits, expiry) with clear self-service reactivation.
- **Institute Staff sub-roles** (Finance Staff, Course Coordinator, Student Support,
  Content Manager, Exam Manager, Attendance Operator, Read-only Auditor) — operate
  narrow slices of the institute's back office without full admin rights.
- **Platform Operator (Platform Admin)** — runs the SaaS business itself: approves new
  tenants, manages subscription plans/feature limits, monitors platform health and
  revenue, handles cross-tenant support escalations, and is the only actor with any
  cross-tenant visibility (and only through auditable, clearly-scoped views/actions).

## 4. Value Proposition

- **One platform, many branded academies** — white-label branding and (later) custom
  domains make each tenant's site feel like their own product, not a shared template.
- **Payment-slip intelligence as a differentiator** — most reference LMS competitors
  treat manual bank-transfer payments as an unstructured "upload and hope"; this
  platform makes manual slip verification a first-class, audited, duplicate-checked
  workflow — important in markets where card-gateway payments aren't the default.
- **Content protection that matters to sellers, not just IT** — signed, short-lived,
  watermarked, device-and-session-bound video access materially reduces the "pay once,
  share the login" risk that undermines paid-course businesses.
- **Staged payment/settlement model reduces platform risk** — the platform holds funds
  and controls enrollment activation from day one, before ever handing tenants direct
  payment-account control, protecting both the platform operator and students from
  early-stage payment-integration mistakes.
- **A genuine institute back office, not just a course player** — attendance, exams,
  finance/expense tracking, and audit logs mean this competes with full institute
  management systems, not only course-delivery tools.

## 5. Competitive Framing

The reference product surveyed (see `docs/references/reference-lms-links.md`, a set of
vendor tutorial videos covering: student registration/login, institute setup, adding
and managing teachers, adding learning/course materials, managing student payments and
collecting payments, marking attendance, conducting online exams, and generating
financial reports) is organized around these feature categories: Course Management,
Teacher Management, Institute Registration, Student Management, Payment Management,
Attendance Management, Exam Management, Finance Management, Student Payments, Learning
Materials, Student Registration. `docs/references/reference-lms-links.md` is treated as
historical competitive-research input (specific tutorial URLs), not a living
specification — the feature categories above are what this document tracks against.

This platform matches every one of those categories (see
`docs/requirements/functional-requirements.md` for the full module breakdown mapped to
each) and extends beyond them in areas the reference categories do not name at all:

| Category the reference product covers | How this platform matches/extends it |
|---|---|
| Course Management | Matches, plus bundles, prerequisites, cloning, landing-page builder (Phase 2+). |
| Teacher Management | Matches, plus teacher assistant role, payout profile, performance analytics. |
| Institute Registration | Matches, plus a formal tenant approval workflow and lifecycle status (trial/active/suspended/cancelled). |
| Student Management | Matches, plus risk indicators, inactive-student detection, activity timeline. |
| Payment Management | Matches, plus a staged settlement/tenant-payment-account roadmap and split-payment readiness. |
| Attendance Management | Matches, plus QR/smart-card attendance and attendance-based access restrictions (later phase). |
| Exam Management | Matches, plus anti-cheating controls, rank lists, model paper library. |
| Finance Management | Matches, plus profit/loss, cashflow forecast, expense approval workflow. |
| Student Payments | Matches, plus the dedicated Payment Slip Intelligence module (OCR, duplicate reference/image-hash detection, audited manual override) — not named as a reference category at all. |
| Learning Materials | Matches, plus material versioning, document analytics, protected/watermarked downloads. |
| Student Registration | Matches, plus bulk import and guardian/parent data. |
| *(not a named reference category)* | Device authentication & account-sharing prevention, audit logging, support/helpdesk, feature-flag/plan-limit engine, notification automation engine, AI-assisted tooling (later phase) — all product areas this platform adds beyond the reference categories. |

## 6. Phased Roadmap (Business View)

This is a business-level summary; see `docs/requirements/functional-requirements.md`
for the full requirement-by-requirement phase breakdown and open questions.

- **MVP (Phase 1) — "Get an institute live and paid."**
  Multi-tenant foundation; student/teacher/admin login; tenant, student, teacher, and
  staff management; course and material management; centralized payments with manual
  bank-slip approval; enrollment/access activation tied to confirmed payment; basic
  video/session access; basic attendance; basic exams; email notifications; audit
  logging; Docker-based deployment.

- **Phase 2 — "Make the business side and content protection real."**
  Tutor/tenant settlements, commission calculation, finance dashboard; device
  authentication and view/session limits; Zoom, SMS, and WhatsApp integrations; course
  reviews; white-label branding and custom domains; advanced reporting.

- **Phase 3 — "Advanced protection, scale, and intelligence."**
  Tenant-specific payment accounts; secure external video storage, YouTube/Vimeo
  management; dynamic document/video watermarking; OCR-based smart payment-slip
  detection; mobile apps; smart-card attendance; advanced analytics; AI-assisted tools.

- **Phase 4 — "Payment flexibility ceiling."**
  Split payment / marketplace-style payouts, only if and when the selected payment
  gateway supports it — this is explicitly the last step of the payment roadmap per
  `CLAUDE.md` and must not be pulled forward ahead of tenant-specific payment accounts.

## 7. Out of Scope / Non-Goals (for now)

- Self-hosted video/streaming or conferencing infrastructure (explicitly ruled out by
  `.claude/rules/architecture.md` — external providers only).
- Microservices decomposition (explicitly ruled out absent an approved ADR).
- Any payment-gateway-specific integration decision at the vision stage — gateway choice
  is a business decision to be made separately, not assumed here.
- Any per-tenant dedicated deployment/infrastructure — this is one shared platform.

## Related

- `docs/requirements/functional-requirements.md`
- `docs/requirements/module-catalog.md`
- `docs/architecture/solution-architecture.md`
