# Klass enrollment & payment parity — plan, contracts and integrated status

Specs: `docs/specs/Klass_Enrollment_Payment.md` (IDs prefixed **EP-**) and
`docs/specs/Klass_Course_Management.md` (IDs prefixed **CM-**). Both specs re-use P01–P04/S01–S02
for different things, so every ID in these docs carries its prefix. This file holds the
**integrated** task table, contracts and decision log for both specs (the companion
`docs/plans/klass-course-management-parity.md` covers CM scope/evidence and points here for shared
status, so overlapping work is counted once). Business rule: ADR-028.

Branch: `feat/klass-course-enrollment-parity`. Migrations reserved: **V85, V86, V87** (V84 — the
parked Zoom foundation — is already in `main`'s history; not touched here).

## 1. Stage-1 analysis (agents: product-requirements-analyst, solution-architect, payment-ledger-specialist)

Key repository facts:
- `enrollment` = payment-activated course access (V19 exactly-one-source check, V22 one-current
  partial unique, ADR-013 lineage/expiry/reactivation, ADR-016 staff grant). Every content guard
  calls course-level `AccessPolicyApi.evaluateStudentCourseAccess`.
- `course_lesson` = (module, title, sequence) only; materials, `class_session.lesson_id`,
  video assets hang off it. `course_billing_period` is a dated **price** row, not a calendar month.
- `student_order` = one course, one amount; no lines/fees/discounts. One `PAYMENT_CONFIRMED`
  ledger entry per payment. PayHere verification per ADR-017.
- No tags, cover image, invitation links, registration flag, enrollment fee, co-teachers, linked
  courses, per-lesson progress, facility fee or bank-detail config existed.

## 2. Decision log

| ID | Decision | Source |
|---|---|---|
| D01 | Free enrollment = new `course_participation` table (one row per tenant+student+course, insert-on-conflict-nothing). Existing `enrollment` rows untouched and reinterpreted as **legacy course-wide paid access**; backfilled into participation. Paid activation paths also upsert participation. | ARCH Q1 |
| D02 | Paid coverage = new `content_entitlement` (scope MONTHLY / MODULE / LESSON), NOT NULL order line + exactly one of payment/slip evidence, unique per order line, partial-unique active coverage, revoke columns only. "Full Course" (ONE_TIME) keeps the existing order → enrollment path (no COURSE entitlement rows). | ARCH Q2, PAY §4 |
| D03 | Leaf = `course_lesson` ("Session" in admin UI). New columns: starts_at, ends_at, resource_type, payment_type (FREE / COVERED_BY_CLASS_FEES / PAID, default COVERED), price (PAID only), description, lesson_label, billing_year+billing_month (explicit association, never parsed from titles or dates). | ARCH Q3 |
| D04 | One evaluator `enrollmentmanagement.api.LessonAccessApi` — batch per course, statuses ALLOWED, NOT_ENROLLED, PAYMENT_REQUIRED, PAYMENT_PENDING, NOT_STARTED, EXPIRED, UNAVAILABLE, DEVICE_NOT_AUTHORIZED. Precedence: UNAVAILABLE → NOT_ENROLLED → DEVICE → NOT_STARTED/EXPIRED (lesson window) → payment. Used by content tree, live schedule, lesson launch, material download, video playback, player handoff, class-session join. | ARCH Q4 |
| D05 | Coverage: FREE lesson → no payment. COVERED lesson → legacy course access, OR MONTHLY entitlement for (course, lesson.billing_year/month), OR MODULE entitlement for its module; course pricing FREE ⇒ covered. COVERED lesson without billing month → legacy access only. PAID lesson → LESSON entitlement only (legacy access does not cover it). | ARCH Q2 |
| D06 | Orders gain immutable `student_order_line` + order snapshot columns (subtotal, facility fee, discount, fee policy). Composer orders are **single-course**, multi-line. Confirmation/approval branches on "order has lines": lines → `EntitlementGrantApi` (same transaction, not inside a swallowed catch); no lines → existing enrollment activation. One `PAYMENT_CONFIRMED` per payment; ADR-010 entry types unchanged. | PAY §2/§4 |
| D07 | Facility fee: tenant config `PAYMENT` keys `facility_fee_mode` (NONE / PER_LINE / PER_ORDER, default NONE) + `facility_fee_amount`; snapshotted per order and line. Fixture PER_LINE 100 → 3500×2 + 200 = 7200. Settlement calculation unchanged (open item O-1). | PAY §3 |
| D08 | Reversal policy unchanged: chargeback revokes the entitlements granted by that payment (plus legacy enrollment as today); staff refunds revoke nothing (as today). Free participation is never revoked by payment events. | payments.md §9, O-2 |
| D09 | Course payment types: Monthly Fee→MONTHLY, Full Course→ONE_TIME, Day payments→DAILY, Pay For Module→MODULE, Installments→INSTALLMENT, Free→FREE. DAILY/INSTALLMENT stored but have no offer engine → composer tabs show honest "No data available". SESSION/CUSTOM remain readable for existing courses, not offered on create. Module price (`course_module.price`) powers MODULE offers; lesson price powers LESSON offers; MONTHLY price = current open MONTHLY billing period amount; eligible months = distinct lesson billing months. | PRA §2.3, ARCH Q6 |
| D10 | Payment Valid Days = existing `course.access_duration_days`; entitlements snapshot `valid_until = granted_at + days` (null = no limit). Never changes lesson ends_at or participation. | ARCH Q6 |
| D11 | Institute Commission stored effective-dated (`course_commission_rate`), displayed; **not** fed into settlement (O-3). | PAY §3, payments.md §5 |
| D12 | Teachers: `course_teacher_assignment` (multi-select). `course.teacher_id` = primary (first selected), still the sole ownership authority for guards. | ARCH Q6 |
| D13 | Linked courses: `course_link` one level, same tenant, no self/cycle; parent tree shows source lessons with source ids; evaluator applies SOURCE course payment rules; "enrolled" satisfied by source participation OR parent participation + link. | ARCH Q6 |
| D14 | Viewed = successful authorized lesson launch (`POST .../launch` returning ALLOWED) → idempotent `lesson_progress` upsert. Expanding folders records nothing. | ARCH Q7 |
| D15 | `course.registration_enabled` (default true): blocks new participation only; never revokes existing participation or paid access. Archived/non-PUBLIC courses not available. | PRA §2.10 |
| D16 | Slip bank details + player app links: tenant config keys (`PAYMENT`: bank_name, bank_branch, bank_account_holder, bank_account_number; player: player_android_url, player_ios_url). No real account numbers anywhere in seeds. | PRA |
| D17 | Exams / attendance / roster / notification audiences keep their current (legacy paid-enrollment) membership (O-4). | ARCH Q1 |
| D18 | Discount codes: no backend support → accordion hidden, documented gap. Online method shown only when tenant has an ACTIVE gateway account (ADR-017); slip always available. | PRA §3 |
| D19 | ADR-015 $0 free checkout no longer used for enrollment; code/history kept. Student "Enroll" calls free participation. | PAY §5, PRA §2.2 |
| D20 | Not built (honest omissions, CM-U01): Quick Create, Import Modules/Sessions, QR/link session actions, "Today's courses first", people/overflow icons, tute delivery workflow, mobile app, Vimeo/Webinar/Vidrora providers, unenroll/re-enroll, cross-course carts. | PRA §3 |
| D21 | Re-purchase after `valid_until`: the active-coverage unique index cannot see time, so a grant that collides with an expired active row first sets `revoked_at` + `revoked_reason=EXPIRED_SUPERSEDED` on it (revoke columns only; no delete). Quotes treat expired coverage as AVAILABLE. ZOOM_RECORDING (catalog UNSUPPORTED) is rejected on lesson create/update and disabled in the UI. | T2, T3 |
| D22 | (amends D05) A MODULE entitlement covers every COVERED lesson in that module whether or not the lesson has a billing month; MONTHLY covers COVERED lessons of its month; legacy access covers COVERED lessons. Lesson launch for a non-participant of a non-PUBLIC course returns 404 (no existence leak), matching the content tree. | T4b review |
| D23 | Legacy `POST /orders` (non-reactivation) refuses MONTHLY courses with 409 `USE_PAYMENT_COMPOSER`: a legacy course-wide enrollment would cover every billing month for one month's price (payment review M-1). Narrows an existing path; adds none. Existing legacy MONTHLY enrollments keep their access window. | T7-PAY |
| D24 | Line-order payment start (gateway initiation and slip upload) re-checks coverage under the same advisory coverage locks inside the transaction that creates the PENDING payment/slip, and refuses when the same order already has a PENDING/CONFIRMED payment or an open slip (payment review H-1). | T7-PAY |

### Open owner decisions (defaults applied; nothing blocked)
- **O-1** Should facility fees be excluded from teacher revenue-share gross? Default: settlement unchanged; facility fee defaults to NONE per tenant.
- **O-2** Should staff (non-chargeback) refunds revoke entitlements / which lines a partial refund covers? Default: current policy (no revocation).
- **O-3** Should per-course Institute Commission drive settlement? Default: stored + displayed only.
- **O-4** Should free participants (no payment) appear in attendance/roster/exam/notification audiences? Default: no change.
- **O-5** Pre-existing gap (not introduced here): nothing prevents a second CONFIRMED payment on the same order (PAY §6.7). Composer orders reject already-covered/pending lines; the general index is left for a payment-ledger ADR.
- **O-6** Automatic expiry of stale PENDING payments (payment review M-3) — payment-rule change, not built. **O-7** Durable system-actor record for gateway-path grants, chargeback revocations and duplicate-payment refund flags (M-2) — needs audit trigger (V33) decision; today: entitlement rows + structured logs. **O-8** MONTHLY offers price past months at the current period amount; MODULE offers ignore course pricing model (L-4) — product confirmation.

## 3. Schema contract (database-architect)

**V85 — course/lesson/module fields** (`V85__klass_course_structure_fields.sql`)
- `course`: `registration_enabled BOOLEAN NOT NULL DEFAULT TRUE`, `enrollment_fee NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (>=0)`, `cover_image_object_key VARCHAR(512)`, `whatsapp_invite_url VARCHAR(500)`, `telegram_invite_url VARCHAR(500)`, `display_order INTEGER` (backfill by created_at per tenant); extend `ck_course_pricing_model` with DAILY, MODULE, INSTALLMENT.
- `course_module`: `price NUMERIC(12,2) CHECK (price IS NULL OR price > 0)`.
- `course_lesson`: `starts_at TIMESTAMPTZ`, `ends_at TIMESTAMPTZ` (CHECK ends_at > starts_at when both), `resource_type VARCHAR(40)` CHECK in (VIMEO_VIDEO, SECURE_VIDEO, DOCUMENT, ZOOM_LINK, ZOOM_MEETING, ZOOM_WEBINAR, ZOOM_RECORDING, VIDEO_LINK, GOOGLE_DRIVE_LINK, MS_TEAMS_LINK, GOOGLE_MEET), `payment_type VARCHAR(30) NOT NULL DEFAULT 'COVERED_BY_CLASS_FEES'` CHECK (FREE, COVERED_BY_CLASS_FEES, PAID), `price NUMERIC(12,2)` CHECK (PAID ⇔ price > 0), `description TEXT`, `lesson_label VARCHAR(255)`, `billing_year SMALLINT`, `billing_month SMALLINT` (both or neither; month 1–12; year 2000–2100). Index `(tenant_id, module_id, billing_year, billing_month)`.
- New: `course_tag (id, tenant_id, name, normalized_name, created_at; UNIQUE (tenant_id, normalized_name))`, `course_tag_assignment (tenant_id, course_id, tag_id; PK (course_id, tag_id); composite tenant FKs)`, `course_teacher_assignment (tenant_id, course_id, teacher_id, is_primary, created_at; UNIQUE (tenant_id, course_id, teacher_id))`, `course_link (id, tenant_id, parent_course_id, source_course_id, created_by, created_at; UNIQUE (tenant_id, parent_course_id, source_course_id); CHECK parent<>source)`, `course_commission_rate (id, tenant_id, course_id, rate NUMERIC(5,2) CHECK 0..100, effective_from, created_by, created_at)` append-only.
- Backfill: primary teacher assignment from `course.teacher_id`; nothing inferred for lessons (payment_type default, billing month NULL).

**V86 — participation, entitlements, progress** (`V86__course_participation_and_content_entitlements.sql`)
- `course_participation (id, tenant_id, student_id, course_id, enrolled_at, source VARCHAR(30) CHECK (SELF_ENROLL, LEGACY_ENROLLMENT, PAID_ACTIVATION, STAFF_GRANT); UNIQUE (tenant_id, student_id, course_id); index (tenant_id, student_id); index (tenant_id, course_id))` + backfill from every non-revoked enrollment lineage (distinct tenant/student/course) with source LEGACY_ENROLLMENT; report counts in the migration header/QA doc.
- `content_entitlement` per D02 (scope MONTHLY|MODULE|LESSON, course_id, module_id, lesson_id, period_year, period_month, order_id, order_line_id NOT NULL, source_payment_id, source_slip_id with exactly-one CHECK, granted_at, valid_until, revoked_at, revoked_reason, revoked_by_refund_id; UNIQUE (tenant_id, order_line_id); partial unique active coverage `(tenant_id, student_id, coverage_key) WHERE revoked_at IS NULL`; index (tenant_id, student_id, course_id)).
- `lesson_progress (tenant_id, student_id, course_id, lesson_id, first_viewed_at; UNIQUE (tenant_id, student_id, lesson_id))`.

**V87 — order lines and fee snapshots** (`V87__order_lines_and_fee_snapshots.sql`)
- `student_order`: `subtotal_amount`, `facility_fee_amount`, `discount_amount` NUMERIC(12,2) nullable (legacy), `fee_policy_snapshot VARCHAR(100)`.
- `student_order_line (id, tenant_id, order_id, line_no, scope, course_id, module_id, lesson_id, period_year, period_month, billing_period_id, label, unit_amount, facility_fee_amount, discount_amount, line_total, currency, coverage_key, created_at; UNIQUE (tenant_id, order_id, line_no); UNIQUE (tenant_id, order_id, coverage_key); index (tenant_id, order_id); index (tenant_id, coverage_key))`. Money CHECKs ≥0, line_total = unit + fee − discount.
- The database architect owns the final file split/ordering so that `content_entitlement.order_line_id` gets a composite FK to `student_order_line`.

`coverage_key` format: `MONTHLY:<courseId>:<yyyy>-<mm>`, `MODULE:<moduleId>`, `LESSON:<lessonId>`.

## 4. API contract (all additive; tenant from auth context; JSON camelCase; money as decimal strings with currency)

### 4.1 Admin course management (course-management; Tenant Admin / Course Coordinator, Teacher on owned courses)
- `GET /api/v1/courses?archived=false|true&search=&page=&size=` — server search (case-insensitive title, teacher name); sort `displayOrder, createdAt`. `CourseResponse` adds: `registrationEnabled`, `enrollmentFee`, `coverImageUrl` (short-lived signed or null), `whatsappInviteUrl`, `telegramInviteUrl`, `paymentValidDays` (= accessDurationDays), `commissionRate`, `teachers:[{id,name,primary}]`, `tags:[{id,name}]`, `linkedCourses:[{id,title}]`, `displayOrder`, `priceDisplay` (`{amount,currency,period}` or null: ONE_TIME price / MONTHLY current period / null for FREE).
- `POST /api/v1/courses` — `CourseCreateRequest` adds optional: `teacherIds[]` (≥1 required by the new modal; legacy `teacherId` still accepted), `pricingModel` (FREE|ONE_TIME|MONTHLY|MODULE|DAILY|INSTALLMENT), `price` (ONE_TIME/MONTHLY amount; MONTHLY creates billing configuration + first open period), `enrollmentFee`, `paymentValidDays` (int ≥0), `commissionRate` (0–100), `whatsappInviteUrl`, `telegramInviteUrl` (https only), `description`, `tagNames[]` (trim, dedupe case-insensitively, create missing), `linkedCourseIds[]`; `slug`/`category` become optional (slug derived server-side). Header `Idempotency-Key` → repeated submit returns the same course. 400 field errors use the existing validation error shape. Cross-tenant/unknown teacher id → 400 `VALIDATION_ERROR` (pre-existing contract kept); unknown/cross-tenant linked course → 404; self/nested link → 400 `COURSE_LINK_INVALID`. Idempotency: Redis guard (tenant+actor+key hash, 24 h), see `CourseCreateIdempotencyGuard`.
- `PATCH /api/v1/courses/{id}` (existing edit endpoint) accepts the same optional fields. `CourseResponse` also carries `timezone` (tenant default) so course managers without config access can convert session times. Teacher list items gain `userId`; `teacherIds` are user ids.
- `PATCH /api/v1/courses/{id}/registration` `{enabled:boolean}` → CourseResponse (audited).
- `PUT /api/v1/courses/order` `{courseIds:[...]}` (all must be tenant courses) → 204.
- `POST /api/v1/courses/{id}/cover` multipart `file` (JPEG/PNG/WebP sniffed, ≤5 MB) → CourseResponse; `DELETE` → default cover.
- `GET /api/v1/course-tags` → `[{id,name}]` (any authenticated tenant user).
- Modules: existing CRUD + `price` field; `PUT /api/v1/courses/{courseId}/modules/order {moduleIds}`; module/lesson delete returns 409 when entitlements/order lines reference it.
- Lessons ("Sessions"): existing CRUD under `/api/v1/courses/{courseId}/modules/{moduleId}/lessons` + fields `startsAt`, `endsAt` (ISO instants; UI converts tenant-timezone date+time), `resourceType`, `paymentType` (required by the UI; API default COVERED), `price`, `description`, `lessonLabel`, `billingYear`, `billingMonth`; response adds `expired` (endsAt < now). As of the approved refinement, billing fields remain on the request for compatibility but are ignored for authority: MONTHLY+COVERED derives stored year/month from `startsAt` in tenant timezone (UTC fallback); other combinations persist null. Existing historical rows are not backfilled. 400 `ENDS_BEFORE_START`, `PRICE_REQUIRED_FOR_PAID`, `PRICE_NOT_ALLOWED`. `PUT .../lessons/order {lessonIds}`.
- `GET /api/v1/courses/lesson-resource-types` → `[{type, label, support: INTEGRATED|LINK_ONLY|UNSUPPORTED, help, liveClass}]`. V89 adds first-class `IMAGE`; SECURE_VIDEO remains the stored enum but displays as **Player Video**.

### 4.2 Student catalog and free enrollment (enrollment-management)
- `GET /api/v1/enrollments/my/catalog?view=ENROLLED|AVAILABLE&search=&tagId=&sort=DEFAULT|TITLE&page=&size=` → page of `{courseId,title,teacherName,coverImageUrl,priceDisplay:{amount,currency,period:"MONTH"|null}|null,pricingModel,tags,enrolled,registrationEnabled}`. AVAILABLE = PUBLIC, not archived, registration enabled, not yet participating. Tag chips from `GET /api/v1/course-tags`.
- `POST /api/v1/enrollments/my/courses/{courseId}` (no body) → 200 `{courseId,enrolledAt,created:boolean}`; idempotent; 404 not visible; 409 `REGISTRATION_CLOSED`. Never creates orders/payments. Audited.

### 4.3 Course content, access and progress (enrollment-management, consuming CourseLookupApi)
- `GET /api/v1/courses/{courseId}/content` (participant, or staff preview) →
  `{courseId, viewedCount, totalCount, timezone, periods:[{key:"2026-10"|null, year, month, label, payment:{status:"PAID"|"PARTIAL"|"PENDING"|"REQUIRED"|"NOT_REQUIRED", purchase:{scope:"MONTHLY",courseId,year,month}|null}, modules:[{moduleId,title,sequence,sourceCourseId,lessons:[LessonNode]}]}]}`; periods newest first, null-key group ("Course content") last.
  `LessonNode = {lessonId, courseId(source), moduleId, title, lessonLabel, resourceType, paymentType, price, startsAt, endsAt, viewed, access:{status, availability:"NOT_STARTED"|"OPEN"|"ENDED"|"ALWAYS", payment:"FREE"|"COVERED"|"PENDING"|"REQUIRED", requiredPurchase:{scope,courseId,moduleId,lessonId,year,month}|null}}` — never URLs.
- `GET /api/v1/courses/{courseId}/live-classes` → upcoming (endsAt ≥ now) lessons with resourceType ZOOM_MEETING/ZOOM_WEBINAR/ZOOM_LINK/MS_TEAMS_LINK/GOOGLE_MEET, as LessonNode + `moduleTitle`, ordered by startsAt; same evaluator.
- `POST /api/v1/courses/{courseId}/lessons/{lessonId}/launch` → 200 `{access, resources:[{kind:"MATERIAL"|"CLASS_SESSION", id, title, type}], playerHandoffRequired, playerLinks:{androidUrl,iosUrl}}` and records progress; non-ALLOWED → 403 `{code:<status>, access}` (UNAVAILABLE → 404). Resources are then opened through the existing (now lesson-guarded) material download / playback / player / join endpoints.

### 4.4 Payment composer (payment-management)
- `GET /api/v1/checkout/courses?scope=` → enrolled courses with ≥1 offer for scope: `[{courseId,title}]`.
- `GET /api/v1/checkout/offers?courseId=&scope=MONTHLY|MODULE|LESSON|COURSE|DAILY|INSTALLMENT&year=` → `{scope,courseId,currency,supported:boolean,years:[2026],items:[{scope,year,month,moduleId,lessonId,label,amount,state:"AVAILABLE"|"PAID"|"PENDING"}]}` (DAILY/INSTALLMENT → `supported:false, items:[]`; COURSE → the ONE_TIME course offer, purchased via the existing order flow).
- `POST /api/v1/checkout/quote` `{courseId, items:[{scope,year?,month?,moduleId?,lessonId?}]}` → `{courseId,currency,lines:[{lineNo,scope,label,year,month,moduleId,lessonId,unitAmount,facilityFee,lineTotal}],subtotal,facilityFeeTotal,discountTotal,total,feeNote,methods:{online:boolean,slip:boolean},bankDetails:{bankName,branch,accountHolder,accountNumber}|null}`. 422 `ITEM_NOT_ELIGIBLE`, `ITEM_ALREADY_PAID`, `ITEM_PENDING`, `EMPTY_SELECTION`.
- `POST /api/v1/checkout/orders` `{courseId, items, idempotencyKey}` → existing `OrderResponse` + `lines`, `subtotal`, `facilityFeeTotal`, `discountTotal`. Server re-quotes; client totals ignored.
- Payment: existing `POST /api/v1/orders/{id}/payments` (online, PayHere/fake per ADR-017) and `POST /api/v1/orders/{orderId}/slips` (multipart) — unchanged contracts, now valid for line orders; `GET /api/v1/orders/{id}/payment-status` for return-page polling.
- Slip review DTOs add `orderLines` so reviewers see covered items.

## 5. File ownership

| Owner | Allowed paths |
|---|---|
| database-architect | `backend/src/main/resources/db/migration/V85__*.sql`, `V86__*.sql`, `V87__*.sql` only |
| integration-engineer | `backend/src/main/java/com/lms/coursemanagement/api/LessonResourceType.java`, `LessonResourceSupport.java`, `LessonResourceTypeCatalog.java` |
| backend-springboot-engineer | `backend/src/main/java/**` (except the three integration files) + unit/MockMvc tests for its own classes outside `com/lms/klass`; never `frontend/`, never migrations |
| qa-test-engineer | `backend/src/test/java/com/lms/klass/**`, `frontend/e2e/klass-*.spec.ts`, `frontend/e2e/fixtures/klass/**` |
| frontend-nextjs-engineer | F-ADMIN: `frontend/src/app/(tenant-admin)/tenant-admin/courses/**`, `frontend/src/components/courses/admin/**`, `frontend/src/lib/api/klass-admin.ts`; F-STUDENT: `frontend/src/app/(student)/student/**`, `frontend/src/components/courses/student/**`, `frontend/src/components/payments/composer/**`, `frontend/src/lib/api/klass-student.ts` |
| parent | all `docs/**`, ADRs, CLAUDE/.claude guidance |

## 6. Integrated task table

| Task | Owner | Depends on | Req IDs | Status | Validation / evidence |
|---|---|---|---|---|---|
| T0 governance + ADR-028 | parent | — | all | done | diff of CLAUDE.md, `.claude/**`, requirements |
| T1 stage-1 analysis | PRA, ARCH, PAY | T0 | all | done | reports summarised §1–2 |
| T2 migrations V85–V87 | database-architect | T1 | CM-A01–A06, EP-C*, EP-E*, EP-L*, EP-M*, EP-D01 | done | `mvnw test -Dtest=FlywayMigrationIntegrationTest` 1/1 pass (88 migrations, Hibernate validate OK); seeded throwaway PG17: backfill 5/5 expected participation rows, all negative CHECK/FK cases rejected |
| T3 resource type catalog | integration-engineer | T1 | CM-A04, CM-U01 | done (compile pending in T4a) | compiled by T4a |
| T4a backend course-management | backend | T2, T3 | CM-A01–A05, EP-C01–C12 | done | parent re-ran `mvnw -o test-compile` EXIT 0; agent: course-mgmt unit 77/77, integration 99/99, new Klass tests 33/33, adjacent 130/130 |
| T4b backend participation/evaluator/progress/guards | backend | T4a | EP-E01–E05, EP-L01–L06, CM-S01–S06, CM-R01–R03, EP-C10–C11 | done | agent: new evaluator unit 34/34, ParticipationAndLessonAccessIntegrationTest 9/9, affected-module suites 780 run (3 legit ADR-028 updates); full `mvnw verify` 2568 tests, 0 failures, 3 errors (audit rollback tests broken by T4a create-audit — fix routed to T4c) |
| T4c backend composer/orders/grants | backend | T4b | EP-M01–M05, EP-P01–P07, EP-S01–S02, CM-P01–P04 | done | agent: full `mvnw -o verify` BUILD SUCCESS 2586 tests, 0 failures, 0 errors (30 min); targeted 72/72 incl. 7200 fixture, webhook replay, slip idempotency, chargeback scope, D21 re-purchase |
| T5a frontend admin | frontend | T4a (contract) | CM-A01–A05, EP-C01–C12 | done (pending live integration) | lint 0 errors, tsc 0, build OK; mismatches routed to T4a |
| T8a admin UX fixes | ui-ux-reviewer → frontend | T5a | CM-A01–A05, EP-C* | done | UX review 3H/8M/7L; fixed all except: live "Sessions" tab label kept (existing e2e depends), no lesson deep link (editor has no anchors), Edit → overview (CourseEditForm lives there); Highs on linked-course/coverage copy deferred until T4b/T4c verify behaviour; lint 0 errors, tsc 0, build OK |
| T5b frontend student + composer | frontend | T4b + §4.4 contract | CM-S01–S06, CM-P*, CM-R03, EP-E*, EP-L01, EP-M*, EP-P*, EP-S* | done (composer awaiting live T4c) | tsc 0, lint 0 errors, build OK. Slip form adds required "Deposit reference number" (backend ``, needed for duplicate-reference check) |
| T6 QA integration + Playwright | qa-test-engineer | T4c, T5 | all | done | Testcontainers + Playwright; also update existing e2e affected by the new /modules page (tenant-admin-courses, tenant-admin-course-edit-parity, course-modules) and by the new student pages (enrollment-and-course-access, student-course-content, live-class-student-join, live-class-recording, live-class-cross-tenant, student-dashboard, material-student-visibility) |
| T7 reviews | security-reviewer, payment-ledger-specialist, ui-ux-reviewer | T6 | all | done (UX admin, security 0C/0H/2M/6L, payment conditional pass H-1) | findings list |
| T8 fixes + re-review | owners | T7 | — | done (T8a–T8e) | — |
| T8b backend review fixes | backend | T6, T7 | EP-P07, EP-P06, CM-T01, EP-C11 | done | full `mvnw -o verify` 2620 tests, 0 failures, 0 errors; module+QA suites 1211/1211; 6 existing tests updated 403→404 (SEC L4 by design), 2 MONTHLY legacy tests moved to SESSION (D23). Known consequence: abandoned PENDING gateway payment blocks re-payment of those items (409 ORDER_PAYMENT_IN_PROGRESS / ITEM_PENDING) until it resolves — tied to O-6 | PAY H-1/D24, PAY M-1/D23, SEC M1 (invite links + non-PUBLIC linked courses only for participants/staff), SEC L2 (no public tenantId repo params), SEC L3 (shared video asset: evaluate every attached lesson), SEC L4 (student 404 for non-visible course), SEC L5 (teacher preview of linked sources checks ownership), SEC L6 (player links https only) |
| T6a QA backend acceptance + mocked e2e updates | qa-test-engineer | T4c, T5 | all | done (live E2E blocked) | `-Dtest=com.lms.klass.**` 11/11; engineer Klass suites 94/94; 10 existing mocked specs updated: 124/132 then 132/132 after T8c (enrollment-and-course-access 47/47). lms_dev V84 draft-checksum blocker RESOLVED 2026-10-14: owner ran the guarded fix (backup C:/Projects/lms_dev-pre-v84fix.dump); parent verified checksum -233641601, column integer, CHECK intact, row counts unchanged. Live klass-* E2E dispatched (T6b) |
| T8c reactivation entry regression + nav | frontend | T6a | ADR-013 regression, CM-S01 | done | restored expired-legacy reactivation section; nav "Available Courses"; lint 0 errors, build OK; open: tsc error in QA-owned tenant-admin-courses.spec.ts (archivedAt), prod-build-only failure student-course-content.spec.ts:234 (toggle after redirect) |
| T9 docs + acceptance | parent | T8 | all | done | QA docs |
| T8d tenant-config clear | backend | T6b | EP-P04 config | done | null clears optional keys; full verify 2627/0/0 |
| T8e prod-build catalog toggle | frontend | T6b | CM-S01 | done | 54/54 on prod build and dev |
| T6b live E2E | qa-test-engineer | T8b, V84 fix | all | done | klass-* 12/12 live; mocked 132/132; 14 screenshots in docs/qa/klass-evidence |

Legacy-migration-engineer: skipped unless backfill ambiguity needs analysis (backfill is mechanical: enrollment → participation). DevOps: invoked only if local Docker/Testcontainers/infra fails (Docker 29.6.1 and dev Postgres/Redis/MinIO are up at start).
