# STAFF-MGMT-02 — Staff Management & Role Permissions (Klass parity)

Status: in implementation (branch `feat/staff-management-permissions`). Decision record: ADR-034.
References: `docs/references/klass-staff-management/*.png`, live `demo.klass.lk/dashboard/staff` (read-only).
Extends MVP-005 (`docs/plans/MVP-005 Staff Management.md`) and MVP-003 (`PermissionCheckServiceImpl`).

## 1. Current model (audit, 2026-11-07)

- One `tenant_user` row per person per tenant (= the tenant membership). `role` is a single
  `Role` value, re-read live from the DB on every request by `JwtAuthenticationFilter`; the JWT
  `role` claim is never trusted. Role change / suspension therefore applies on the next request.
- Authorization source of truth: `PermissionCheckService.hasPermission(DomainArea, PermissionAction)`
  backed by a static code matrix. Teacher/Student are ownership-scoped (e.g. `CourseAccessGuard`),
  absent from the matrix.
- Staff (MVP-005): 7 fixed sub-roles, admin types a password, list/get/login-mobile only.
  No edit / role change / deactivate / activity / configurable permissions.
- Invitation infrastructure exists: `TenantUser.invited(...)` + `PasswordEmailRequestedEvent(ACCOUNT_SETUP)`.
- Audit: `AuditLogApi.record` joins the caller's transaction (rolled back together).

## 2. Design (contract)

### 2.1 Roles
- New tenant roles `OPERATOR`, `MANAGER` (role catalog + `Role` enum, migration). Their permissions
  are **tenant-configurable** grants; they hold nothing by default.
- `TEACHER` keeps ownership scoping; a small set of teacher capabilities is tenant-configurable and
  can only **narrow** (default granted = current behaviour). A Teacher grant never elevates.
- Legacy fixed sub-roles (`FINANCE_STAFF` … `READ_ONLY_AUDITOR`) and `TENANT_ADMIN` keep their
  static matrix unchanged. Existing assignments are preserved; new assignments offer only
  Operator/Manager (server-derived). Tenant Admin / Platform Admin are never assignable here.
- Effective permissions: one role per tenant membership; a person in two tenants has two
  independent memberships. No multi-role union exists.

