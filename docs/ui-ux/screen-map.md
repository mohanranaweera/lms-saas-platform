# Screen Map

Status: Draft
Related: `docs/ui-ux/information-architecture.md`, `docs/requirements/source-requirements.md`

## Purpose

Enumerates every concrete screen/page required per portal. Format:

> Portal > Section > Screen name — one-line purpose

Every screen listed here is subject to the baseline requirement (loading, empty,
error, permission-denied where applicable, responsive behavior, accessible form
labels) defined in `frontend/CLAUDE.md` and detailed in `.claude/rules/ui-ux.md`.

---

## Public Storefront (`app/(public)/`)

- Public > Storefront > Tenant Homepage — branded landing page with featured courses/teachers
- Public > Storefront > Course Listing — filterable public catalog of published courses
- Public > Storefront > Course Detail — curriculum, pricing, reviews, FAQ, enroll CTA
- Public > Storefront > Teacher Profile — public teacher bio, assigned public courses
- Public > Storefront > Reviews — approved public reviews list for a course
- Public > Storefront > FAQ — tenant FAQ content

## Authentication (`app/(auth)/`)

A separate route group from the public storefront, per `.claude/rules/frontend.md`'s
"group by role/audience" convention — auth pages need a focused form shell without
storefront/marketing chrome. As shipped in the Application Foundation module, these are
disabled placeholder screens ("Not yet implemented — pending identity-access-service");
real submit behavior lands with that module.

- Public > Auth > Tenant Login — tenant-branded login, resolved by domain/subdomain
- Public > Auth > Student Registration — new student sign-up
- Public > Auth > Forgot Password — password reset request flow

## Student Portal (`app/(student)/`)

- Student > Dashboard > Overview — enrollment/payment summary + expired-access alerts
  (MVP-013). Attendance/exam summary tiles are a future addition once
  `attendance-management`/`exam-management` ship — not present today.
- Student > Courses > My Courses — list of enrolled courses
- Student > Courses > Course Workspace — modules/lessons navigation for one course
- Student > Courses > Lesson/Material View — PDF/notes/image viewer with watermark, expiry/view-limit enforcement
- Student > Courses > Video Player — secure signed-URL playback, watch-time tracking, watermark
- Student > Courses > Catalog / Browse More Courses — authenticated catalog to enroll in additional courses
- Student > Live Classes > Upcoming/Past Live Classes — Zoom join links, recordings
- Student > Attendance > My Attendance — session-by-session attendance history
- Student > Exams > Exam List — scheduled/available exams
- Student > Exams > Exam Taking — timed exam attempt screen
- Student > Exams > Results & Review — published results, answer review
- Student > Payments > Payment History — all past payments/receipts
- Student > Payments > Outstanding Payments — amounts due, upcoming reminders
- Student > Payments > Payment Slip Upload — manual slip upload form
- Student > Payments > Reactivation — reactivate expired course access
- Student > Devices > My Devices — registered device list, device history
- Student > Reviews > My Reviews — reviews submitted; submit new review (verified-enrollment only)
- Student > Notifications > Notification Center — in-app notifications, preference center
- Student > Support > My Tickets — ticket list, ticket detail, new ticket
- Student > Profile > Profile & Guardian Info — student profile, guardian/parent fields, school/grade/stream

## Teacher Portal (`app/(teacher)/`)

- Teacher > Dashboard > Overview — assigned courses, pending marking, upcoming classes
- Teacher > Courses > My Courses — assigned courses list
- Teacher > Courses > Course Builder — create/edit course metadata, pricing, enrollment rules, access duration, visibility, prerequisites
- Teacher > Courses > Module & Lesson Editor — course modules/lessons structure
- Teacher > Courses > Materials Manager — upload/organize PDFs, images, videos, recordings; drag-and-drop ordering; visibility/expiry/limits/watermark settings
- Teacher > Courses > Landing Page & SEO — course landing page builder, SEO fields
- Teacher > Courses > Course Reviews — view/respond to reviews
- Teacher > Roster > Course Roster — students enrolled in an assigned course
- Teacher > Live Classes > Schedule Live Class — create Zoom session
- Teacher > Live Classes > Recordings — manage/attach recordings to lessons
- Teacher > Attendance > Mark Attendance — manual attendance marking per session
- Teacher > Attendance > Attendance Reports — per-course/teacher attendance report
- Teacher > Exams > Question Bank — MCQ/structured question management
- Teacher > Exams > Exam Scheduler — create/schedule exams, time limits, attempt limits
- Teacher > Exams > Marking Queue — manual marking of structured answers
- Teacher > Exams > Results Publishing — publish results, rank lists
- Teacher > Reports > My Performance — teacher performance analytics
- Teacher > Support > My Tickets — teacher support tickets
- Teacher > Profile > Profile & Availability — teacher profile, availability, payout profile

## Tenant Admin Portal (`app/(tenant-admin)/`)

