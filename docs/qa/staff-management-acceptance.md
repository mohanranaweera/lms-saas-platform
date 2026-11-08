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
