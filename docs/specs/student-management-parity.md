# STU-MGMT-02 — Student Registration & Management (Klass parity)

Status: Implemented (branch `feat/student-management-parity`, 2026-11-07).
Plan: `docs/plans/student-management-parity.md`. Acceptance: `docs/qa/student-management-acceptance.md`.
Change-control: introduces a support-impersonation session (auth architecture, change-controlled) —
approved by the task owner's explicit "Log as Student" requirement and recorded in
`docs/adr/ADR-033-student-support-impersonation.md`.

## 1. Evidence base

| Source | Status |
|---|---|
| `docs/references/klass-student-management-pages/*.png` (9 images) | Inspected once |
| Live demo `demo.klass.lk/dashboard/students` | **Not inspected** — no credentials were supplied to this session. Everything below marked *Klass* comes from the screenshots only. |

**Confirmed Klass (screenshots):** Students heading; tabs ACTIVE / PENDING / BLACK LIST (disabled);
Search Field selector (default "All Fields"; other options not visible → *assumption*: Index Number,
First Name, Last Name, Email, Mobile); search box; EXPORT AS CSV; EXPORT QRS; ADD STUDENT; columns
Index Number, First Name, Last Name, Email, Mobile, Last Login, Actions; row icons Login As Student,
view (drawer), edit; ⋮ menu Comprehensive Report, Send Message, Reset Password, Delete Student.
Add form fields/order as §3. Drawer: avatar, index, full name, email, phone, WhatsApp, school, DOB,
last login, ADD REMARK, Devices (count), SEE FULL PROFILE. Full profile: breadcrumbs, Date Range
Filter, Remarks, Attended Exams (name/course/date/PASS-FAIL), Enrolled Courses (+ SHOW ATTENDANCE),
Payments, Session Views, identity sidebar (first/last name, mobile, home, email, location, birthday,
school, A/L year, A/L stream, guardian "name · phone · NIC", Registered On) and a QR. Bulk Print QRs
dialog: search, select-all + per-row checkboxes, columns Index Number / First Name / Last Name / NIC.
Klass index numbers include alphanumerics (`D-1525`).

## 2. Ownership: account vs tenant profile

`tenant_user` is **per tenant** (UNIQUE `(tenant_id, email)`, V3) — there is no cross-tenant shared
identity, so a tenant admin can never overwrite another tenant's account.

| Owner | Data |
|---|---|
| `identity-access-service` `tenant_user` | email (NOT NULL), password hash, status, role, `login_mobile` (ADR-031), must-change-password |
| `user-management` `student_profile` | name parts, index number, contact/guardian/school/address fields, profile image ref, QR token, pending/removed flags |
| `user-management` `student_remark` (new) | staff-only internal remarks |
| `identity-access-service` `impersonation_session` (new) | support sessions |
| `enrollment-management` `course_participation` | free enrollment (ADR-028) |

Profile image belongs to the tenant **student profile** (tenant-scoped `media_file`, usage `STUDENT_PROFILE`).

## 3. Field mapping (create/edit form, Klass order)

