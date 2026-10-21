# Klass course management — implemented workflows

Source spec: `docs/specs/Klass_Course_Management.md` (IDs **CM-**). Shared decisions, schema and the
integrated task table: `docs/plans/klass-enrollment-payment-parity.md`. Free-enrollment/payment
behaviour (catalog, enrollment, composer, entitlements): `docs/specs/klass-enrollment-payment-workflows.md`.
This file describes what the code does, not Klass internals.

## 1. Model mapping

| Klass term | Our entity | Notes |
|---|---|---|
| Course | `course` | `pricing_model`, `price`, `registration_enabled`, `archived_at`, `display_order`, cover, tags, teachers, links (V85) |
| Module | `course_module` | `sequence` (persisted order), optional `price` (powers MODULE offers) |
| Session | `course_lesson` | `starts_at`, `ends_at`, `resource_type`, `payment_type`, `price`, `description`, `lesson_label`, `billing_year`/`billing_month` (V85) |
| Month folder | projection | `billing_year/billing_month` on the lesson; never parsed from titles/dates |
| Viewed | `lesson_progress` | first successful authorized launch (V86) |

Existing ids, materials (`material.lesson_id`), class sessions (`class_session.lesson_id`), video
assets, enrollments, orders, payments and ledger rows are preserved; V85 backfills only the primary
teacher assignment and `display_order`.

## 2. Admin (Tenant Admin / Course Coordinator; Teacher on owned courses)

- **Courses** `/tenant-admin/courses`: ACTIVE/ARCHIVED tabs (`GET /api/v1/courses?archived=&search=`),
  server search on title and primary teacher name, columns #, Name, Teacher, Payment Type, Price (LKR or
  N/A), Enabled for Registration switch (`PATCH /api/v1/courses/{id}/registration`, audited; blocks new
  enrollment only), actions Modules / Edit (course overview) / Archive / Delete (409 `COURSE_IN_USE` when
  referenced). Reorder by drag or keyboard (`PUT /api/v1/courses/order`, subset allowed, Active tab only).
- **Add New Course** modal — see the enrollment/payment workflows doc §2 (fields from EP §3A).
- **Modules** `/tenant-admin/courses/{id}/modules`: "Modules of <course>", breadcrumb, search, Add Module
  modal (Name*, optional Price), reorder (`PUT .../modules/order`), Sessions action, delete 409
  `MODULE_IN_USE` when orders/entitlements reference it. The former module/lesson/material editor moved
  to `/tenant-admin/courses/{id}/content` (**Modules & sessions** workspace tab). The Teacher equivalent
  remains `/teacher/courses/{id}/modules`. Both now reuse the full Session form, preserving availability,
  resource/payment metadata and timestamp precision. Module rename preserves price;
  module/session reordering uses the dedicated atomic `PUT .../order` endpoints.
- **Sessions** `/tenant-admin/courses/{id}/modules/{moduleId}/sessions`: columns #, Name, Type, From, To,
  Payment Type, Price, Expired?; Add/Edit Session modal: Name*, From Date*/Time* on create, editable nullable
  From/To on existing sessions, Resource Type*, Payment Type*, Price (Paid only), and SHOW/HIDE ADDITIONAL
  SETTINGS → Session, Description. Billing month is not an input: Monthly/Covered sessions derive it server-side
  from the start instant in tenant timezone. Date/time is entered in the tenant
  timezone and stored as instants; a To Date without time means 23:59. Untouched timestamp precision and
  all payment/resource metadata survive rename/reorder. Server codes: `ENDS_BEFORE_START`,
  `PRICE_REQUIRED_FOR_PAID`, `PRICE_NOT_ALLOWED`, `RESOURCE_TYPE_UNSUPPORTED`; delete 409 `LESSON_IN_USE`.
  The separate course workspace `/sessions` route is labelled **Live classes** to avoid confusing provider
  meetings with course-content sessions.
- **Session resources:** Document, Image and Player Video each open a centered, focus-trapped, server-paginated
  popup locked to the selected kind/status. A compact attachment list sits below Resource Type; the old
  duplicate Session resources panel is gone. One session supports multiple/mixed files, images, videos and links.
  Selections remain drafts until Save;
  Cancel sends no attachment request. Metadata is saved first; if additions partly fail, completed additions
  remain, the saved session id is retained, server lists are reconciled, and retry sends only outstanding work.
  Existing attachments remain on type changes. Explicit removal detaches only the session reference, never the
  library asset; saved link URLs are read-only, so replacement is add-new then explicitly remove-old.