### 2.2 Capability catalog (`identityaccessservice.api.Capability`)
Each capability: `key`, `group`, `label`, `primary (DomainArea, PermissionAction)` (= the existing
gate, used for static roles and the UI), `implied` pairs (what the endpoint's code path checks),
`configurableFor` ⊆ {OPERATOR, MANAGER, TEACHER}, `availability` (`ENFORCED` | `LOCKED` (system-managed,
reason) | `UNAVAILABLE` (no backing operation, reason)).

`hasCapability(cap)`:
- static roles → `hasPermission(primary)` (unchanged access);
- OPERATOR/MANAGER → `ENFORCED && configurableFor contains role && tenant grant = true`;
- TEACHER → teacher-configurable: tenant grant (default true); otherwise n/a;
- anyone else → false.

### 2.3 Enforcement: `@RequiresCapability`
Controller methods carry `@RequiresCapability(Capability.X)`. A `HandlerInterceptor`
(runs before the `@PreAuthorize` proxy):
- OPERATOR/MANAGER: missing grant → 403. Granted → the cap's `implied` pairs are elevated for this
  request only (thread-local, cleared in `afterCompletion`, never propagated to other threads), so
  existing `hasPermission`/`requirePermission` checks on that endpoint pass.
- TEACHER: teacher-configurable cap with grant revoked → 403; never elevates (ownership still applies).
- all other roles: no-op (existing checks unchanged).
- OPERATOR/MANAGER on any non-annotated endpoint: `hasPermission` = false (fail closed).

Grants are read from the DB per request (memoised per request) → a revoked grant is denied on the
very next request from an existing session. No permissions in tokens.

### 2.4 Persistence (append-only migration V118)
- `role` catalog: `OPERATOR`, `MANAGER` (TENANT, `app/(tenant-admin)/`).
- `staff_profile`: add `first_name`, `last_name`, `contact_number` (VARCHAR, string phone).
- `tenant_role_capability_grant(tenant_id FK NOT NULL, role_code FK role, capability_key, granted,
  updated_at, updated_by)`, UNIQUE (tenant_id, role_code, capability_key), CHECK role_code IN
  (OPERATOR, MANAGER, TEACHER). Absent row = catalog default.
- `tenant_role_permission_version(tenant_id PK FK, version BIGINT, updated_at, updated_by)` —
  optimistic concurrency.

### 2.5 API (all tenant from trusted context; no tenantId accepted)
| Method/path | Capability | Notes |
|---|---|---|
| `GET /api/v1/staff?q&page&size(≤100)` | STAFF_VIEW | server search (name/email/phone), sorted by last name |
| `GET /api/v1/staff/{id}` | STAFF_VIEW | 404 cross-tenant |
| `GET /api/v1/staff/assignable-roles` | STAFF_MANAGE | `[{code,displayName}]` = Operator, Manager |
| `POST /api/v1/staff` | STAFF_MANAGE | `{firstName,lastName,email,mobileNumber,roleCode}` → INVITED + account-setup email. 409 email taken in this tenant (no other-tenant data). 400 forged role |
| `PATCH /api/v1/staff/{id}` | STAFF_MANAGE | `{firstName,lastName,contactNumber}` profile only |
| `PUT /api/v1/staff/{id}/role` | STAFF_ASSIGN_ROLES | `{roleCode}`; not self; target must be a staff membership |
| `POST /api/v1/staff/{id}/deactivate` / `reactivate` | STAFF_MANAGE | not self; revokes sessions; other tenants untouched |
| `POST /api/v1/staff/{id}/resend-invite` | STAFF_MANAGE | INVITED only, 1/min |
| `GET /api/v1/staff/permission-matrix` | STAFF_VIEW | `{version, roles[], groups[{key,label,rows[{key,label,cells{ROLE:{state,reason}}}]}]}` state ∈ GRANTED, NOT_GRANTED, LOCKED, UNAVAILABLE |
| `PUT /api/v1/staff/permission-matrix` | STAFF_PERMISSIONS_EDIT | `{expectedVersion, changes[{roleCode,capabilityKey,granted}]}` atomic; 409 `PERMISSIONS_STALE`; 400 unknown/invalid; 403 delegation |
| `GET /api/v1/me/permissions` | authenticated | `{role, capabilities[], permissionVersion}` |
| `GET /api/v1/staff/activities?q&from&to&page&size(≤50)` | STAFF_ACTIVITY_VIEW | tenant audit feed, redacted details |

Staff capabilities (STAFF_VIEW → STAFF_AND_ROLES/VIEW; STAFF_MANAGE, STAFF_ASSIGN_ROLES,
STAFF_PERMISSIONS_EDIT → STAFF_AND_ROLES/CREATE_EDIT; STAFF_ACTIVITY_VIEW → AUDIT_LOG/VIEW +
Tenant Admin/Auditor allowlist) are LOCKED: never grantable to Operator/Manager/Teacher.

### 2.6 Delegation & protection
- Only actors holding STAFF_PERMISSIONS_EDIT (Tenant Admin) edit grants; changes to the actor's own
  role are rejected; LOCKED/UNAVAILABLE cells and non-configurable role/cap pairs are rejected (400).
- Role assignment: target role must be in the server assignable set; never self; never a
  TENANT_ADMIN/TEACHER/STUDENT membership; the tenant owner and last Tenant Admin are therefore
  unreachable through this flow.

### 2.7 Audit (same transaction as the change)
`staff.invited`, `staff.profile_updated`, `staff.role_changed {from,to}`, `staff.deactivated`,
`staff.reactivated`, `staff.invite_resent`, `staff.role_permissions_changed {fromVersion,toVersion,
granted[{role,cap}], revoked[...]}`. Emails masked; no tokens/passwords. Rolled-back write ⇒ no entry.

## 3. Working matrix (Klass control → capability)

Legend — Roles: O=Operator, M=Manager, T=Teacher (configurable). Scope: tenant = all tenant
resources; own = teacher-owned courses. Gap: E=enforced in this change, L=locked (intentional),
U=unavailable (no backing operation), UI=UI-only before this change.

| Klass control | Capability key | Config roles | Scope | Backend enforcement (endpoint → existing gate) | Test / gap |
|---|---|---|---|---|---|
| Courses · View Courses | COURSES_VIEW | O M T | tenant / own | GET /courses, /courses/{id} → COURSES/VIEW (+ownership) | E |
| Courses · View Course Students | COURSES_VIEW_STUDENTS | O M T | tenant / own | GET /courses/{id}/roster | E |
| Courses · View Archived Courses | COURSES_VIEW_ARCHIVED | — | — | archived filter is a list param, not a separate op | U |
| Courses · Add Course | COURSES_ADD | O M | tenant | POST /courses → COURSES/CREATE_EDIT | E |
| Courses · Edit Course | COURSES_EDIT | O M | tenant | PATCH /courses/{id}, POST/DELETE cover → COURSES/CREATE_EDIT (+VIEW for linked courses) | E (Operator/Manager: price, pricing model, enrollment fee, commission and charges changes → 403 before any write; /price, /pricing-model, publish, clone, reorder, teacher reassignment not delegated) |
| Courses · Archive Course | COURSES_ARCHIVE | O M | tenant | POST /courses/{id}/archive, /unarchive | E |
| Courses · Toggle Registration Button | COURSES_TOGGLE_REGISTRATION | O M | tenant | PATCH /courses/{id}/registration | E |
| Courses · Delete Course | COURSES_DELETE | O M | tenant | DELETE /courses/{id} → COURSES/DELETE | E (implied: DELETE only) |
| Courses · Enroll Students | COURSES_ENROLL_STUDENTS | — | — | POST /students/{id}/enroll also requires PAYMENTS_SLIPS/APPROVE: it records a staff-granted CONFIRMED payment + ledger entry and a paid entitlement, so it is not a delegable free enrollment | L (Tenant Admin only; free ADR-028 enrollment is the course selection of Add/Edit Student) |
| Courses · Add Timetable | (= TIMETABLE_EDIT) | — | — | same operation as Timetable · Edit | L (alias) |
| Courses · Reviews (4 rows) | COURSES_REVIEWS_* | — | — | no course-review feature | U |
| Modules · View Modules | MODULES_VIEW | O M T | tenant / own | GET modules, lessons | E |
| Modules · Add Module | MODULES_ADD | O M T | tenant / own | POST modules | E |
| Modules · Delete Module | MODULES_DELETE | O M T | tenant / own | DELETE modules/{id} | E |
| Modules · Questions / Import (6 rows) | MODULES_QUESTIONS_* | — | — | question bank is teacher/exam-owned, not wired in this change | U |
| Students · View Students | STUDENTS_VIEW | O M | tenant | GET /students, /students/directory → STUDENTS/VIEW | E |
| Students · Add Student | STUDENTS_ADD | O M | tenant | POST /students, /students/v2 | E |
| Students · View Student Profile | STUDENTS_VIEW_PROFILE | O M | tenant | GET /students/{id}, /detail, /profile-fields, /photo, /remarks | E (sensitive profile fields stay masked without Edit) |
| Students · View Student Devices | STUDENTS_VIEW_DEVICES | O M | tenant | GET /students/{id}/devices → DEVICES/VIEW | E |
| Students · Delete Student Device | STUDENTS_DELETE_DEVICE | O M | tenant | POST devices/{id}/reset, devices/reset → DEVICES/CREATE_EDIT | E |
| Students · Edit Student | STUDENTS_EDIT | O M | tenant | PATCH /students/{id}, PUT /detail, PUT /profile-fields, POST/DELETE /photo, POST /remarks | E |
| Students · Send Notification | STUDENTS_SEND_NOTIFICATION | — | — | no single-student send operation | U |
| Students · Reset Password | STUDENTS_RESET_PASSWORD | O M | tenant | POST /students/{id}/reset-password | E (separate from Log as Student) |
| Students · Delete Student | STUDENTS_DELETE | O M | tenant | DELETE /students/{id} → STUDENTS/DELETE | E |
| Students · View / Approve Pending | STUDENTS_APPROVE_PENDING | O M | tenant | POST /students/{id}/approve | E (view pending = STUDENTS_VIEW filter) |
| (ours) Export Students CSV | STUDENTS_EXPORT_CSV | O M | tenant | GET /students/directory/export.csv | E (export ≠ view) |
| (ours) Export QR labels | STUDENTS_EXPORT_QR | O M | tenant | POST /students/qr-export | E |
| (ours) Log as Student | STUDENTS_LOG_AS | — | — | Tenant Admin only (ADR-033) | L |
| Payments · View Payments | PAYMENTS_VIEW | O M | tenant | GET /ledger/dashboard, /ledger/orders, /orders/{id}/admin-detail, /payments/{id}, staff payment-context/payment-history, /payment-slips/review-queue(+count), /payment-slips/{id}(+download-url), GET /free-cards | E |
| Payments · Add Payment | PAYMENTS_ADD | O M | tenant | POST manual-payments (+quote, GET payment-offers) → PAYMENTS_SLIPS/APPROVE (+VIEW) | E (never FREE_CARDS) |
| Payments · Issue Free Cards | PAYMENTS_ISSUE_FREE_CARDS | O M | tenant | POST /free-cards → FREE_CARDS/CREATE_EDIT | E (Add Payment never implies it) |
| Payments · Approve/Reject Pending | PAYMENTS_APPROVE_PENDING | O M | tenant | POST /payment-slips/{id}/approve, reject | E (dup checks unchanged) |
| Payments · List Undo Payments | PAYMENTS_LIST_UNDO | O M | tenant | GET /payments/{id}/refunds, /payments/{id}/refund-verification | E (the undo-payments report is REPORTS_UNDO_PAYMENTS) |
| Payments · Undo Payments | PAYMENTS_UNDO | O M | tenant | POST /payments/{id}/refunds (new REFUND row; append-only) | E (≠ approve) |
| Payments · Update Payment Rules | PAYMENTS_UPDATE_RULES | — | — | access-policy/gateway settings are Tenant Admin | L |
| Exams · View Exams | EXAMS_VIEW | O M T | tenant / own | GET /exams, /exams/{id}, /exams/courses/{id}/exams, /exams/directory | E |
| Exams · View Results | EXAMS_VIEW_RESULTS | O M | tenant | GET /exams/{id}/management, /stats | E |
| Exams · View Answers (answer key) | EXAMS_VIEW_ANSWERS | O M | tenant | GET /exams/{id}/documents/{purpose} | E (≠ results) |
| Exams · schedule/manage/edit/delete/start/stop/leaderboard/report | EXAMS_* | — | — | exam authoring stays teacher/Exam Manager | U |
| Attendance · View Attendance | ATTENDANCE_VIEW | O M | tenant | GET class-session/session rosters, /summary, /reports, /students/{id}/report | E |
| Attendance · Add Attendance | ATTENDANCE_ADD | O M | tenant | POST records | E |
| Media · View / Add / Delete | MEDIA_VIEW / MEDIA_ADD / MEDIA_DELETE | O M | tenant | /media-files GET (+summary, open-url) / POST / DELETE | E (rename not delegated) |
| Media · Zoom/YouTube/Folders (5 rows) | MEDIA_* | — | — | provider ops are Tenant Admin (ADR-018) / not built | L/U |
| Notifications · Show | NOTIFICATIONS_VIEW | O M | tenant | GET broadcasts, broadcasts/{id}, deliveries, templates → COMMUNICATIONS/VIEW | E |
| Notifications · Publish | NOTIFICATIONS_PUBLISH | O M | tenant | POST broadcasts, audience-preview, GET channels → COMMUNICATIONS/CREATE_EDIT (+VIEW) | E (≠ view; templates editing not delegated) |
| Notifications · Manage Scheduled | NOTIFICATIONS_SCHEDULED | — | — | no scheduled-broadcast management | U |
| Timetable · View | TIMETABLE_VIEW | O M | tenant | GET /calendar/occurrences, /calendar/courses, timetable.pdf, GET /courses/{id}/class-schedule | E |
| Timetable · Edit | TIMETABLE_EDIT | O M | tenant | PUT /courses/{id}/class-schedule | E |
| Teachers · View / Add / Edit | TEACHERS_VIEW / ADD / EDIT | O M | tenant | /teachers GET / POST / PATCH | E (Teacher column locked; Operator/Manager may not set or change the institute commission → 403, as with course pricing) |
| Teachers · View Wallet | TEACHERS_VIEW_WALLET | O M | tenant | GET /finance/teachers, /teacher-settlements, /teacher-settlements/{id} → FINANCE_EXPENSES/VIEW | E |
| Teachers · Disable Wallet / wallet actions | TEACHERS_WALLET_ACTIONS | — | — | settlements mark-paid/adjust are ledger actions, Tenant Admin/Finance | L |
| Teachers · Create Wallet | TEACHERS_WALLET_CREATE | O M | tenant | POST /finance/teachers/{id}/wallet → FINANCE_EXPENSES/CREATE_EDIT (TEACHER-WALLET-01, ADR-036) | E |
| Teachers · Record Wallet Pay In | TEACHERS_WALLET_PAY_IN | O M | tenant | POST …/wallet/pay-ins → FINANCE_EXPENSES/CREATE_EDIT | E |
| Teachers · Record Wallet Pay Out | TEACHERS_WALLET_PAY_OUT | O M | tenant | POST …/wallet/pay-outs → FINANCE_EXPENSES/CREATE_EDIT | E |
| Teachers · Adjust Wallet Balance | TEACHERS_WALLET_ADJUST | O M | tenant | POST …/wallet/adjustments → FINANCE_EXPENSES/CREATE_EDIT (GET wallet / entries carry TEACHERS_VIEW_WALLET) | E |
| Teachers · Delete Teacher | TEACHERS_DELETE | — | — | no teacher delete (suspend only) | U |
| Finance · View Income Summary | FINANCE_VIEW_INCOME_SUMMARY | O M | tenant | GET /finance/reports/summary | E |
| Finance · View Expenses | FINANCE_VIEW_EXPENSES | O M | tenant | GET /finance/expenses, /finance/expenses/{id} | E |
| Finance · View / Add / Delete Category | FINANCE_*_CATEGORY | O M | tenant | expense-categories GET / POST / archive | E (delete = archive, no row delete) |
| Finance · Annual stats, pending/recent/registration payments, transactions | FINANCE_DASHBOARD_* | — | — | not separable dashboard widgets | U |
| Finance · Account configuration (4 rows) | FINANCE_ACCOUNT_* | — | — | payment gateway secrets, Tenant Admin only (ADR-017) | L |
| Integrations (5 rows) | INTEGRATIONS_* | — | — | Tenant Admin only (ADR-018) | L |
| Tute · View Tutes / View Tute | TUTE_VIEW_TUTES / TUTE_VIEW_TUTE | O M T* | tenant; Teacher own courses | GET /courses/{id}/tutes(/{tuteId}, /paid-sessions, /fulfillments) → TUTES/VIEW | E (TUTE-01) |
| Tute · Create / Update / Enroll / Unroll | TUTE_CREATE / TUTE_UPDATE / TUTE_ENROLL / TUTE_UNROLL | O M T* | tenant; Teacher own courses | POST/PUT tutes, sync-payments, POST fulfillments, cancel → TUTES/CREATE_EDIT | E (TUTE-01) |
| Tute · Update Tute Status | TUTE_UPDATE_STATUS | O M T* | tenant; Teacher own courses | fulfillment status, POST /tute-dispatch/confirm → TUTES/APPROVE | E (TUTE-01) |
| Tute · Delete Tute | TUTE_DELETE | O M T* | tenant; Teacher own courses | DELETE tute (archive when it has history) → TUTES/DELETE | E (TUTE-01) |
| Tute · View Tute Dispatch (new) | TUTE_DISPATCH_VIEW | O M T* | tenant; Teacher own courses | GET /tute-dispatch/queue, filter-options → TUTES/VIEW | E (TUTE-01) |
| Tute · Print Dispatch Labels (new) | TUTE_DISPATCH_PRINT_LABELS | O M T* (not Auditor) | tenant; Teacher own courses | POST /tute-dispatch/labels (full addresses) → TUTES/VIEW | E (TUTE-01; bulk address export, so never configurable for Read-only Auditor) |
| Reports · each existing report | REPORTS_<NAME> | O M | tenant | /reports/<name>/* (class-level: options, runs, rows, PDFs) | E (synchronous; course summary also implies ATTENDANCE/VIEW) |
| Staff · view/manage/assign/permissions/activities | STAFF_* | — | — | Tenant Admin (view/activities also Auditor) | L |

Rows the backend cannot enforce cleanly during implementation are downgraded to U with a reason
(and recorded in §5), never shown as editable.

## 4. Acceptance (see §5 for results)
A1 create/reopen staff (invite, no password) · A2 edit profile · A3 reassign role · A4 deactivate/
reactivate, other tenant untouched · A5 forged role / self-change / cross-tenant id rejected ·
A6 matrix save+reload individual & group · A7 locked/unavailable rejected · A8 stale version 409 ·
A9 grant enables op, revoke blocks next request on existing session (course, student, payment,
export) · A10 Teacher revoke narrows own-course op; Teacher never reaches unowned course ·
A11 audit entry matches change, no secrets; failed save → no entry · A12 activities search/paging
tenant-scoped · A13 rendered Users/Add/Permissions/Activities desktop + mobile.

## 5. Results (2026-11-08)

Live acceptance A1–A13: **all PASS** on the local stack with two tenants and synthetic Operator, Manager
and Teacher accounts. Details and evidence: `docs/qa/staff-management-acceptance.md`,
`docs/qa/staff-management-evidence/`.

Automated (Testcontainers, targeted): `CapabilityEnforcementIntegrationTest` 9/9,
`StaffManagementIntegrationTest` 14/14, `RolePermissionMatrixIntegrationTest` 10/10,
`StaffActivitiesIntegrationTest` 3/3, `RoleCatalog*` 13/13, `CapabilitySemanticsTest` 11/11,
`ManualPaymentServiceTest` 12/12, `StudentRemarksAndQrIntegrationTest` 3/3, plus the static-role suites
`PermissionCheckServiceImplTest` and `PermissionEnforcementIntegrationTest`. Combined affected-module run (identity, staff, course, slip, refund, free card, manual payment,
Flyway): 625 tests. Its one failure was an activity-mask style mismatch; it was fixed and the suite re-ran green. Frontend:
`e2e/staff-management.spec.ts` 13/13 (mocked), `e2e/staff-management-live.spec.ts` 3/3 (live, opt-in).

Security review (bounded): no Critical or High findings. Fixed: Operator/Manager may not record a payment
with a discount adjustment (403; prevents a near-free card without `PAYMENTS_ISSUE_FREE_CARDS`); staff
sign-in mobile now refuses self and protected memberships; activity details drop credential- and URL-like
keys and mask contact values; suspending an account invalidates its open setup and reset links.
Operator/Manager may not set or change course pricing or teacher commission.

Known, accepted or open:
- `COURSES_ENROLL_STUDENTS` is LOCKED: the existing `/students/{id}/enroll` path writes a staff-granted
  payment and paid entitlement. Free enrollment stays available via Add/Edit Student.
- **Owner decision:** with `COURSES_ADD`, an Operator/Manager can set the price and commission when
  creating a course (edits are already blocked).
- QR label export with ids from another tenant returns a PDF without them (the pre-existing STU-MGMT-02
  contract for mixed bulk selections). Nothing about the other tenant is revealed.
- The portal header reads "Tenant Admin" for every tenant-portal staff role (pre-existing portal label).
- Klass layout differences: the matrix scrolls with the page (sticky header) rather than in its own
  pane, and Users row actions were not observable in the empty demo list, so they are not claimed as
  exact parity.
- Pre-existing failures, unrelated (also fail on clean `main`): `TeacherManagementIntegrationTest` (4)
  and `e2e/tenant-admin-nav.spec.ts` Teachers mocks (2).

## 6. STAFF-MGMT-03 — more configurable roles and unlocks (owner decisions 2026-11-08)

Owner decisions:
- Promote the 7 existing fixed roles (FINANCE_STAFF, COURSE_COORDINATOR, STUDENT_SUPPORT, CONTENT_MANAGER,
  EXAM_MANAGER, ATTENDANCE_OPERATOR, READ_ONLY_AUDITOR) to configurable matrix columns.
- Unlock: teacher wallet/settlement actions; Free Cards + Enroll Students; Update Payment Rules + Log as
  Student. Gateway/integration credentials, staff management and the Add Timetable alias stay LOCKED.
- Delegated roles may set the price on course **create** (edits stay blocked).

### 6.1 Seeded roles (the promoted 7)
- **Default grant** for a capability = the role's static matrix already holds the capability's primary
  pair. With no override row, behaviour is exactly as before: no elevation, so no silent broadening.
- **Explicit grant** (row `granted = true`) elevates the capability's implied pairs for that request,
  as for Operator/Manager. **Explicit revoke** (row `granted = false`) gives 403 on that capability's
  endpoints even though the static matrix holds the pair.
- **Unannotated endpoints** keep the static matrix unchanged (the matrix governs only listed capabilities).
- READ_ONLY_AUDITOR is configurable only for capabilities whose primary action is VIEW (keeps the
  static "never a write" invariant).
- Assignable in Add User / Change role: OPERATOR, MANAGER and the 7. Never TENANT_ADMIN/TEACHER/
  TEACHER_ASSISTANT/STUDENT. Delegation, self-edit and protected-membership rules are unchanged.

### 6.2 Newly ENFORCED (were LOCKED/UNAVAILABLE) — as implemented
Primary = the endpoint's existing gate (static access unchanged); implied = exactly what the code path checks.
Seeded roles are configurable for every Operator/Manager row (Read-only Auditor: view-only rows only).

| Capability | Endpoint(s) | Primary (+implied) | Notes |
|---|---|---|---|
| FINANCE_CALCULATE_SETTLEMENTS | POST /finance/teacher-settlements | FINANCE_EXPENSES/CREATE_EDIT | idempotent: an overlapping/already-settled period is 409, no new rows |
| FINANCE_MARK_SETTLEMENT_PAID | POST /finance/teacher-settlements/{id}/mark-paid | FINANCE_EXPENSES/CREATE_EDIT | row-locked, once only (409) |
| FINANCE_ADJUST_SETTLEMENT | POST /finance/teacher-settlements/{id}/adjustments | FINANCE_EXPENSES/CREATE_EDIT | new ADJUSTMENT row referencing the original; original untouched |
| FINANCE_EDIT_SHARE_RATES | POST /finance/teacher-share-rates | FINANCE_EXPENSES/CREATE_EDIT | append-only, effective-dated (GET rates now carries TEACHERS_VIEW_WALLET) |
| FINANCE_ADD_EXPENSE | POST /finance/expenses | FINANCE_EXPENSES/CREATE_EDIT | (GET attachment-url now carries FINANCE_VIEW_EXPENSES) |
| FINANCE_VOID_EXPENSE | POST /finance/expenses/{id}/void | FINANCE_EXPENSES/DELETE | void = state change, no row delete |
| FINANCE_ACCOUNT_VIEW_CONFIGURATION | GET /payment-gateway-accounts | PAYMENT_GATEWAY/VIEW | status only, never the secret; change/update/delete stay LOCKED |
| FINANCE_DASHBOARD_ANNUAL_STATS | GET /finance/reports/periods | FINANCE_EXPENSES/VIEW | monthly income/expense buckets |
| FINANCE_DASHBOARD_PENDING_PAYMENTS | GET /ledger/outstanding | PAYMENTS_SLIPS/VIEW | orders awaiting payment |
| PAYMENTS_REVOKE_FREE_CARDS | POST /free-cards/{id}/revoke | FREE_CARDS/DELETE | reason required, once only; Issue never implies Revoke |
| COURSES_ENROLL_STUDENTS | POST /students/{id}/enroll | STUDENTS/CREATE_EDIT (+PAYMENTS_SLIPS/APPROVE) | static default holder: Tenant Admin only (the only role holding both) |
| PAYMENTS_UPDATE_RULES | PUT /access-policy, PUT /access-policy/courses/{id} | ACCESS_EXPIRY/CREATE_EDIT | a course device-limit change still needs DEVICES/CREATE_EDIT (not implied → 403); GETs unannotated |
| STUDENTS_LOG_AS | POST /students/{id}/impersonation | STUDENT_IMPERSONATION/CREATE_EDIT | ADR-033 amended: actor ≠ subject, start/end audited; the session's actor is re-checked each request (Tenant Admin or role still granted), a revoke ends it |
| MODULES_QUESTIONS_VIEW | GET courses/{c}/modules/{m}/questions, modules/question-counts, GET exam-questions/{id} | EXAMS/VIEW | teacher ownership unchanged (not teacher-configurable) |
| MODULES_QUESTIONS_ADD | POST …/questions, POST …/questions/copy, POST /exam-media | EXAMS/CREATE_EDIT | |
| MODULES_QUESTIONS_EDIT | PUT /exam-questions/{id} | EXAMS/CREATE_EDIT | |
| MODULES_QUESTIONS_IMPORT | POST/GET …/question-imports, GET /question-imports/template.csv | EXAMS/CREATE_EDIT (+EXAMS/VIEW) | |
| EXAMS_SCHEDULE | POST /exams, POST /exams/courses/{c}/exams | EXAMS/CREATE_EDIT (+VIEW) | create; DRAFT→SCHEDULED (APPROVE) not delegated |
| EXAMS_EDIT | PUT /exams/{id} | EXAMS/CREATE_EDIT (+VIEW) | |
| EXAMS_MANAGE | POST/DELETE /exams/{id}/enrollments, GET enrollment-candidates, POST enrollments/sync-payments | EXAMS/CREATE_EDIT (+VIEW) | |
| EXAMS_CREATE_LEADERBOARD | POST/PUT/DELETE /exam-leaderboards | EXAMS/CREATE_EDIT | |
| EXAMS_GENERATE_REPORT | GET /exams/{id}/export.csv | EXAMS/VIEW | |
| COURSES_VIEW_ARCHIVED | GET /courses?archived=true / includeArchived=true | COURSES/VIEW | CourseService check for staff; Teachers unaffected |
| STUDENTS_VIEW_PENDING | GET /students/directory(?/export.csv) tab=PENDING | STUDENTS/VIEW | StudentDirectoryService check |

Left UNAVAILABLE (reason shown in the matrix):
- TEACHERS_WALLET_ACTIONS ("Disable Wallet", key kept) — no wallet disable operation; the real wallet actions are the four settlement rows above.
- MODULES_QUESTIONS_DELETE, EXAMS_DELETE — need EXAMS/DELETE, which no staff role (not even Tenant Admin) holds; owning teacher only.
- EXAMS_START, EXAMS_STOP — exams open/close on their schedule; no manual operation.
- MODULES_IMPORT — no module import operation.
- FINANCE_DASHBOARD_RECENT_PAYMENTS, _REGISTRATION_PAYMENTS, _TRANSACTIONS — part of the payments dashboard / order list (View Payments).
- Course reviews, Delete Teacher, scheduled notifications, single-student notification, media folders.

Still LOCKED: account credential change/update/delete, Integrations, STAFF_*, Add Timetable alias, Zoom/YouTube provider ops.

Delegation guards (pricing edits, teacher commission, manual-payment adjustment) now apply to any actor holding the
pair only through a grant (`PermissionCheckService.isDelegated`): Operator/Manager as before, and a seeded role whose
static matrix lacks the pair. Static holders are unaffected. Migration V120 widens the grant table's role CHECK.

### 6.3 Results (2026-11-08)
Live B1, B2, B4, B5, B6 PASS. B3 PASS for every unlock except the end-to-end settlement run, which is
BLOCKED for lack of closed-period revenue in a synthetic tenant and covered by
`TeacherSettlementIntegrationTest`. Security and ledger review: no Critical/High. The two Medium
by-id bypasses (archived courses, pending students) are fixed (77db3e7). QA follow-ups are fixed:
overrides equal to the default are deleted, and the matrix no longer causes page-level sideways
scroll. Details: `docs/qa/staff-management-acceptance.md`.

T* = Teacher-configurable but **not granted by default** (TUTE-01, `CapabilitySpec.omtTeacherOff`); `TUTES` static grant is Tenant Admin only. See `docs/specs/tute-management-dispatch.md` §8.
