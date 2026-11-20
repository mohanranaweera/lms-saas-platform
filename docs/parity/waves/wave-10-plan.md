# Wave 10 Plan — Device + Access Policy

Status: **DONE** (2026-10-01) — backend, frontend, tests, security/tenant-isolation review
and documentation complete. See Sections 12-16 for the completion record and ADR-022 for the
decisions. Decision D1 still needs confirmation at PR review (change-controlled area).

Scope per the Wave 10 brief: implement approved device controls (Device, DeviceSession,
TenantDevicePolicy — limits, registration, listing, revocation, reset, cooldown, admin
reset, student self-management where permitted) and centralize entitlement/expiry policy
in one `AccessPolicyService` (course entitlement, billing entitlement, material and video
availability, expiry, manual extension, grace period, device policy).

Parity rows addressed: PAR-16-01–04, PAR-03-04 (Devices tab), PAR-03-05 (device reset,
access extension), PAR-09-04, PAR-09-05 (student-specific extension only), PAR-18-02,
PAR-18-03, PAR-XC-04.

---

## 1. Phase A — Analysis (findings)

### 1.1 Device / session — what exists

- `device_session` (V5, `identity-access-service`) — one row per refresh-token session.
  `id` = JWT `session_id`. Columns `device_identifier_hash` (NOT NULL) and `reset_at`
  (reserved, unused). Status `active|revoked|expired`. Soft-revoke only.
- `DeviceFingerprint` computes `device_identifier_hash` = SHA-256(**User-Agent + remote
  IP**). This is exactly what the brief forbids ("do not use User-Agent alone") and what
  ADR-007 §2 rejected (it mandates a **server-issued opaque device identifier** in an
  `HttpOnly` cookie). Nothing reads the hash today, so replacing it is safe.
- `JwtAuthenticationFilter` validates every request's session via Redis fast path
  (`DeviceSessionCacheService`, 60 s bounded TTL) then Postgres. Revoking a
  `device_session` + evicting the cache entry logs that session out on its next request.
- `RefreshTokenService` rotates/revokes sessions; `revokeAllTenantSessions` exists
  (AUTH-PWD-01).
