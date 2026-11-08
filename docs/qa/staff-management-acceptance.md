# STAFF-MGMT-02 — Live acceptance (A1–A13)

Branch `feat/staff-management-permissions` (uncommitted working tree), verified 2026-11-08 against the
local stack only: backend `spring-boot:run` (profile `local`, devtools; classes compiled 23:56 local after the
last source edit 23:55), DB `lms_dev` (Flyway V118 `staff management permissions` and V119
`tenant user suspended while invited` applied, `success = t`), Mailpit `localhost:8025`, Next dev
`demo.lms.test:3000` / `academy.lms.test:3000`. `GET /api/v1/me/permissions` returns 200
`{role, capabilities[], permissionVersion}`, so the running backend is the current tree.

Tenants: **A** = `demo` (`tenantadmin@demo.test`), **B** = `academy` (`admin@academy.test`). All created data is
synthetic (`*@example.test`, run tags `mw7e3ceo`, `mw7e8adx`, ...). The Operator and Manager were created through
`POST /api/v1/staff` (= Add User) and completed **through the invite link found in Mailpit**. The Teacher went
through the existing onboarding: `POST /teachers`, then `POST /teachers/{id}/approve`, then first login, then the
mandatory password change. At the end, the permission matrix was restored to its defaults (only the Teacher
defaults are granted; Operator/Manager have no grants). Synthetic courses were deleted. Staff accounts remain,
because staff are never deleted.

Evidence:
- API log (request, status, redacted body excerpt for every call): `docs/qa/staff-management-evidence/api-evidence.md`
  (run 1 = full A1–A12; "Resume run" sections = A9 course cross-tenant with a real tenant-B course, and the A10 rerun).
- Screenshots (1440×900 `desktop-*`, 390×844 `mobile-*`): `docs/qa/staff-management-evidence/*.png`.
  Overflow: `desktop-overflow.json` / `mobile-overflow.json` (all 0 px). Operator nav: `operator-nav.json`.
- Browser spec (opt-in, live stack): `frontend/e2e/staff-management-live.spec.ts`. Run with
  `STAFF_LIVE=1 STAFF_LIVE_OPERATOR_EMAIL=<operator> npx playwright test e2e/staff-management-live.spec.ts --workers=1`.
  Result: **3 passed (31.2s)**.

