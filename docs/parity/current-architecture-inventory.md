# Current Architecture Inventory (Wave 0)

Status: analysis only. No production code was modified to produce this document.

This is the factual baseline for the rest of the Wave 0 parity documents. It inspects the
actual repository state as of branch `parity/wave-00-parity-audit` (HEAD `9133bd7`) — it does
not assume a GitHub issue being closed means the underlying functionality is complete or
correct; every claim below is sourced from reading the actual backend package, migration,
frontend route, and RBAC code.

---

## 1. Confirmed backend domain list vs. actual packages

`.claude/rules/architecture.md` names 18 confirmed backend domains. Actual
`backend/src/main/java/com/lms/*` packages:

| # | Confirmed domain (architecture.md) | Java package present? | Notes |
|---|---|---|---|
| 1 | identity-access-service | `identityaccessservice` ✅ | Auth, Role/RoleCatalog, DomainArea/PermissionAction, PermissionCheckService |
| 2 | tenant-management | `tenantmanagement` ✅ | Tenant registration, Platform Admin tenant approval |
| 3 | user-management | `usermanagement` ✅ | `staff`, `student`, `teacher` sub-packages |
| 4 | course-management | `coursemanagement` ✅ | `course` sub-package: Course, Module, Lesson |
| 5 | content-management | `contentmanagement` ✅ | `material` sub-package only |
| 6 | video-access-management | ❌ **NOT IMPLEMENTED** | No package, no controller, no domain classes anywhere |
| 7 | live-class-management | ❌ **NOT IMPLEMENTED** | No package; no Zoom/meeting-provider code anywhere |
| 8 | enrollment-management | `enrollmentmanagement` ✅ | Enrollment, CourseAccessState, ReactivationRequest |
| 9 | payment-management | `paymentmanagement` ✅ | `order`, `payment`, `refund`, `slip` sub-packages |
| 10 | ledger-settlement-management | `ledgersettlementmanagement` ✅ (ledger only) | `LedgerController`/`PlatformAdminLedgerController` exist; no `Settlement`/`TeacherSettlement` entity or calculation service anywhere |
| 11 | attendance-management | `attendancemanagement` ✅ | Session-equivalent = `course_lesson.id` (see parity matrix PAR-10) |
| 12 | exam-management | `exammanagement` ✅ | Question bank, exams, attempts, marking queue, results |
| 13 | finance-expense-management | ❌ **NOT IMPLEMENTED** | No package; no `Expense`/`ExpenseCategory` entity anywhere |
| 14 | notification-management | `notificationmanagement` ✅ | Email + in-app + outbox only; no SMS/WhatsApp |
| 15 | integration-management | `integrationmanagement` ✅ (partial) | `gateway` (payment webhook), `mail`, `storage`; no Zoom/SMS/WhatsApp/YouTube-Vimeo adapters |
| 16 | reporting-analytics | ❌ **NOT IMPLEMENTED** | No package; only ad hoc dashboard queries inside owning domains (e.g. `PlatformAdminLedgerController`) |
| 17 | audit-log-management | `auditlogmanagement` ✅ | Event-consumer pattern, append-only |
| 18 | support-management | ❌ **NOT IMPLEMENTED** | No package; "Support tickets" exists only as a `DomainArea` enum value and a permission-matrix row |

**5 of 18 confirmed domains have zero backend implementation**: `video-access-management`,
`live-class-management`, `finance-expense-management`, `reporting-analytics`,
`support-management`.

`com.lms.common` is the shared kernel (`api`, `config`, `error`, `persistence`, `tenant`,
`web`) — tenant-context resolution, the global exception handler, and shared persistence base
classes live here, consistent with `.claude/rules/tenancy.md`'s "resolved once, at the edge"
model.

## 2. REST controller inventory (all 31 controllers found)

