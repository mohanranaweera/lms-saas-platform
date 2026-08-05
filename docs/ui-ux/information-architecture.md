# Information Architecture

Status: Draft
Related rules: `.claude/rules/frontend.md`, `.claude/rules/ui-ux.md`
Related requirements: `docs/requirements/source-requirements.md` (module list, section 6 "Final module architecture direction")

## Purpose

This document defines the top-level navigation structure for each portal, mapped to the
Next.js App Router route groups defined in `frontend.md`:

- `app/(student)/`
- `app/(teacher)/`
- `app/(tenant-admin)/`
- `app/(platform-admin)/`
- `app/(public)/`

Each route group is a distinct IA tree. No navigation element may cross route-group
boundaries (e.g. Tenant Admin nav never links into `app/(platform-admin)/`). Where a
capability is genuinely shared (e.g. "my profile"), it is implemented as a shared
component reused inside each role's own route group — not as a cross-group link.

Every nav item listed here must resolve its visibility from the authenticated user's
backend-issued role/permission set (session/profile payload), not from a client-stored
role string alone, per `frontend.md`'s permission-denied rule.

---

## 1. `app/(public)/` — Public Tenant Storefront

Audience: anonymous visitors, prospective students, tenant's public marketing surface.
Source: module 2 (White-Labeling & Branding — tenant-specific public course pages,
tenant-specific login page) and module 19/C (Public Website / Course Storefront).

Tenant identity for this route group is resolved from the request's subdomain/custom
domain, never from client state.

Primary navigation (per-tenant, branding-driven):

- **Home** — tenant homepage (hero, featured courses, teacher highlights)
- **Courses** — public course catalog/listing
  - Course detail page (curriculum preview, pricing, reviews, FAQ, enroll/payment CTA)
- **Teachers** — public teacher profile pages
- **Reviews** — aggregated/public course reviews (only reviews approved via moderation)
- **FAQ**
- **Login** — tenant-specific login page, branding resolved strictly from the matched
  tenant; falls back to neutral platform branding if tenant cannot be resolved
- **Register / Enroll** — student registration entry point, hands off into the
  authenticated Student flow after account creation

Notes:
- No cross-tenant navigation exists in this route group (no "browse other institutes").
- SEO fields (module 6 recommended addition) apply to course detail and homepage.
- Payment CTA on course detail page routes into the authenticated Student
  enrollment/payment flow — it never itself activates enrollment (see
  `user-journeys.md` §1).

---

## 2. `app/(student)/` — Student Portal

Audience: enrolled/prospective students. Mobile-first (consumer-style surface per
`ui-ux.md` §5).

Primary navigation:

- **Dashboard** — enrollment summary, upcoming live classes, pending payments, alerts
  (expiring access, absence, exam results published)
- **My Courses** — enrolled courses (module 6, module 4)
  - Course workspace: Modules & Lessons, Materials, Video Player, Live Classes
- **Live Classes** — upcoming/past Zoom sessions (module 9)
- **Attendance** — personal attendance history (module 10)
- **Exams** — scheduled exams, results, answer review (module 11)
- **Payments** — payment history, outstanding balance, payment slip upload, receipts,
  reactivation (module 12/13)
- **My Devices** — registered devices, device history (module 17)
- **Reviews** — reviews the student has submitted; submit a review for a completed
  course (module 19)
- **Notifications** — in-app notification center (module 15)
- **Support** — student support tickets (module 4B / Support module)
- **Profile** — student profile, guardian/parent info, school/grade/stream fields
  (module 3)

Scope rule: every list here is backend-filtered to "this student's own records" only —
no student-selector or ID-based navigation to another student exists anywhere in this
route group (`ui-ux.md` §1).

---

## 3. `app/(teacher)/` — Teacher Portal

Audience: teachers and teacher assistants. Mobile-first, but content-authoring screens
(course/material editing) are usable at `md`+ with mobile fallback.

Primary navigation:

- **Dashboard** — assigned courses summary, upcoming live classes, pending marking,
  activity feed (module 4)
- **My Courses** — only backend-authorized assigned courses (module 6)
  - Course builder: Modules, Lessons, Sessions, Pricing, Enrollment Rules, Access
    Duration, Visibility, Prerequisites, Landing Page, SEO
  - Materials — upload/organize PDFs, images, notes, videos, Zoom recordings,
    YouTube/Vimeo links; visibility, expiry, view/download limits, watermarking
    (module 7)
  - Course Reviews — view reviews, respond to reviews (module 19)
- **Students / Roster** — students enrolled per assigned course (read scope only;
  never a platform-wide student directory)
- **Live Classes** — schedule/manage Zoom sessions, recordings (module 9)
- **Attendance** — mark/review attendance for assigned courses/sessions (module 10)
- **Exams** — question bank, exam creation/scheduling, manual marking, results
  publishing, exam analytics (module 11)
- **Reports** — own teaching performance/analytics (module 4 recommended addition)
- **Support** — teacher support tickets
- **Profile** — teacher profile, availability, payout profile (module 4)

Scope rule: course/roster data is fetched pre-filtered by the backend to this
teacher's assignments; the frontend must not fetch an unfiltered dataset and filter
client-side (`ui-ux.md` §1).

---

## 4. `app/(tenant-admin)/` — Tenant Admin Portal