| # | Requirement | Result | Evidence (excerpt) |
|---|---|---|---|
| A1 | Create staff with invite and no password; reopen; duplicate | **PASS** (15/15) | `POST /staff` OPERATOR → 201 `status:"INVITED"`, no password field. A body-supplied `password` is ignored (login with it → 401). A body-supplied `tenantId` is ignored (B list unchanged). `GET /staff/{id}` → INVITED. Duplicate email in a different case → **409**. An email that exists only in tenant B → 201 (no cross-tenant leak). Mailpit received "Set up your … account" and the link → `password-tokens/complete` → login 200 → status ACTIVE. Same flow for the Manager. Assignable roles = `[MANAGER, OPERATOR]`. |
| A2 | Edit profile | **PASS** (3/3) | `PATCH /staff/{id}` → 200 (`firstName:"Olive"`, `contactNumber:"+94 71 555 0101"`). `roleCode`/`email` in the body are ignored on reload. An invalid body → 400. |
| A3 | Reassign role | **PASS** (4/4) | `PUT /staff/{id}/role` OPERATOR→MANAGER → 200. The Operator's **existing token** `/me/permissions` immediately reports `MANAGER`. Changing back → `OPERATOR`. |
| A4 | Deactivate/reactivate; other tenant untouched | **PASS** (9/9) | Deactivate → 200 SUSPENDED. The existing token's next request → **401**. Login → 403 `USER_SUSPENDED`. B list is unchanged. Reactivate → ACTIVE, and the old token stays 401 (sessions revoked); a fresh login → 200. An INVITED account that is deactivated and then reactivated goes back to **INVITED**, not ACTIVE (V119). Resend invite → 200, then an immediate repeat → **429**; resend for an ACTIVE account → 409. Extra probe: after deactivate, the original invite link → `verify`/`complete` 400 `TOKEN_INVALID`, and the account stays SUSPENDED. |
| A5 | Forged role / Operator denied / self / cross-tenant | **PASS** (25/25) | Create with roleCode `TENANT_ADMIN`, `FINANCE_STAFF`, `PLATFORM_ADMIN`, `TEACHER` or `garbage` → 400. `PUT role` with `TENANT_ADMIN`, `FINANCE_STAFF` or `garbage` → 400. The Operator token on every `/staff*` endpoint, the matrix GET/PUT and activities → **403**. The admin's own id on role/deactivate → 404 (not a staff membership), and the admin stays active. The tenant-A admin on a tenant-B staff id with GET/PATCH/PUT role/deactivate/resend → **404**, and the B record is unchanged. |
| A6 | Matrix: single save, group save, reload | **PASS** (6/6 + UI) | Single `OPERATOR/COURSES_ADD` → 200, v0→v1, and reload shows GRANTED. Two-cell atomic save → 200. Group save `MANAGER × PAYMENTS` (6 cells) → 200, and reload shows all GRANTED while the LOCKED `PAYMENTS_UPDATE_RULES` is unchanged. The Manager's `/me/permissions` lists all 6. UI: 3 cells toggled, "3 unsaved changes", the summary "Operator: 3 granted, 0 revoked", Save, then reload shows them checked (`desktop-03/04`). |
| A7 | LOCKED/UNAVAILABLE/unknown → 400, no partial write | **PASS** (7/7) | Each batch was `[valid OPERATOR/MEDIA_VIEW, bad]`, with bad = LOCKED `STAFF_VIEW`, LOCKED `STUDENTS_LOG_AS`, UNAVAILABLE `TUTE_CREATE`, `NOT_A_CAPABILITY`, `TEACHER/COURSES_ADD`, role `TENANT_ADMIN`, or role `FINANCE_STAFF`. Every batch → **400**, the version stayed at 7, and MEDIA_VIEW stayed NOT_GRANTED. |
| A8 | Stale version → 409 | **PASS** (3/3) | `expectedVersion` v-1 → **409 `PERMISSIONS_STALE`** with no write. A future version → 409. An Operator PUT → 403. |
| A9 | Grant/revoke on an existing session; siblings; cross-tenant | **PASS** (25 + 9 checks) | Operator **same token**, before any grant: `POST /courses`, `GET /students/directory`, `export.csv`, `GET /ledger/dashboard`, `POST /students/qr-export` and `POST /free-cards` all → 403. After grants: course 201, directory 200, ledger 200, CSV 200 `text/csv`, QR 200 `application/pdf`. Siblings: STUDENTS_VIEW does not allow CSV or QR (403), and PAYMENTS_ADD does not allow free cards (403). After revoke, the **next request** for each of the 5 → 403. Cross-tenant with grants: B course GET/PATCH/POST module → 404, GET modules → 403; B student `GET` and `/detail` → 404; the CSV and course list contain no B rows; B data is unchanged. Run 1 showed 4 FAILs caused by the test seed (the B course was not created: `teacherId is required`). These were re-verified with a real B course in the resume run (9/9). See the observation below on QR export. |
| A10 | Teacher narrowing; never an unowned course | **PASS** (15/15, rerun) | Default: `/me/permissions` includes MODULES_ADD, and a module in the teacher's own course → 201. Revoke `TEACHER/MODULES_ADD`: `/me` drops it, and the same token's own-course create → **403** while MODULES_VIEW stays 200 and the Tenant Admin is unaffected (201). Regrant → 201. Another teacher's course → 403 in every state, and a tenant-B course → 404. Teacher `/staff` and matrix PUT → 403. (Earlier attempts were blocked by onboarding gates that are by design: new teachers start SUSPENDED pending approval, then hit `PASSWORD_CHANGE_REQUIRED`.) |
| A11 | Audit entries match changes; no secrets; failed save writes nothing | **PASS** | Present: `staff.invited`, `profile_updated`, `role_changed {from:"MANAGER",to:"OPERATOR"}`, `deactivated`, `reactivated`, `invite_resent`, `role_permissions_changed {fromVersion,toVersion,granted[{role,capability}],revoked[]}`. Each has `actorName` (masked `t•••@demo.test`), `actorRole`, `targetEntity`/`targetReference`, and `occurredAt`. The Operator email does not appear in details. The 7 rejected saves plus the 409s → activity count stayed 7→7. Secret scan over 1,168 entries flagged 6, all reviewed as **false positives**: pre-existing `tenant_config.updated` privacy/terms text containing the word "password", and the flag name `DUPLICATE_IMAGE_HASH`. No credential, token or hash value is present. |
| A12 | Search, paging, tenant scoping | **PASS** (7/7) | `q=Operator<tag>` → 4 matching entries. `size=3` pages 0 and 1 are disjoint, with consistent total=1167. `size=500` is clamped to 50. Tenant B's 34 entries contain none of A's ids or names, B's search for A's operator → 0, and B sees its own `staff.invited`. Operator → 403. |
| A13 | Rendered Users/Add/Permissions/Activities, desktop and mobile | **PASS (with gaps below)** | `desktop-01..05`, `mobile-01..05`, Operator nav `desktop-06`/`mobile-06`. Horizontal overflow is 0 everywhere. The Operator nav shows only Dashboard, Students and Courses (granted: COURSES_VIEW, COURSES_ADD, STUDENTS_VIEW), with no Staff and no Finance. |