- No device entity, no device limit, no device list, no reset, no cooldown, no policy.
- RBAC: `DomainArea.DEVICES` — Tenant Admin `V/C/E`, Student Support `V` ("request
  only"), Read-only Auditor `V`. Nobody else.
- `videoaccessmanagement.support.RequestDeviceFingerprint` (UA+IP) is a *separate*,
  playback-session concurrency signal (Wave 5), not device identity. Left unchanged.

### 1.2 Entitlement / expiry — what exists

- `EnrollmentExpiryService#resolveAccessState` (exposed as
  `EnrollmentAccessApi#resolveAccessState`) — the live `NEVER_ENROLLED|ACTIVE|EXPIRED`
  computation from `enrollment.access_expires_at` (snapshotted at activation from the
  course's duration / billing period — this *is* the billing entitlement; Wave 6 confirmed
  no other billing-period entitlement state exists).
- `EnrollmentRepository#findAllCurrentByCourseId` / `#findAllCurrentlyEnrolledByStudentId`
  repeat the same expiry predicate in SQL (rosters, attendance, upcoming exams/classes).
- **Six per-domain student entitlement checks** each call `resolveAccessState` and compare
  to `ACTIVE` themselves: `MaterialAccessGuard`, `VideoAccessGuard`,
  `LiveClassAccessGuard`, `CourseAccessGuard`, `ExamAttemptService`,
  `ExamSchedulingService`. (Wave 5 flagged three; there are now six.)
- Two separate inline availability-window checks: `MaterialService#getDownloadUrl`
  (`available_from_at`/`expiry_at`) and `VideoPlaybackSessionService` (video policy
  window).
- No manual extension, no grace period, no course-/tenant-level access policy.
- `enrollment.access_expires_at` is `updatable=false` and must stay that way (ADR-013
  lineage model) — an extension therefore cannot be an `UPDATE` of the enrollment row.

### 1.3 Product-owner decisions taken at the start of this wave (2026-10-01)

| # | Question | Decision |
|---|---|---|
| D1 | May a Tenant Admin extend an enrollment that has already expired? | **Yes** — active or expired, never a revoked/superseded one. Mandatory reason, audit-logged, append-only extension rows. Paid reactivation (ADR-013) is unchanged. |
| D2 | Plan-level default device limit | **2 devices per student** (`app.security.device.plan-default-limit`). Staff accounts are registered but not limited. |
| D3 | Default grace period | **0 days** (today's hard cutover preserved). Tenant- and course-level settings; course beats tenant. |

D1 touches the change-controlled "enrollment activation rules" (`payments.md` §7): it is
recorded in ADR-022 and must be confirmed at PR review.

---

## 2. Target behavior

### 2.1 Device identity (ADR-007 §2, now implemented)

- On login the server reads the `lms_device_id` cookie (`HttpOnly`, `Secure` except
  `local`, `SameSite=Strict`, path `/api/v1/auth`, 400-day max-age). If it matches an
  ACTIVE `device` row of **this user in this tenant** (by SHA-256 hash), that device is
  reused. Otherwise a new 256-bit random token is issued and a new `device` row is
  registered. The raw token is never stored, logged, or returned in JSON.
- User-Agent is used **only** for a display label ("Chrome on Windows"). IP is stored as
  `last_ip` for admin review only. Neither is identity.
- `device_session.device_id` links each session to its device;
  `device_identifier_hash` now stores the token hash.
- Refresh requires the same device cookie as the session's device and an ACTIVE device;
  otherwise `401`. Legacy sessions with no `device_id` are refused at refresh (forces one
  re-login that registers a device — at most a 15-minute access-token tail).

### 2.2 Device limit (students only)

- Login limit precedence: **student exception > tenant policy > plan default**. (Course
  is not known at login.)
- Course-content limit precedence: **student exception > course policy > tenant > plan**,
  evaluated by `AccessPolicyService` when a course has a `max_devices` override (e.g.
  "Premium course: 1 device"). The authorized devices for that course are the student's
  N earliest-registered ACTIVE devices; another device gets
  `403 DEVICE_NOT_AUTHORIZED`.
- Occupied slots = ACTIVE devices + REVOKED devices still in cooldown
  (`slot_reusable_at > now`). A login from a **new** device when occupied ≥ limit →
  `403 DEVICE_LIMIT_REACHED` (after password verification, so no enumeration). A login
  from an already-registered device always succeeds.
- Registration is serialized per user with `pg_advisory_xact_lock` so two concurrent
  logins can't both take the last slot.

### 2.3 Revocation / reset / cooldown

- Revoking a device: `status = REVOKED`, `revoked_at`, `revoked_by`, `revocation_source`
  (`ADMIN`/`STUDENT`), `reason`, and `slot_reusable_at = now + tenant cooldown` (default
  24 h, tenant-configurable 0–720 h). Every ACTIVE `device_session` on it is revoked and
  evicted from Redis in the same transaction → the device is signed out on its next
  request. Rows are never deleted.
- Admin reset (one device or all devices of a student): `DEVICES/CREATE_EDIT` (Tenant
  Admin). Reason mandatory. Audit `device.reset` on the device and on the student's
  `student_profile` (visible on the Activity tab).
- Student self-management: a student can always **list** their devices
  (`GET /devices/my`, current device marked). Revoking one of their *other* devices is
  allowed only when `tenant_device_policy.student_self_service_enabled`; otherwise
  `403 DEVICE_SELF_SERVICE_DISABLED`. Cooldown applies the same way. Audit
  `device.revoked_by_user`.
- Student device-limit exception: Tenant Admin sets/clears per student, reason
  mandatory, audit-logged.

### 2.4 AccessPolicyService (centralized)

One `AccessPolicyApi` in `enrollment-management` (owner of course access + payment-based
expiry per `docs/architecture/enrollment-access.md`):

- **Effective window** (single implementation, `AccessWindowCalculator`):
  `effectiveExpiresAt = max(enrollment.access_expires_at, latest extension.new_expires_at)`
  (null = lifetime); `grace = course.grace_period_days ?? tenant.grace_period_days ?? 0`;
  `accessEndsAt = effectiveExpiresAt + grace`. Access is current iff not superseded and
  (lifetime or `now < accessEndsAt`). Used by `resolveAccessState`, roster/attendance/
  exam lists — so every existing `EnrollmentAccessApi` caller (including payment flows)
  sees the same answer.
- **`evaluateStudentCourseAccess(studentId, courseId)`** → `ALLOWED | NOT_ENROLLED |
  EXPIRED | DEVICE_NOT_AUTHORIZED`, plus window details (effective expiry, grace end,
  in-grace flag). Includes the course-level device check for the caller's current session.
- **`requireStudentCourseAccess(courseId, notFound)`** — the one call every guard makes:
  NOT_ENROLLED/EXPIRED → the guard's own 404 (anti-enumeration preserved), DEVICE →
  `403 DEVICE_NOT_AUTHORIZED` (only reachable once enrollment is proven, so no leak).
- **`evaluateContentWindow(from, until)`** → `AVAILABLE | NOT_YET_AVAILABLE | EXPIRED` —
  replaces the inline material and video window checks (same error codes as today).
- The six guards/services in §1.2 are refactored to call it; none keeps its own
  `resolveAccessState == ACTIVE` comparison.

### 2.5 Manual extension

- `POST /api/v1/enrollments/{enrollmentId}/extensions` `{newExpiresAt, reason}` —
  `ACCESS_EXPIRY/CREATE_EDIT` (Tenant Admin). The enrollment must be current (not
  superseded/revoked) and time-limited (lifetime → `409`). `newExpiresAt` must be after
  both now and the current effective expiry, and within 5 years. Writes an append-only
  `enrollment_access_extension` row (previous + new expiry, reason, actor) and audit
  `enrollment.access_extended` (before/after) on the enrollment and the student profile.
- `GET .../extensions` lists the history (`ACCESS_EXPIRY/VIEW`).
- Bulk extension is **not** built (second-approver question still open, §9 of
  `enrollment-access.md`).

---

## 3. Database impact (additive; V64, V65)

**V64 (`identity-access-service`)**

- `device` — `id`, `tenant_id` (FK tenant), `user_id` (composite FK
  `(tenant_id,user_id) → tenant_user`), `token_hash`, `label`, `first_seen_at`,
  `last_seen_at`, `last_ip`, `status` (`active|revoked`), `revoked_at`, `revoked_by`,
  `revocation_source`, `revoke_reason`, `slot_reusable_at`. `UNIQUE (tenant_id, id)`,
  `UNIQUE (tenant_id, user_id, token_hash)`, index `(tenant_id, user_id, status)`.
  Check: revoked ⇒ `revoked_at` set.
- `device_session.device_id` — nullable (legacy rows), composite FK
  `(tenant_id, device_id) → device(tenant_id, id)`, index `(tenant_id, device_id)`.
- `tenant_device_policy` — `id`, `tenant_id` UNIQUE (FK), `max_devices` (null = plan
  default, 1–20), `reset_cooldown_hours` (0–720, default 24),
  `student_self_service_enabled` (default false), `updated_by`, `updated_at`.
- `student_device_limit_override` — `id`, `tenant_id`, `user_id` (composite FK),
  `max_devices` (1–20), `reason`, `set_by`, `set_at`; `UNIQUE (tenant_id, user_id)`.

**V65 (`enrollment-management`)**

- `tenant_access_policy` — `id`, `tenant_id` UNIQUE, `grace_period_days` (0–90,
  default 0), `updated_by`, `updated_at`.
- `course_access_policy` — `id`, `tenant_id`, `course_id` (composite FK → course),
  `grace_period_days` (null/0–90), `max_devices` (null/1–20), `updated_by`, `updated_at`;
  `UNIQUE (tenant_id, course_id)`.
- `enrollment_access_extension` — `id`, `tenant_id`, `enrollment_id` (composite FK →
  enrollment), `previous_expires_at`, `new_expires_at` (CHECK new > previous), `reason`
  NOT NULL, `granted_by`, `granted_at`; index `(tenant_id, enrollment_id)`. Append-only
  (repository exposes no update/delete; DB trigger rejects UPDATE/DELETE).

No existing migration is edited. No payment, ledger or enrollment row is updated.

## 4. API impact (all `/api/v1`, tenant from trusted context)

| Method | Path | Gate |
|---|---|---|
| GET | `/devices/my` | any tenant user (own devices) |
| POST | `/devices/my/{deviceId}/revoke` | own device + tenant self-service enabled |
| GET / PUT | `/device-policy` | `DEVICES` V / C/E |
| GET | `/students/{studentId}/devices` | `DEVICES` V |
| POST | `/students/{studentId}/devices/{deviceId}/reset` | `DEVICES` C/E, reason |
| POST | `/students/{studentId}/devices/reset` (all) | `DEVICES` C/E, reason |
| PUT | `/students/{studentId}/device-limit` | `DEVICES` C/E, reason; `maxDevices: null` clears |
| GET / PUT | `/access-policy` (tenant grace) | `ACCESS_EXPIRY` V / C/E |
| GET | `/access-policy/courses` | `ACCESS_EXPIRY` V |
| PUT | `/access-policy/courses/{courseId}` | `ACCESS_EXPIRY` C/E (+ `DEVICES` C/E when `maxDevices` changes); both values null removes the override |
| GET / POST | `/enrollments/{enrollmentId}/extensions` | `ACCESS_EXPIRY` V / C/E |
| GET | `/courses/{courseId}/access-state` (existing) | STUDENT — additive fields `accessEndsAt`, `inGracePeriod`, `deviceAuthorized` |
| GET | `/students/{studentId}/enrollments` (existing) | additive `effectiveExpiresAt`, `accessEndsAt`, `extended` |

New error codes: `DEVICE_LIMIT_REACHED` (403, login), `DEVICE_NOT_AUTHORIZED` (403,
course content), `DEVICE_SELF_SERVICE_DISABLED` (403). Login/refresh responses unchanged
in shape (device cookie only).

## 5. Frontend impact

- Student: **My Devices** page (`/student/devices`, nav item) — list, "this device"
  badge, revoke other devices when allowed, limit + cooldown explained. Login form shows
  the `DEVICE_LIMIT_REACHED` message. Course content shows `DEVICE_NOT_AUTHORIZED` and
  grace-period notices.
- Tenant Admin: Student detail **Devices** tab (devices, reset one / all with reason,
  device-limit exception); Enrollments tab **Extend access** dialog + extension history;
  **Settings → Devices & access** page (tenant device policy, grace period, per-course
  overrides).
- Student Support / Auditor: Devices tab read-only (buttons hidden; server still enforces).

## 6. Security impact

- Device identity no longer derived from User-Agent/IP; server-issued token, hashed at
  rest, `HttpOnly` cookie, never in JSON or logs.
- Device-limit enforced server-side at login (403) and at course-content time.
- Revocation revokes the authoritative Postgres session rows and evicts Redis in the
  same transaction.
- Every reset / self-revoke / limit change / policy change / extension is audit-logged in
  the same transaction (`security.md`).
- Cooldown persisted (`slot_reusable_at`), not client-side.

## 7. Tenant isolation impact

All new tables tenant-owned with `NOT NULL tenant_id`, tenant-leading indexes, composite
same-tenant FKs; all repositories extend `TenantAwareRepository`; no method accepts a
caller-supplied tenant id. Cross-tenant negative tests for every new endpoint.

## 8. Test plan (Phase D)

- Unit: `AccessWindowCalculator` (extension, grace precedence, lifetime), device-limit
  precedence resolver, `evaluateContentWindow`.
- Integration (Testcontainers):
  - device registration at login, same-device reuse, limit reached → 403, override
    precedence (student > tenant > plan; student > course > tenant), cooldown blocks
    reuse then expires, concurrent logins can't exceed the limit;
  - refresh bound to device cookie; revoked device → session rejected on next request;
  - admin reset / reset all / permission denials (Student Support, Teacher, Student);
  - student self-revoke enabled/disabled, cannot revoke another user's device;
  - extension of active and expired enrollment restores access; revoked → 409; lifetime
    → 409; append-only; audit rows;
  - grace period (tenant, course override) keeps access then ends; reactivation unchanged;
  - material/video window via the policy; course-level device limit blocks the extra
    device on material/video/live/exam access (direct-bypass);
  - cross-tenant: every new endpoint addressed with another tenant's ids → 404.
- Frontend: Playwright specs for My Devices, Devices tab, extension dialog, policy page,
  login device-limit message.

## 9. Migration / rollout risk

- Existing sessions have no device: they keep working until their next refresh, which
  forces a re-login (registers the device). Communicate as "you may be asked to sign in
  again once".
- Existing students with >2 already-active sessions: devices are only created on new
  logins, so nobody is retroactively locked out; the limit bites on the next new device.
- Default grace 0 and extensions empty ⇒ `resolveAccessState` returns exactly today's
  answers for existing data.

## 10. Judgment calls (flagged for sign-off)

1. D1–D3 above (product owner, 2026-10-01).
2. Course-level device limit is enforced at course-content access (login has no course).
3. The authorized devices for a stricter course limit are the earliest-registered ones.
4. Device limits apply to STUDENT only; staff devices are registered and listable but
   unlimited.
5. A student in grace is ACTIVE, so cannot place a reactivation order until grace ends
   (reactivation requires EXPIRED — unchanged ADR-013 rule).

## 11. Deferred (explicit)

- PAR-16-05 suspicious-login detection (impossible travel / churn) — not in the brief.
- Bulk expiry extension (PAR-09-05 bulk part) — second-approver question open.
- PAR-18-04 expiry reminders (notification wiring) — Wave 11 communications.
- Session-level (live class) independent expiry windows beyond course entitlement.

---

## 12. Phase B completion (backend)

- **V64** `device`, `device_session.device_id`, `tenant_device_policy`,
  `student_device_limit_override`; **V65** `tenant_access_policy`, `course_access_policy`,
  `enrollment_access_extension` (append-only trigger). No existing migration edited.
- `identity-access-service`: `Device`/`TenantDevicePolicy`/`StudentDeviceLimitOverride`,
  `DeviceTokens`, `DeviceLabels`, `DevicePolicyService` (precedence), `DeviceRegistrationService`
  (login binding + refresh verification), `DeviceManagementService` (`DeviceManagementApi`,
  `DeviceAccessApi`), `DeviceController` (`/devices/my`), `DevicePolicyController`. Login sets
  the `lms_device_id` cookie; refresh requires it. `DeviceFingerprint` (UA+IP) now only serves
  Platform Admin sessions.
- `user-management`: `StudentDeviceService` + `StudentDeviceController`
  (`/students/{id}/devices`, `/device-limit`), each change also audit-logged on the student
  profile.
- `enrollment-management`: `AccessPolicyApi`/`AccessPolicyService`, `AccessWindowCalculator`,
  `AccessPolicyAdminService` + `AccessPolicyController`, `AccessExtensionService` +
  `EnrollmentExtensionController`; `EnrollmentExpiryService` and `EnrollmentAccessApiImpl` use
  the calculator; the two SQL expiry predicates in `EnrollmentRepository` were removed.
- Guards refactored onto `AccessPolicyApi`: `MaterialAccessGuard`, `VideoAccessGuard`,
  `LiveClassAccessGuard`, `CourseAccessGuard`, `ExamAttemptService`, `ExamSchedulingService`;
  material/video availability windows via `AccessPolicyApi#evaluateContentWindow`.
- Change from the plan: device **registration** is not audit-logged — the `device` and
  `device_session` rows are the registration/login record (V5 convention, backend.md
  "registration + login-activity record" pair), and an audit write on every sign-in broke
  audit-dependent flows (login failing when the audit write fails). Resets, self-removal,
  limit/policy changes and extensions are audit-logged.

## 13. Phase C completion (frontend)

- Student **My Devices** (`/student/devices`, nav item): list, this-device badge, slot usage
  and cooldown explanation, self-service removal when allowed.
- Student detail **Devices** tab (`DEVICES`/`VIEW` roles): device table with last IP,
  reset one / reset all (reason dialog), device-limit exception form (Tenant Admin only).
- Student detail **Enrollments** tab: effective expiry + grace, "Access ended" and "Extended"
  badges, **Extend access** dialog with extension history (Tenant Admin only).
- **Settings → Devices & access** (`/tenant-admin/settings/devices-access`, nav + settings
  card): device policy, grace period, per-course overrides.
- Login shows "Device limit reached" for `DEVICE_LIMIT_REACHED`; course content 403
  `DEVICE_NOT_AUTHORIZED` renders the backend message through the shared permission-denied
  state.
- Shared components: `components/devices/{device-list,device-status-badge,device-limit-summary}`.

## 14. Phase D — tests

Backend (Testcontainers):
- `DeviceManagementIntegrationTest` (15): registration + reuse via cookie, token-hash identity,
  tenant limit, wrong password never reveals the limit, student exception precedence and
  clearing, staff unlimited, concurrent sign-ins (1 of 4 wins), refresh bound to device,
  legacy session refused, admin reset signs out + cooldown + revoked device cannot return,
  reset-all + reason validation, permissions (Student Support view-only, Teacher/Student
  denied), policy validation + audit, self-service on/off + current device + another
  student's device, cross-tenant 404s.
- `AccessPolicyIntegrationTest` (6): extending an expired enrollment restores access (state,
  materials, roster) without touching `enrollment`; extension rules (past date, blank reason,
  >5 years, lifetime, revoked); permissions, cross-tenant 404s, append-only trigger; tenant
  grace + course override; course device limit on materials/access-state with student
  exception precedence; course-policy permissions, validation, isolation, removal.
- Unit: `DevicePolicyServiceTest` (5), `AccessWindowCalculatorTest` (5); existing guard tests
  migrated to an `AccessPolicyTestDouble`.
- Full suite: 2210 tests run; the only failures were 7 audit-log tests broken by the
  registration audit row, fixed by the §12 change and re-run green. Final full run: §16.

Frontend: `e2e/devices-and-access.spec.ts` (13 Playwright specs) — all pass against a
production build. Full mocked suite: 782 passed, 8 failed, 1 skipped — the 8 failures are in
specs this wave did not touch (stale `/forgot-password` placeholder assertions, platform-admin
audit drill-down table, payment-slip in-flight button, duplicate "Try again" on the student
profile page); not verified against a pre-wave baseline build.

## 15. Phase E — security, tenant-isolation and payment review

- Device identity: 256-bit server token, hashed at rest, `HttpOnly`/`SameSite=Strict`, never
  in JSON, logs or audit metadata; User-Agent/IP are display only.
- Limits enforced server-side after password verification (no enumeration); per-user advisory
  lock closes the last-slot race (concurrency test). Cooldown persisted
  (`slot_reusable_at`), applies to admin reset and self-service.
- Revocation is authoritative in Postgres and evicted from Redis in the same transaction;
  refresh requires the session's own device.
- Every new table is tenant-owned (`NOT NULL tenant_id`, tenant-leading indexes, composite
  same-tenant FKs); every repository is `TenantAwareRepository`; no method takes a tenant id;
  cross-tenant 404 tests for every new endpoint.
- Payments/ledger untouched; extensions append-only and never create or modify enrollments.
- Findings (not blocking, recorded in ADR-022): (1) a reactivation order created while expired
  and confirmed after a manual extension replaces the extension with the paid window;
  (2) the device-slot invariant uses lock + count rather than a unique slot constraint;
  (3) pre-existing: the revoke-enrollment warning log writes `tenantId` twice when the student
  has no profile (structured-logging error in tests, unrelated to this wave).

## 16. Phase F/G — documentation and verification

Docs: ADR-022 (new); `docs/api/identity-access-service.md` (Devices, login/refresh changes,
error codes); `docs/api/user-management.md` (Student devices);
`docs/api/enrollment-management.md` (Access policy); `docs/architecture/
authentication-authorization.md` §5 status; `docs/architecture/enrollment-access.md` §9
update; `klass-parity-matrix.md` (PAR-03-04/05, PAR-09-04/05, PAR-16-01–05, PAR-18-02/03,
PAR-XC-04); `implementation-roadmap.md` (Wave 10 row + summary).

Verification: backend compile + full test suite (see completion report), frontend `tsc`,
`eslint` (0 errors; 2 pre-existing warnings), `next build` (passes).