| Controller | Base path | Domain |
|---|---|---|
| `AuthController` | `/api/v1/auth` | identity-access-service |
| `PlatformAdminAuthController` | `/api/v1/platform-admin/auth` | identity-access-service |
| `RoleCatalogController` | `/api/v1/roles` | identity-access-service |
| `TenantRegistrationController` | `/api/v1/tenant-registrations` | tenant-management |
| `PlatformAdminTenantController` | `/api/v1/platform-admin/tenants` | tenant-management |
| `StaffController` | `/api/v1/staff` | user-management |
| `StudentController` | `/api/v1/students` | user-management |
| `TeacherController` | `/api/v1/teachers` | user-management |
| `CourseController` | `/api/v1/courses` | course-management |
| `CourseModuleController` | `/api/v1/courses/{courseId}/modules` | course-management |
| `CourseLessonController` | `/api/v1/courses/{courseId}/modules/{moduleId}/lessons` | course-management |
| `CoursePublicController` | `/api/v1/public/courses` | course-management (storefront) |
| `MaterialController` | `/api/v1/courses/{courseId}/modules/{moduleId}/lessons/{lessonId}/materials` | content-management |
| `EnrollmentController` | `/api/v1/enrollments` | enrollment-management |
| `CourseAccessStateController` | `/api/v1/courses/{courseId}/...access-state` | enrollment-management |
| `ReactivationRequestController` | (nested under enrollments) | enrollment-management |
| `OrderController` | `/api/v1/orders` | payment-management |
| `PaymentController` | `/api/v1/payments` | payment-management |
| `RefundController` | `/api/v1/payments/{id}/refunds` (nested) | payment-management |
| `SlipController` | `/api/v1/payment-slips` | payment-management |
| `SlipReviewController` | `/api/v1/payment-slips` (review actions) | payment-management |
| `PaymentWebhookController` | `/api/v1/integrations/webhooks` | integration-management |
| `LedgerController` | `/api/v1/ledger` | ledger-settlement-management |
| `PlatformAdminLedgerController` | `/api/v1/platform-admin/payments` | ledger-settlement-management |
| `AttendanceController` | `/api/v1/attendance` | attendance-management |
| `ExamController` | `/api/v1/exams` | exam-management |
| `QuestionBankController` | `/api/v1/exams` (question-bank sub-paths) | exam-management |
| `ExamAttemptController` | `/api/v1/exams` (attempt sub-paths) | exam-management |
| `MarkingQueueController` | `/api/v1/exams` (marking sub-paths) | exam-management |
| `ResultsController` | `/api/v1/exams` (results sub-paths) | exam-management |
| `NotificationController` | `/api/v1/notifications` | notification-management |
| `AuditLogController` | `/api/v1/audit-log` | audit-log-management |
| `PlatformAdminAuditLogController` | `/api/v1/platform-admin/audit-log` | audit-log-management |

No controller exists for: white-labelling/branding, custom domains, device management, video
playback, Zoom/live-class scheduling, SMS/WhatsApp, finance/expenses, settlements,
duplicate-slip-override reporting UI, course reviews, tenant configuration of any kind, or
reporting/analytics read models. See `api-impact-analysis.md` for the full endpoint-level gap
list.

## 3. Frontend route inventory (Next.js App Router)

Route groups present: `(auth)`, `(platform-admin)`, `(public)`, `(student)`, `(teacher)`,
`(tenant-admin)`.

**Platform Admin** (`app/(platform-admin)/platform-admin/`): `login`, `(dashboard)/dashboard`,
`(dashboard)/tenants`, `(dashboard)/payments`, `(dashboard)/audit-log`.

**Public** (`app/(public)/`): `courses`, `courses/[slug]`, `register-institute`.

**Student** (`app/(student)/student/`): `dashboard`, `courses`, `courses/[courseId]`,
`checkout/[courseId]`, `payments`, `payments/history`, `payments/awaiting-confirmation`,
`payments/slip-upload`, `payments/reactivation`, `attendance`, `exams`, `exams/[examId]`,
`notifications`, `profile`.

**Teacher** (`app/(teacher)/teacher/`): `dashboard`, `courses`, `courses/[courseId]`,
`courses/new`, `attendance/mark`, `attendance/reports`, `exams`, `exams/[examId]`,
`exams/marking`, `exams/questions`, `notifications`.

**Tenant Admin** (`app/(tenant-admin)/tenant-admin/`): `dashboard`, `students`,
`students/[studentId]`, `teachers`, `teachers/[teacherId]`, `teachers/new`, `courses`,
`courses/[courseId]`, `payments/dashboard`, `payments/refunds`, `payments/slip-review`,
`access-expiry/reactivation-approvals`, `attendance/mark`, `attendance/reports`, `exams`,
`audit-log`.

**Not present anywhere in the frontend**, despite `KLASS-PARITY-MASTER-INSTRUCTION.md` §6
requiring them: `tenant-admin/staff`, `tenant-admin/roles-permissions`,
`tenant-admin/materials`, `tenant-admin/finance/*`, `tenant-admin/settings/*` (Institute
Configuration — General/Branding/Academic/Student/Teacher/Course/Payment/Finance/
Attendance/Exam/Content/Video/Notifications/Integrations/Security/Domain), any
Communication screens (templates/bulk messaging/delivery logs), `tenant-admin/devices`, any
Live Classes screen, any Video/Materials-oversight screen distinct from course detail, and any
Course Reviews moderation screen.