## Observations / defects

1. **QR export with foreign or unknown ids returns 200 and an empty sheet** (`backend/.../reportinganalytics/web/StudentQrLabelPdfController.java:64-75`).
   `POST /students/qr-export {studentIds:[<tenant-B id>]}` returns 200 `application/pdf` at 832 bytes. That is byte-identical to the response for a random UUID, so it neither leaks existence nor any B data. However, `.claude/rules/tenancy.md` asks for 403/404 rather than 200 with empty data when addressing another tenant's id. Severity: low.
2. **Activities readability gaps (UI):** `granted`/`revoked` render as raw JSON arrays (`[{"role":"OPERATOR","capability":"COURSES_VIEW"},…]`). The target shows a technical entity name plus a UUID (`tenant_role_permission_version 24c4ed08-…`, `staff_profile 01a1b99f-…`) instead of a staff name or label (`desktop-05`, `mobile-05`).
3. The Operator's shell header reads "Tenant Admin" (`desktop-06`), which is misleading for a non-admin role. Cosmetic.

## Klass parity comparison (`docs/references/klass-staff-management/*.png`)

- Users: tabs, search, ADD USER, columns, pagination and sorting by last name all match. Gaps: there is no "Staff" item visible in the first sidebar screen (it lives under Administration further down), and the INVITED badge is ours, not Klass's. Below `md`, the list falls back to cards (Klass has no mobile pattern to compare against).
- Add User: same fields and order (First/Last, Email/Mobile, Role) with the same Cancel/Create User actions. Ours adds an explanatory line about the invite email. Klass uses icon-prefixed outlined fields; ours uses labelled inputs above the fields.
- Permissions: same Manage Permissions card, OPERATOR/MANAGER/TEACHER columns, per-group "Select all" and SAVE CHANGES. Material differences (by design per spec §3): LOCKED cells show a lock and UNAVAILABLE cells show "—" instead of an editable checkbox. Ours adds an unsaved count, Discard and a confirmation dialog with a per-role summary. The matrix scrolls with the page, while Klass uses an inner scroll pane. On mobile, a "Role column" selector shows one role at a time.
- Activities: there is no Klass reference image to compare against. See observation 2.

---

# STAFF-MGMT-03 — Seeded roles configurable + unlocks (B1–B6)

Commits fa471c9, 77db3e7 and e939303. Contract: spec §6. Verified 2026-11-08 on the same local stack.

Preconditions: Flyway **V120** `seeded roles configurable capabilities` (`success = t`, 12:22:54), and
`GET /staff/permission-matrix` returns **10 role columns**. The running backend includes the commits.

Evidence:
- API log: `docs/qa/staff-management-evidence/api-evidence.md`, section **"STAFF-MGMT-03"** (run `mw7ve682`) and two settlement follow-ups.
- Screenshots: `m03-*.png` (desktop 1440×900, mobile 390×844).
- Browser data: `m03-desktop-report.json`, `m03-mobile-report.json`, `m03-finance-staff-nav.json`. Baseline: `m03-matrix-baseline.json`.
- Browser spec (opt-in): `frontend/e2e/staff-management-m03-live.spec.ts`. Run with `STAFF_LIVE=1 STAFF_LIVE_FINANCE_EMAIL=<finance staff>`. Result: **3 passed (27.5s)**.