| # | Klass field | Column | Rule |
|---|---|---|---|
| 1 | Index Number * | `student_profile.index_number` | **Server-assigned** by the V95/V97 per-tenant counter trigger, immutable, string. Form shows "Assigned automatically" on create, read-only on edit. *Deviation:* Klass lets staff type it; our existing policy (numeric, 4-digit zero-padded, transactional counter) is kept — a typed value could collide with the counter. Alphanumeric indexes are not in our format. |
| 2 | Email | `tenant_user.email` | **Required** (V3 NOT NULL). *Deviation:* Klass optional; making email nullable changes auth architecture → needs its own ADR. |
| 3 | Password | via `UserProvisioningApi` | Optional on create: blank → server generates a temporary password returned **once** (`mustChangePassword=true`). Never on edit. |
| 4/5 | First/Last Name * | `first_name`, `last_name` (new); `name` = "first last" kept for other modules | 1–120 chars |
| 6 | WhatsApp | `whatsapp_number` | string, ≤50, `[0-9+ ()-]` |
| 7 | Date of Birth | `date_of_birth` DATE | optional; blank = unknown (null); not in future; ≥ 1900-01-01 |
| 8 | Mobile * | `student_profile.mobile` + `tenant_user.login_mobile` via `setLoginMobile` | string (leading zeros kept); uniqueness per ADR-031 |
| 9/10 | Address Line 1/2 | `address_line`, `address_line_2` | ≤255 |
| — | City / Postal code (Location) | `city`, `postal_code` | optional |
| 11 | Guardian name | `guardian_name` | ≤255 |
| 12 | Guardian contact | `guardian_phone` | ≤50 |
| 13 | Guardian NIC | `guardian_nic` (new) | ≤20; sensitive — staff only, never in QR/labels/CSV |
| 14 | Courses * | `course_participation` | ≥1 on create (Klass asterisk); multi-select of tenant courses; free participation only |
| — | Home telephone | `fixed_phone` | ≤50 |
| — | School | `school` | ≤255 |
| — | A/L Year | `al_year` SMALLINT | 2000–2100 |
| — | A/L Stream | `stream` | ≤50 |
| — | Profile image | `profile_media_file_id` (new) | §6 |

Tenant-configurable "additional details" (STU-PROF-01 `student_profile_field_setting`) remain the
source of required/visible flags for self-service; staff edit uses this allowlisted DTO. No competing
field definitions are added.

**Course selection on edit is add-only**: newly ticked courses get a free participation; unticking is
not offered in the edit form (revocation stays the existing audited Enrollments-tab flow). No order,
payment, ledger row or paid entitlement is ever created (ADR-028).

## 4. Permissions

| Action | Gate |
|---|---|
| List/search/view/drawer/full profile/CSV/QR view+export/QR resolve | `STUDENTS` VIEW |
| Create/edit/photo/approve pending/remarks create | `STUDENTS` CREATE_EDIT |
| Delete (remove from tenant) | `STUDENTS` DELETE |
| Reset password | existing `STUDENTS` CREATE_EDIT flow (temporary password shown once to the admin) |
| Log as Student | **new** `STUDENT_IMPERSONATION` CREATE_EDIT — Tenant Admin only |
| Remarks list | `STUDENTS` VIEW (staff only; never exposed on `/me` or public APIs) |
| Devices | existing `DEVICES` VIEW / CREATE_EDIT |
| Student self-service photo | `ROLE_STUDENT`, own profile only (`/me/photo`) |

## 5. API contract (all under `/api/v1`, `ApiResponse` envelope, tenant from auth context)

