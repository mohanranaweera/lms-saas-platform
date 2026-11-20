# Klass course management parity — plan

Spec: `docs/specs/Klass_Course_Management.md` (IDs prefixed **CM-**). Implemented together with
`docs/specs/Klass_Enrollment_Payment.md` (**EP-**) as one change. **Decisions, schema/API contracts,
file ownership and the integrated task table live in
`docs/plans/klass-enrollment-payment-parity.md` (§2–§6)** so shared work is tracked once; this file
records CM-specific scope, evidence limits and repository mapping.

## Evidence and references

Reference screenshots (read via the parent's image-capable Read tool; reviewers get the notes below):
- `docs/references/Klass Course Backend Management UI/` — 10 images (course table, modules, add module,
  sessions table, add session form, resource type lists, payment types, validation, additional settings).
- `docs/references/Klass Course Front End UI/` — 13 images (cards, search, header/month folders, exams,
  live classes, tute delivery, expanded month, unpaid sessions, payment choice, gateway, slip form,
  paid recording, external player).
- No course-creation or enrollment/payment-composer images exist; EP §3A/§7 are implemented from the
  written spec only (visual verification limit recorded in the QA docs).

Layout notes extracted for text-only reviewers:
- Admin course table: tabs ACTIVE/ARCHIVED; search left; QUICK CREATE + ADD COURSE right; columns
  drag, #, Name, Teacher, Payment Type, Price (LKR) right-aligned "4000.00" / "N/A", Enabled for
  Registration switch, Actions (modules list icon, people, edit pencil, red bin, overflow).
- Add New Session modal: Name*; From Date*/From Time* row; To Date/To Time row; Resource Type*;
  Payment Type*; "SHOW ADDITIONAL SETTINGS" link (Lesson, Description); CANCEL / CREATE SESSION.
- Student content tree: "Course content" header with "N of M viewed" + progress bar; month folder
  rows ("2026 October") with red "Pay now" pill or green "Paid"; nested module folders; lesson rows
  with empty circle / green check, resource icon, title, green "Starts …", red "Expires …", Pay now.

## Repository mapping (observed concept → existing code → change)

| Concept | Existing | Change |
|---|---|---|
| Course list/archive | `CourseController` (`/archive`, `/unarchive`, `includeArchived`), `tenant-admin/courses/page.tsx` (client-side search) | Active/Archived tabs, server search, registration switch, price/payment type columns, persisted display order |
| Module | `CourseModule` + `CourseModuleController`, `modules/page.tsx` | Add-module modal, price, reorder endpoint, breadcrumbs, guarded delete |
| Session (leaf) | `CourseLesson` (title, sequence only) | V85 fields, Sessions page per module with the recorded table/form |
| Billing month | none (`course_billing_period` is price history) | server-derived `billing_year/billing_month` on Monthly/Covered lesson from tenant-local start month; month folders are a projection |
| Viewed | `video_watch_progress` only | `lesson_progress` on authorized launch |
| Live Classes tab | `student/live-classes` page, `ClassSession.lessonId` | course-scoped live list from the same lesson data/evaluator |
| Exams tab | `student/exams` + exam APIs filtered by course | reuse existing APIs in the tab |
| External player | ADR-025 launch codes, `TenantVideoConfiguration.nativePlayerRequired` | modal with configured links; unavailable state when not configured |

## Stages and status (CM view of the integrated table)

| Stage (spec §12) | Integrated tasks | Status |
|---|---|---|
| 1 Discovery + gap matrix | T1 | done |
| 2 Model/migration + shared access/payment logic | T2, T3, T4a, T4b | done |
| 3 Admin courses/modules/sessions UI + CRUD | T4a, T5a | done |
| 4 Student cards/tabs/content projection/progress | T4b, T5b | done |
| 5 Payment modal with online/manual workflows | T4c, T5b | done |
| 6 Resource launch + external-player handoff | T4b, T5b | done |
| 7 Tests, browser walkthrough, screenshots, docs | T6–T9 | done |
| 8 Course-edit session/resource completion (2026-10-17) | Frontend-only: shared full Session form, availability preservation/clearing, atomic reorder, mixed paginated resource selection, gated Zoom surfaces | done |
| 9 Session resource refinement | V89 IMAGE, Player Video label, compact attachments, centered popups, server-derived Monthly association, student visibility verification | implemented; final review |

## Existing data preservation (CM-A06)

Additive migrations only (V85–V87). Course/module/lesson ids, materials, class sessions, enrollments,
orders, payments, slips, ledger and audit rows are not rewritten or deleted. Lessons get
`payment_type = COVERED_BY_CLASS_FEES` and no billing month (nothing inferred from titles/dates); the
QA doc lists the count of lessons without a billing month for admin review.

Stage 8 adds no migration or contract change. It removes the remaining title-only course-content edit path:
Teacher and Tenant Admin now share the full Session form, preserve existing schedule/payment/resource fields,
use atomic order endpoints, and add files/videos/links through existing APIs. Zoom configuration/recording import
stays parked and is presented as unavailable rather than persisted as ineffective configuration.