Audience: Institute Owner and staff sub-roles (Finance Staff, Course Coordinator,
Student Support, Content Manager, Exam Manager, Attendance Operator, Read-only
Auditor — module 5). Admin-heavy surface (`ui-ux.md` §5): optimized for `md`+ with
defined mobile fallback.

No tenant selector/switcher exists anywhere in this route group — its absence
communicates single-tenant scope per `ui-ux.md` §1.

Primary navigation:

- **Dashboard** — tenant-wide KPIs (active students, revenue snapshot, pending
  approvals, alerts)
- **Students** — student management, bulk import, tags, risk indicators, inactive
  detection, timeline (module 3)
- **Teachers** — teacher management, approval, assigned courses, commission settings
  (module 4)
- **Staff** — staff accounts, role-based access/permission management, activity logs,
  password reset, staff count vs. plan limit (module 5)
- **Courses** — course catalog management, categories, bundles, cloning, archive
  (module 6)
- **Materials** — org-wide materials oversight, folder structure, bulk upload
  (module 7)
- **Live Classes** — Zoom account integration, scheduling oversight, recordings,
  cloud storage tracking (module 9)
- **Attendance** — course/teacher/student attendance reports, absent-student alerts
  (module 10)
- **Exams** — exam oversight, model paper library, rank lists (module 11)
- **Payments** — admin payment dashboard, manual payment slip queue/approval,
  Payment Slip Intelligence (duplicate/suspicious flags, override with audit trail),
  refunds, payment reports (module 12/13)
- **Finance & Expenses** — income/expense dashboards, accounts, tutor payouts,
  wallet transactions, financial reports (module 14; phase 2 settlement views)
- **Communications** — templates (email/SMS/WhatsApp), bulk messaging, segment
  messaging, delivery logs (module 15)
- **Integrations** — tenant-level Zoom/SMS/WhatsApp/email/payment gateway/storage
  settings, integration health, webhook logs (module 16)
- **Devices** — device policy overrides, device reset, login activity, suspicious
  login flags (module 17)
- **Access & Expiry** — expiry rules, grace periods, reactivation approvals
  (module 18)
- **Reviews** — moderation queue (approve/reject), public display toggle (module 19)
- **Reports & Analytics** — tenant-scoped reports across students/teachers/courses/
  payments/attendance/exams/finance (module 20)
- **Audit Log** — tenant-scoped audit trail (price changes, approvals, resets,
  deletions, impersonation) — read-only, filtered strictly to this tenant
- **Support** — tenant/student/teacher support tickets, ticket assignment, internal
  notes
- **Branding & White-Label** — logo, color theme, custom domain, tenant-specific
  login/public page branding, email/SMS templates, favicon, certificate/invoice
  branding, branding preview panel, theme presets (module 2)
- **Tenant Settings** — subscription plan, feature limits/usage, tenant profile,
  contact information (module 1)

Staff sub-role visibility: each nav item is shown/hidden per the sub-role's
permission set as a UX convenience only; the corresponding backend endpoints
independently enforce the same restriction (`ui-ux.md` §1).

---

## 5. `app/(platform-admin)/` — Platform Admin Portal

Audience: platform operations staff. Admin-heavy surface, `md`+ optimized with mobile
fallback.

Primary navigation:

- **Dashboard** — platform-wide KPIs (tenant count, revenue, churn risk, storage/
  bandwidth usage, pending tenant approvals, support ticket volume) (module 20)
- **Tenants** — tenant list (every row shows tenant name/identifier), registration
  approval workflow, tenant status (trial/active/suspended/cancelled), plan/feature
  limits, usage tracking (module 1)
  - Drilling into a single tenant's data renders a persistent, non-dismissible
    tenant-context banner naming that tenant for the duration of the drill-down
    (`ui-ux.md` §1)
- **Plans & Feature Flags** — SaaS plan definitions, feature/limit engine (max
  students/teachers/staff, storage/bandwidth quota, white-label/custom-domain/
  advanced-exam/SMS-WhatsApp toggles) (module 4D)
- **Payments & Settlements** — cross-tenant payment oversight, tutor/tenant
  settlement runs, commission/gateway-fee configuration, settlement exports
  (module 12 phase 2)
- **Finance** — platform-level financial reporting across tenants
- **Integrations** — platform-level default integration configuration, credential
  vault, health checks (module 16)
- **Reports & Analytics** — platform-wide analytics, cross-tenant reporting
  (module 20)
- **Audit Log** — platform-level audit log plus per-tenant audit log drill-down
  (never merged/ambiguous between tenants) (module 4A)
- **Support** — cross-tenant support ticket queue, escalations (module 4B)
- **Impersonation** — "view as tenant" sessions; entry point only, always rendered
  as a distinct, visually loud mode once active (see `design-system.md`)

Scope rule: every cross-tenant list/table shows the tenant name/identifier on every
row; no destructive/state-changing action is submittable without the target tenant
visibly named next to the action (`ui-ux.md` §1).

---

## Open questions

- Exact ordering/grouping of Tenant Admin nav items into a collapsed sidebar vs. a
  mega-menu is not yet decided — left as an implementation choice for
  `screen-map.md` consumers.
- Whether "Finance & Expenses" is a top-level Tenant Admin nav item or a tab under
  "Payments" is not yet decided; recommend a stakeholder decision before wireframing.