| Method/Path | Body / params | Response |
|---|---|---|
| GET `/students/directory` | `tab=ACTIVE\|PENDING`, `field=ALL\|INDEX\|FIRST_NAME\|LAST_NAME\|EMAIL\|MOBILE`, `q`, `page`, `size≤100` | `PageResponse<StudentDirectoryRow>` `{id,indexNumber,firstName,lastName,email,mobile,lastLoginAt,status,hasPhoto}` — one row per student, sorted by index |
| GET `/students/directory/export.csv` | same filters | `text/csv` full filtered scope; cells starting `= + - @ \t \r` prefixed with `'`; no NIC/guardian/password |
| POST `/students/v2` | `StudentRegistrationForm` (§3, no tenantId/status/role) | 201 `{student: StudentDetail, temporaryPassword?}` |
| GET `/students/{id}/detail` | — | `StudentDetail` (all §3 fields + `fullName,status,pending,registeredAt,lastLoginAt,photoUrl?,enrolledCourseIds[]`) |
| PUT `/students/{id}/detail` | same form minus password/index | `StudentDetail` |
| POST `/students/{id}/approve` | — | `StudentDetail` (pending → active) |
| DELETE `/students/{id}` | `{reason}` | 204 — sets `removed_at/removed_by`, suspends account, revokes sessions; history untouched |
| POST `/students/{id}/photo` · `/students/me/photo` | multipart `file` | `StudentDetail` / own photo |
| DELETE `/students/{id}/photo` · `/students/me/photo` | — | 204 |
| GET `/students/{id}/photo` · `/students/me/photo` | — | 302-free JSON `{url, expiresAt}` short-lived signed URL (5 min) or 404 |
| GET/POST `/students/{id}/remarks` | `{body ≤2000}` | `PageResponse<Remark>{id,body,authorName,createdAt}` |
| GET `/students/{id}/qr` | — | `{payload, rotatedAt}` |
| GET `/students/{id}/qr.png` | — | `image/png` |
| POST `/students/{id}/qr/rotate` | — | `{payload}` (audited) |
| POST `/students/qr-export` | `{studentIds[] ≤500}` | `application/pdf` label sheet |
| GET `/students/qr/resolve?payload=` | — | `{id,indexNumber,fullName}` (this tenant only, else 404) |
| POST `/students/{id}/impersonation` | `{reason 5–500}` | `{sessionId, expiresAt, student{id,indexNumber,fullName}}` + HttpOnly cookie |
| POST `/auth/impersonation/token` | cookie only | `{accessToken, expiresAt, student{…}, actorName}` |
| POST `/auth/impersonation/end` | cookie and/or impersonation bearer | 204, cookie cleared |
| GET `/auth/impersonation/current` | impersonation bearer | `{sessionId, expiresAt, student{…}, actorName}` |

JSON names (form and `StudentDetail`): `email, password, firstName, lastName, whatsappNumber,
dateOfBirth, mobile, addressLine, addressLine2, city, postalCode, guardianName, guardianPhone,
guardianNic, fixedPhone, school, alYear, stream, courseIds[]`. Edit refuses an email change (400 field
error). `POST /remarks` and `POST /impersonation` return 201. Error codes: `IMPERSONATION_READ_ONLY`
(403), `IMPERSONATION_NOT_ALLOWED`, `IMPERSONATION_SESSION_INVALID` (401), `STUDENT_NOT_ACTIVE` (409).
CSV export writes a `student.directory_exported` audit entry. Staff course selection accepts any
non-archived, non-draft tenant course (private included) and ignores the public registration switch.

Existing endpoints reused unchanged: `/students/{id}/reset-password`, `/students/{id}/devices*`,
enrollment/attendance/exam/payment/watch-time tabs, Communications `SELECTED_STUDENTS` broadcast,
public `GET /public/calendar/today`.

## 6. Profile image

Server: bounded read (≤2 MB), magic-byte sniff + full `ImageIO` decode (**JPEG/PNG only** — the JDK cannot decode WebP),
64–4096 px each side, re-encoded to a 512×512 centre-cropped JPEG (drops EXIF/metadata; EXIF
orientation applied when present), stored as tenant `media_file` usage `STUDENT_PROFILE`. Replacement
and removal discard the old file only when no other row references it. Served only via the
authorized signed-URL endpoints (never public). Recommended source: square ~400×400 px.
Client: preview + square crop/position (zoom slider) before upload; circular avatar; initials fallback.

## 7. Student QR

- Payload: `LMS-STU:1:<token>` — `token` = 48 hex chars (≥192 random bits) in `student_profile.qr_token`,
  unique. No name, index, contact, NIC, password or session data.
- **Identification only**: the resolver needs an authenticated staff principal with `STUDENTS` VIEW in
  the same tenant; it never logs anyone in and never marks attendance. Static until rotated
  (rotation = revocation of printed cards, audited). No Klass-scanner compatibility is claimed.
- Labels (PDF, A4, 3×7 grid, 60 mm cells, QR 32 mm with 4-module quiet zone, black on white): tenant
  name, student full name, index number. Never NIC.