Fixtures: the Finance Staff user was created through Add User and the invite link from Mailpit. The Operator is the existing STAFF-MGMT-02 synthetic account. The pending student was created through public self-registration, with `approval_required` turned on temporarily and then restored to `false`.

| # | Requirement | Result | Evidence (excerpt) |
|---|---|---|---|
| B1 | Finance Staff with no overrides: unchanged access; still denied staff management | **PASS** (11/11) | Created 201, invite received, setup 204, login 200. Review queue 200. Slip approve on an absent id → **404** (passed the gate). Expenses list 200. Teacher-settlements list 200. `GET/POST /staff`, matrix GET/PUT and activities → **403**. `POST /courses` → 403 (unchanged). |
| B2 | Seeded-role edit | **PASS** (11/11) | Revoke `FINANCE_STAFF/PAYMENTS_APPROVE_PENDING`: the same token's next approve → **403**, while the review queue stays 200 (narrow). Regrant → 404 (passes the gate). Grant `COURSES_ADD` → `POST /courses` **201** on the same token; revoke → 403. `READ_ONLY_AUDITOR` + `COURSES_ADD`, `PAYMENTS_APPROVE_PENDING`, `STUDENTS_LOG_AS` or `FINANCE_ADD_EXPENSE` → **400** "not configurable for READ_ONLY_AUDITOR", and the version is unchanged. |
| B3 | Operator unlocks: 403 before grant → allowed → 403 after revoke (same token) | **PASS for 8 capabilities; BLOCKED for the settlement business path** | **PASS:** `FINANCE_ADD_EXPENSE` 403/201/403. `FINANCE_VOID_EXPENSE` 403/200/403, and the voided row remains (state change, no delete). `PAYMENTS_ISSUE_FREE_CARDS` 403/201/403, and Issue alone does not authorize Revoke (403). `PAYMENTS_REVOKE_FREE_CARDS` 403/200/403. `COURSES_ENROLL_STUDENTS` 403/200/403. `PAYMENTS_UPDATE_RULES` 403/200/403 (same value; the policy is unchanged). `FINANCE_EDIT_SHARE_RATES` 403/201/403. `STUDENTS_LOG_AS`: 403, then 201 with a handoff cookie and `current` 200. The audit entry `student.impersonation_started` records `actorName:"Olive Operator…"`, `actorRole:"Operator"`, target `student_profile`, so actor ≠ subject. After revoke, the live impersonation token → **401**, the cookie cannot mint a new token (401), and a new start → 403. Operator price on **create** → 201; price **edit** → 403. **Gate only:** `FINANCE_CALCULATE_SETTLEMENTS`, `FINANCE_ADJUST_SETTLEMENT` and `FINANCE_MARK_SETTLEMENT_PAID` were each 403 before grant and 403 after revoke. With the grant, each request passed the gate into business validation: "No revenue share rate", then 409 "no unsettled ledger entries", then 400 "Period is not closed". End-to-end calculate-twice / adjust-new-row / mark-paid-once could not run: settlements need revenue dated in a **closed** period for a synthetic teacher, and `demo` has none (all synthetic revenue is dated today). Reaching the synthetic `course-summary-seed` tenant needs a Host mapping that isn't configured locally and wasn't authorised in this session. |
| B4 | By-id bypass fixes | **PASS** (15/15) | Finance Staff by default: archived course detail 200. After revoking `COURSES_VIEW_ARCHIVED`: `GET /courses/{archived}`, `/modules` and `/roster` → **403**; the list stays 200; regrant → 200. Pending student by default: detail 200. After revoking `STUDENTS_VIEW_PENDING`: `/students/{id}`, `/detail`, `/profile-fields` and `/devices` → **403**; directory `tab=PENDING` → 403; QR export of the pending id is silently skipped (832-byte sheet, byte-identical to an unknown id); regrant → 200. |
| B5 | Assignable roles = 9 with descriptions; forged roles; cross-tenant | **PASS** (14/14 + UI) | API returns 9 roles `{code, displayName}`. Descriptions are frontend copy: the role helper under the select and the "What each role can do" guide list all 9, each with a description (`m03-*-05-add-user-role-guide.png`). Create with `TENANT_ADMIN`, `TEACHER`, `TEACHER_ASSISTANT`, `STUDENT` or `PLATFORM_ADMIN` → 400. Change role → `TENANT_ADMIN` → 400. Finance Staff → Read-only Auditor → 200 (then restored). Tenant-B staff GET/PUT role → 404. Finance Staff on a tenant-B course, a tenant-B student, or an unknown settlement → 404. |
| B6 | Browser: Roles shown (all 10), Add User role descriptions, Finance Staff nav after revoke | **PASS with 1 layout defect** | Default shows "4 of 10 roles shown" (Operator, Manager, Teacher, Finance Staff); after "All" it shows 10/10 with the right headers, a sideways-scrolling pane, and sticky Permission column and header (`m03-desktop-01..04`). On mobile, the role selector offers all 10 (`m03-mobile-03`), and Add User is a full-screen sheet (`m03-mobile-05`). Finance Staff nav before: 15 links including Expenses and Teacher Payouts. After revoking `FINANCE_VIEW_EXPENSES` and `TEACHERS_VIEW_WALLET`, Expenses and Teacher Payouts are gone, and Staff never appears (`m03-*-06-*`). **Defect:** with 10 roles at 1440×900, the page scrolls horizontally by 147 px (`m03-desktop-07-page-horizontal-overflow.png`). |

