# API Impact Analysis (Wave 0)

Status: analysis only. No controller, DTO, or route was modified to produce this document.

## 1. Existing REST surface (31 controllers, all under `/api/v1`)

See `current-architecture-inventory.md` §2 for the full controller-to-base-path table. Summary
by domain:

| Domain | Controllers | API maturity |
|---|---|---|
| identity-access-service | `AuthController`, `PlatformAdminAuthController`, `RoleCatalogController` | Mature |
| tenant-management | `TenantRegistrationController`, `PlatformAdminTenantController` | Mature (approval audit event NEEDS_VERIFICATION — PAR-01-04) |
| user-management | `StaffController`, `StudentController`, `TeacherController` | Backend ahead of frontend for staff (PAR-02-01) |
| course-management | `CourseController`, `CourseModuleController`, `CourseLessonController`, `CoursePublicController` | Mature; no pricing-model/billing-period contract yet (PAR-05-04) |
| content-management | `MaterialController` | MVP scope only (PDF/image/notes); no expiry/limit/watermark fields |
| enrollment-management | `EnrollmentController`, `CourseAccessStateController`, `ReactivationRequestController` | Mature for course-level; no session/material/video-level contract |
| payment-management | `OrderController`, `PaymentController`, `RefundController`, `SlipController`, `SlipReviewController` | Mature |
| integration-management | `PaymentWebhookController` | Payment gateway only; no Zoom/SMS/WhatsApp/YouTube-Vimeo adapter endpoints |
| ledger-settlement-management | `LedgerController`, `PlatformAdminLedgerController` | Ledger-read only; no settlement-run endpoints |
| attendance-management | `AttendanceController` | Mature (course_lesson-scoped, see PAR-10-01) |
| exam-management | `ExamController`, `QuestionBankController`, `ExamAttemptController`, `MarkingQueueController`, `ResultsController` | Mature |
| notification-management | `NotificationController` | Email + in-app only |
| audit-log-management | `AuditLogController`, `PlatformAdminAuditLogController` | Mature |

## 2. Domains with zero API surface

`video-access-management`, `live-class-management`, `finance-expense-management`,
`reporting-analytics`, `support-management` have **no controllers at all**. Every parity item
under Klass domains 14 (partially — tenant-management owns branding), 15, 17, 19, 20, 21, 22,
23, 24, and 26 requires net-new endpoint design, not extension of an existing contract.

## 3. Endpoint-level gaps on domains that DO have an API surface

Cross-referenced against `klass-parity-matrix.md`; only the gaps, not the full contract (which
belongs in `docs/api/` per module once designed):

| Existing controller | Missing endpoint(s) | Parity ID |
|---|---|---|
| `StaffController` | none identified at the API layer — the gap is frontend-only (PAR-02-01/02) | PAR-02-01 |
| `StudentController` | bulk-import endpoint | PAR-03-03 |
| `TeacherController` | explicit SUSPENDED/REJECTED lifecycle transitions (NEEDS_VERIFICATION whether these already exist) | PAR-04-04 |
| `CourseController` | pricing-model/billing-period fields; clone; archive; staff-facing course-creation parity with Teacher's builder | PAR-05-02, 05-04, 05-07, 05-08 |
| `MaterialController` | expiry date, view/download limit, watermark-flag fields; YouTube/Vimeo external-reference attach endpoint | PAR-06-03, 06-05 |
| `EnrollmentController` | session/material/video-scoped expiry endpoints; bulk-extension endpoint | PAR-09-04, 09-05 |
| `LedgerController` | settlement-run trigger/status/export endpoints | PAR-24-02 to 24-04 |
| `NotificationController` | template CRUD, bulk-send, delivery-log read endpoints | PAR-12-04 |
| `AuditLogController` | staff-sub-role "own-area" filtered view (mechanism undecided — NEEDS_VERIFICATION first) | PAR-13-04 |

## 4. Authorization posture of the existing API surface

Every controller inspected uses the shared `PermissionCheckService.hasPermission(DomainArea,
PermissionAction)` pattern rather than ad hoc per-controller role checks, and tenant context is
resolved once at the edge (`identity-access-service`'s auth filter) and propagated via the
request-scoped `TenantContextHolder` — consistent with `.claude/rules/tenancy.md`. No controller
inspected accepts a client-supplied `tenant_id` as authoritative. This is a **MATCHES** finding
at the architectural level for the API layer as a whole; per-endpoint verification against the
target permission matrix is captured per-row in `klass-parity-matrix.md` rather than repeated
here.

Two structural notes worth carrying into every new controller built in Waves 1+:

1. `DomainArea` grants for `PAYMENTS_SLIPS`, `FINANCE_EXPENSES`, and `ACCESS_EXPIRY` are
   explicitly documented in the enum itself (`DomainArea.java`) as **category grants only** —
   they never by themselves authorize a terminal-state payment/slip mutation, a ledger-row
   delete, or a bare enrollment write. Any new endpoint gated on one of these three areas must
   independently re-verify the finer-grained rule from `.claude/rules/payments.md`, exactly as
   the existing payment/slip/enrollment controllers already do. This same pattern should be
   extended explicitly when `FINANCE_EXPENSES`-gated settlement/expense endpoints are built in
   Wave 7.
2. New endpoints for domains without an existing permission-matrix row at all (live-class
   scheduling — PAR-19-03; bulk notification sends — PAR-12-04; SMS/WhatsApp template
   approval — PAR-21/22) must not invent an authorization rule silently — the missing matrix row
   is itself listed as a gap to resolve (via `rbac-impact-analysis.md`) before the endpoint ships.

## 5. API versioning and DTO discipline

All 31 existing controllers are under `/api/v1` with dedicated request/response DTOs (no direct
JPA entity exposure was found in any inspected controller signature) — matches
`.claude/rules/backend.md`'s "never expose JPA entities directly" rule and master instruction
§36. New Wave 1+ endpoints should continue under `/api/v1` unless a breaking contract change is
deliberately introduced, in which case `docs/api/`'s versioning guidance (not duplicated here)
governs.

## 6. Recommended sequencing for API design work

New endpoint design should follow `review-api-contract` skill discipline per domain, in this
order (matching `migration-strategy.md`'s dependency ordering):

1. Tenant configuration read/write endpoints (unblocks branding, publish-approval policy,
   review toggle, device-limit overrides, expiry precedence — all currently stalled on this).
2. Course pricing-model/billing-period endpoints (highest blast radius on an existing, heavily
   used contract).
3. `video-access-management` token-issuance/validation endpoints (flagged MVP-baseline gap,
   PAR-20-01).
4. `live-class-management` scheduling endpoints (depends on `integration-management`'s
   `MeetingProviderApi` existing first).
5. `finance-expense-management` and `ledger-settlement-management` settlement endpoints
   (finance domain explicitly depends on settlement's API per its own spec — build settlement
   first).