- Bulk dialog: selection is a set of ids that survives search and pagination; header checkbox selects
  **all students matching the current search** (fetched server-side, capped at 500); export sends
  exactly the selected ids; server re-filters to the tenant and refuses >500.

## 8. Log as Student (support impersonation) — see ADR-033

Start (admin tab): dialog names the student (index + name), explains an audited, read-only support
session, requires a reason → `POST /students/{id}/impersonation`. Server checks permission, rejects
nested impersonation, non-student / inactive / pending / removed / cross-tenant targets; ends the
actor's previous active impersonation; stores `impersonation_session` (actor user + actor session,
subject, reason, 30-min absolute expiry, SHA-256 of a 256-bit handoff secret); sets cookie
`lms_impersonation` (HttpOnly, Secure outside dev, SameSite=Strict, Path=`/api/v1/auth/impersonation`);
audits `student.impersonation_started` (actor, subject, reason).

New tab `/impersonate` → `POST /auth/impersonation/token` → in-memory access token (sub = student,
role STUDENT, `sid` = impersonation session, claims `imp=true`, `act` = admin id, exp ≤ session expiry).
Never refreshable past the session expiry; no device_session, so no last-login/device side effects.
JWT filter: an `imp` token is valid only while the session is active, unexpired, the actor account is
ACTIVE and the actor's own device session is still usable. Principal carries `actorUserId`.

**Restrictions:** support sessions are **read-only** — every non-GET request except
`/auth/impersonation/end` returns `403 IMPERSONATION_READ_ONLY`. This blocks password/security
changes, payments, enrollment, exam start/submit, attendance, watch-time and playback-token issuance,
so support browsing is never recorded as student activity. Also refused: `GET …/download-url`
(counts against the student's download limit; matched on the decoded path), and reads never finalize
an overdue exam attempt. Any audit row written during a support request carries
`impersonatorUserId`/`impersonationSessionId` metadata. Demoting or suspending the admin, or the admin
logging out, ends the session (`ACTOR_REVOKED`). TTL key: `app.security.impersonation.ttl` (30m). Student authorization (enrollment,
payment, lesson rules) is evaluated exactly as for the student; admin permissions do not apply.

Exit: banner button → `POST /auth/impersonation/end` (audits `student.impersonation_ended`), clears the
query cache and the tab flag, navigates to `/tenant-admin/students`, where the admin's untouched
refresh cookie restores the admin session (or the login page if that session expired).
**Multi-tab:** the support session lives only in the tab that opened it (sessionStorage marker, no
credential); other tabs stay admin. Starting a new support session ends the previous one; its tab
shows "Support session ended".

## 9. Directory, drawer, full profile behaviours

- Search across the whole authorized dataset server-side; 300 ms debounce; page resets on change.
- Last Login = latest `device_session.issued_at` for the student's account (impersonation never counts),
  formatted in the tenant timezone `YYYY-MM-DD HH:mm` (24 h).
- Pending tab = `pending_approval = true` (self-registration with approval required). Approve action.
- Black List tab: rendered disabled with tooltip "Not available" — no blacklist policy is invented.
- Delete = remove from this tenant (soft): account suspended, sessions revoked, hidden from lists;
  payments, ledger, exams, attendance, audit, enrollment history kept.
- Drawer queries are keyed by student id with AbortSignal; content renders only when the loaded id
  equals the selected id.
- Full profile date range filters: remarks (created_at), exams (attempt submitted date), payments
  (payment timestamp), session views (view date). Enrolled courses are **not** date-filtered (current
  state). Attendance via existing per-course dialog. Errors show error states, never "No records".
- Comprehensive Report → full profile. Send Message → Communications composer with the student
  preselected (`SELECTED_STUDENTS`). Reset Password → existing dialog.

## 10. Today's Classes

Already delivered by CAL-01 (`ClassOccurrenceService`, `GET /public/calendar/today`,
`components/public/todays-classes.tsx`). This work re-verifies it; no second scheduling model.