- **Resource types** (`GET /api/v1/courses/lesson-resource-types`, `LessonResourceTypeCatalog`):
  INTEGRATED — Document, Image and Player Video (our secure player; Klass "Vidrora Video"); LINK_ONLY — Vimeo
  Video, Zoom Link, Zoom Webinar, Video Link, Google Drive Link, MS Teams Link and Google Meet, which expose
  an HTTPS link input and create ordinary LINK materials. Zoom Meeting remains metadata-only in this editor:
  Zoom Integration Name, Mark Attendance When Joining and Require Registration appear disabled with an honest
  parked-integration explanation. Zoom Recording opens an unavailable-library explanation and remains rejected
  on save; upload a downloaded recording as Player Video instead.
- **Not built (no fake controls):** Quick Create, Import Modules/Sessions, QR/link session icons,
  "Today's courses first", people/overflow icons.

## 3. Student

- **Course cards / search** — enrollment/payment workflows doc §3.
- **Course detail** `/student/courses/{id}?tab=content|exams|live|tutes|about`: persistent header (cover,
  price badge, title, teacher); Exams = existing exam APIs filtered by course (membership still follows
  legacy paid enrollment, O-4); Live Classes = `GET /api/v1/courses/{id}/live-classes` (same evaluator as the
  tree); Tute Delivery = honest empty state; About = description as plain text + invite links (participants only).
- **Course content** = `GET /api/v1/courses/{id}/content`: "N of M viewed", billing-month folders newest
  first ("Course content" group last for lessons without a month), nested modules, lesson rows with
  Starts/Expires in the course timezone and a per-lesson action from the server access status. No URLs are
  returned. Multiple folders can stay open; expansion is preserved across dialogs.
- **Launch** `POST /api/v1/courses/{id}/lessons/{lessonId}/launch`: ALLOWED → records progress once and
  returns resource references; the client opens them through the existing (lesson-guarded) material,
  playback, player and class-session endpoints. When the tenant requires the native player and the lesson
  has video media, the "Web playback is turned off" modal offers Open in AOC Player and configured
  Android/iOS links (tenant VIDEO config `player_android_url`/`player_ios_url`, https only; "Not configured
  by your institute" otherwise).

## 4. Access rules (single evaluator `LessonAccessApi`)

Precedence: UNAVAILABLE → NOT_ENROLLED → DEVICE_NOT_AUTHORIZED → NOT_STARTED → EXPIRED → PAYMENT_PENDING /
PAYMENT_REQUIRED → ALLOWED. Payment coverage:
- **Free** session: no payment.
- **Covered by class fees**: legacy (pre-ADR-028) course access, a MONTHLY entitlement for the session's
  billing month, or a MODULE entitlement for its module; a FREE-priced course covers them.
- **Paid**: only its own LESSON entitlement.
- An ended session (`ends_at` past) is EXPIRED even if paid (fee period ≠ resource expiry).
Every protected path uses it: lesson launch, material list redaction/download, video playback and asset
access (shared assets require access to every non-free attachment), native player exchange/heartbeat,
class-session join and recording. Resources without a lesson keep the legacy course-wide rule.
Non-participant students get 404 for non-PUBLIC courses (no existence leak).

## 5. APIs

See `docs/plans/klass-enrollment-payment-parity.md` §4.1 (admin) and §4.3 (content/launch) for request/
response fields; implementation notes: course edit is the existing `PATCH /api/v1/courses/{id}` (partial
body allowed), teacher ids are user ids (`GET /v1/teachers` items carry `userId`), cross-tenant teacher id
→ 400, cross-tenant linked course → 404, self/nested link → 400 `COURSE_LINK_INVALID`.

## 6. Known limits

Import/Quick Create absent; Zoom recordings unsupported; Vimeo/Teams/Meet/Drive/Webinar are links only;
reorder of courses not audited; course clone does not copy tags/links/cover/commission/invite links;
the mobile app is not part of this repository.