- Tenant Admin > Dashboard > Overview — tenant KPIs and alerts
- Tenant Admin > Students > Student List — search/filter/manage students
- Tenant Admin > Students > Student Detail — profile, enrollment/payment/attendance/exam/device/communication history, timeline
- Tenant Admin > Students > Bulk Import — CSV/bulk student creation
- Tenant Admin > Teachers > Teacher List — teacher management
- Tenant Admin > Teachers > Teacher Detail — profile, approval, assigned courses, commission settings
- Tenant Admin > Staff > Staff List — staff accounts, status
- Tenant Admin > Staff > Staff Detail / Role Editor — role-based permission assignment
- Tenant Admin > Staff > Activity Log — staff activity log
- Tenant Admin > Courses > Course List — all tenant courses, status, category
- Tenant Admin > Courses > Course Detail / Approval — oversight of teacher-authored courses
- Tenant Admin > Materials > Materials Oversight — folder structure, bulk upload management
- Tenant Admin > Live Classes > Zoom Accounts — tenant Zoom account integration, multiple accounts
- Tenant Admin > Live Classes > Live Class Oversight — scheduled/past sessions across teachers
- Tenant Admin > Attendance > Attendance Reports — student/course/teacher-level reports
- Tenant Admin > Exams > Exam Oversight — exam list across courses
- Tenant Admin > Exams > Model Paper Library — shared paper library
- Tenant Admin > Payments > Payment Dashboard — all payments, statuses
- Tenant Admin > Payments > Manual Slip Review Queue — pending slip approvals
- Tenant Admin > Payments > Slip Detail / Duplicate & Suspicious Flags — OCR reference, duplicate check results, override with reason (audit-logged)
- Tenant Admin > Payments > Refunds — refund issuance
- Tenant Admin > Payments > Payment Reports — exportable reports
- Tenant Admin > Finance > Income Dashboard — income overview
- Tenant Admin > Finance > Expense Dashboard — category/account-wise expenses
- Tenant Admin > Finance > Accounts — bank/cash account management
- Tenant Admin > Finance > Tutor Payouts — payout tracking (phase 2)
- Tenant Admin > Finance > Financial Reports — profit/loss, cashflow, exports
- Tenant Admin > Communications > Templates — email/SMS/WhatsApp template editor
- Tenant Admin > Communications > Bulk Messaging — segment messaging composer
- Tenant Admin > Communications > Delivery Logs — sent/failed message log
- Tenant Admin > Integrations > Integration Settings — Zoom/SMS/WhatsApp/email/payment gateway/storage config
- Tenant Admin > Integrations > Webhook & Health Logs — integration health checks
- Tenant Admin > Devices > Device Policy — tenant/course-level device limit overrides
- Tenant Admin > Devices > Device Reset & History — reset device, view login activity, suspicious login flags
- Tenant Admin > Access & Expiry > Expiry Rules — rules engine, grace periods
- Tenant Admin > Access & Expiry > Reactivation Approvals — approve reactivation requests
- Tenant Admin > Reviews > Moderation Queue — approve/reject reviews
- Tenant Admin > Reports > Reports & Analytics — cross-module tenant reports
- Tenant Admin > Audit Log > Audit Log Viewer — tenant-scoped, read-only, immutable log
- Tenant Admin > Support > Ticket Queue — ticket list, assignment, internal notes
- Tenant Admin > Support > Ticket Detail — related payment/course/student links
- Tenant Admin > Branding > Branding Settings — logo, colors, favicon, custom domain, templates, certificate/invoice branding
- Tenant Admin > Branding > Branding Preview Panel — live preview through production theming pipeline
- Tenant Admin > Settings > Tenant Profile & Subscription — plan, feature limits, usage tracking

## Platform Admin Portal (`app/(platform-admin)/`)

- Platform Admin > Dashboard > Overview — platform-wide KPIs
- Platform Admin > Tenants > Tenant List — all tenants, status, plan
- Platform Admin > Tenants > Tenant Approval — review/approve/reject new tenant registration
- Platform Admin > Tenants > Tenant Detail — profile, plan, usage, status control (suspend/cancel/reactivate)
- Platform Admin > Plans > Plan & Feature Flag Editor — plan limits, feature toggles
- Platform Admin > Payments > Cross-Tenant Payment Dashboard — platform-wide payment oversight
- Platform Admin > Payments > Settlement Runs — tutor/tenant settlement calculation, status, export
- Platform Admin > Finance > Platform Finance Reports — platform-level financial reporting
- Platform Admin > Integrations > Platform Default Integrations — default Zoom/SMS/WhatsApp/email/gateway/storage config
- Platform Admin > Integrations > Credential Vault — API credential management
- Platform Admin > Reports > Platform Analytics — churn risk, most-watched courses, bandwidth/storage usage, device violations, ticket volume
- Platform Admin > Audit Log > Platform Audit Log — platform-scope actions
- Platform Admin > Audit Log > Tenant Audit Log Drill-down — per-tenant audit log, tenant-context banner required
- Platform Admin > Support > Cross-Tenant Ticket Queue — escalated/all-tenant tickets
- Platform Admin > Impersonation > Start/End Impersonation — enter/exit "view as tenant" mode

## Shared / Cross-Role Screens (implemented once, reused per role's route group)

- Shared > Auth > Login (role-specific entry, tenant-resolved branding)
- Shared > Auth > Forgot/Reset Password
- Shared > Notifications > Notification Preferences
- Shared > Error > 403 Permission-Denied Page
- Shared > Error > 404 Not Found
- Shared > Error > 500 / Unexpected Error

## Open questions

- Whether "Model Paper Library" and "Rank Lists" are Teacher-only, Tenant-Admin-only,
  or both is not resolved by source requirements — flagging for product decision.
- Mobile app screens (module 5/phase 3, "Mobile apps") are out of scope for this
  web IA and screen map; a separate mobile IA would be needed if pursued.