`TenantAdminNav` (`frontend/src/components/layout/nav/tenant-admin-nav.tsx`) confirms this: it
renders a **flat** list (Dashboard, Students, [Teachers], Courses, **Profile**, **Settings**,
[Payments], [Refunds], [Payment Slips], [Reactivation Approvals], [Attendance Reports], [Mark
Attendance], [Exams], [Audit Log]) with no grouping into Dashboard/Academic/Finance/
Communication/Administration/Institute Configuration as required by §6. **"Profile" and
"Settings" nav items have no `href` at all** — they are literal dead-end placeholders,
violating §34 ("Do not create navigation items whose underlying workflows are not
implemented"). This is flagged as WRONG_UX_IA / MISSING_SCREEN in the parity matrix (PAR-TA-NAV).

Role-conditional item visibility (`canViewTeachers`, `canViewPaymentDashboard`, etc., from
`@/lib/auth/permissions`) is genuinely backend-permission-driven (each destination page
independently renders a `PermissionDeniedState` from a real 403), matching the
frontend/security rules' "hidden nav is UX convenience, not enforcement" requirement — this
part is a MATCH, not a gap.

## 4. RBAC data model vs. target permission matrix

`Role` enum (`identityaccessservice/domain/Role.java`): `TENANT_ADMIN`, `FINANCE_STAFF`,
`COURSE_COORDINATOR`, `STUDENT_SUPPORT`, `CONTENT_MANAGER`, `EXAM_MANAGER`,
`ATTENDANCE_OPERATOR`, `READ_ONLY_AUDITOR`, `TEACHER`, `TEACHER_ASSISTANT`, `STUDENT` — this is
an **exact match** to `docs/requirements/user-roles-and-permissions.md` §1's role list (plus
`PlatformAdminUser` modeled as a separate table/implicit role, also matching spec).

`DomainArea` enum: `STUDENTS, TEACHERS, STAFF_AND_ROLES, COURSES, MATERIALS, PAYMENTS_SLIPS,
FINANCE_EXPENSES, ATTENDANCE, EXAMS, DEVICES, ACCESS_EXPIRY, REVIEWS_MODERATION, AUDIT_LOG,
BRANDING_SETTINGS, SUPPORT_TICKETS` — **exact match** to all 15 rows of the target permission
matrix, including domain areas whose actual feature (Devices, Branding & Settings, Support
Tickets, Reviews Moderation, Finance & Expenses) has **no backend module, no controller, and no
frontend screen yet**. The RBAC skeleton is ahead of the features it will eventually gate — see
`rbac-impact-analysis.md`.

`PermissionAction` enum: `VIEW, CREATE_EDIT, DELETE, APPROVE` — exact match to the matrix's
V/C-E/D/A columns.

**This is a MATCHES finding for the RBAC data model itself.** The gap is not the permission
model — it is that most `DomainArea` values have no enforced feature behind them yet.

## 5. Database migration summary

35 Flyway migrations (`V1`–`V35`), additive only (see `database-impact-analysis.md` and
`migration-strategy.md` for the full table-by-table breakdown and risk analysis). No evidence
of an already-applied migration being edited in place — later migrations (`V32`–`V34`) instead
correct earlier ones additively (`V32` relaxes a constraint, `V33` restores it, `V34` tightens
it further), which is the correct additive-only pattern, not a rewrite.

## 6. Documentation set already in the repository

This repository already contains an unusually complete requirements/architecture baseline that
Wave 0 treats as source-of-truth precedence level 2 (`docs/requirements/**`) and level 3
(`docs/adr/**`):

- `docs/requirements/specifications/01-tenant-onboarding.md` through `28-wordpress-migration.md`
  — 28 files, one per Klass parity domain, each already annotated with MVP-vs-Phase-2/3
  classification, shipped-vs-not status, and explicit Open Decisions. These digests are the
  primary source for the "target behavior" column of `klass-parity-matrix.md`.
- `docs/requirements/user-roles-and-permissions.md` — the role/permission matrix (§4 above).
- `docs/requirements/open-decisions.md` — consolidated open-decision tracker (referenced
  per-domain in the spec files; not duplicated here).
- `docs/adr/ADR-001` through `ADR-014` — architecture decisions already Accepted, including
  ADR-006 (tenant isolation mechanism), ADR-007 (device auth mechanism — Accepted but **not yet
  implemented**), ADR-008 (video protection mechanism — Accepted but **not yet implemented**),
  ADR-009 (role/permission data model — implemented, see §4), ADR-013 (enrollment
  lineage/reactivation order gate — implemented).
- `docs/plans/MVP-001` through `MVP-021` — the as-built module plans; module numbering in the
  spec files (e.g. "MVP-016 Attendance", "MVP-012" enrollment) cross-references these directly.

Because this documentation set already encodes deep, code-verified knowledge of the current
implementation (including exact file/table names and known limitations), `klass-parity-matrix.md`
treats it as authoritative for "target behavior" and "known gap," and this Wave 0 pass adds the
classification/remediation/wave columns plus the direct code inspection (controllers, routes,
migrations, RBAC enums) recorded in this file.

## 7. Test suite footprint (not modified, inspected only)

Backend: JUnit + Testcontainers integration tests exist per shipped domain, including explicit
cross-tenant negative tests (naming convention `*CrossTenant*Test`) for course, payment, slip,
enrollment, attendance, exam, and audit-log endpoints, plus `PlatformAdminLedgerController`
cross-tenant coverage per commit `8643de1`. Frontend: Playwright specs exist for the shipped
student/teacher/tenant-admin flows, including a real-backend two-tenant cross-tenant spike
(commit `48cc6d4`). No test file was modified during this Wave 0 pass. A full test-coverage
gap analysis per parity item is out of scope for Wave 0 and is instead a required field
("tests required") on each future wave's implementation plan per §40 of the master instruction.