## STAFF-MGMT-03 defects / observations

1. **The page overflows horizontally by 147 px with "Roles shown" = 10 at 1440×900.**
   - Files: `frontend/src/app/(tenant-admin)/tenant-admin/staff/permissions-tab.tsx:621` (the `sr-only` span in non-editable cells) and `:442` (the scroll pane, which has no `relative`).
   - Cause: 129 `sr-only` spans in "—"/lock cells beyond the pane's visible width are positioned against a containing block outside `permission-matrix-scroll`, so the pane's `overflow-auto` does not clip them.
   - Repro: log in as Tenant Admin → Staff → Permissions → Roles shown → All. `document.documentElement.scrollWidth - innerWidth` = 147, and `window.scrollTo(10000, 0)` gives `scrollX` = 147. With 4 roles it is 0.
   - Likely fix: add `relative` to the pane or the cell.
2. **There is no "reset to default" for seeded-role cells.** A revoke followed by a regrant leaves an explicit `granted = true` override row. Seven such rows now exist in `demo`: FINANCE_STAFF × 6 and TEACHER/MODULES_ADD. The effective matrix equals the baseline (0 cell differences, version 20→66), and for these capabilities the implied pairs are within the static pairs, so behaviour is identical. However, per §6.1 an explicit grant *elevates* implied pairs, so for a capability whose implied pairs exceed the role's static pairs, "un-revoking" would broaden access. This is a design observation; consider a "restore default" action that deletes the row.
3. **Synthetic financial rows created, and they are append-only so they can't be removed:**
   - two CONFIRMED manual enrollments of `m03-stu-mw7ve682@example.test`, one on a course taught by the demo teacher `sampath@gmail.com`;
   - one voided expense;
   - one revoked free card;
   - a 70% share rate for the synthetic `teacher3-mw7e8adx` (from 2019-01-01).

### STAFF-MGMT-03 follow-up fixes (2026-11-08)

- **B6 defect fixed:** the permission matrix pane is now `relative`, so screen-reader text in
  locked/unavailable cells stays inside the scroll pane. Live rerun of
  `e2e/staff-management-m03-live.spec.ts` (Tenant Admin screens): `pageOverflowPx = 0` at 1440×900 and
  390×844 with all 10 roles shown (`m03-desktop-report.json`, `m03-mobile-report.json`).
- **Override rows fixed:** saving a value equal to the role's default now deletes the override instead of
  storing it. At runtime, an explicit grant only elevates a promoted role when its default is off, so the
  7 rows already in `demo` cannot widen access. Covered in `CapabilityEnforcementIntegrationTest` 19/19,
  where a revoke then regrant leaves no row.
- **B3 settlements:** live end-to-end is BLOCKED by the lack of closed-period revenue in a reachable
  synthetic tenant. Calculate-twice idempotency and adjustment-as-new-row are covered by
  `TeacherSettlementIntegrationTest` (10/10, Testcontainers), and the live grant/deny gates PASS.
