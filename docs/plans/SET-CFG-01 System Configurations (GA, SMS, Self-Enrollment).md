# SET-CFG-01 — System Configurations (Klass "Configs" parity)

Status: approved for implementation 2026-11-12 (owner chose scope; "Disable
self-enrollment" explicitly approved as an ADR-028 amendment, see §8).

## 1. Business goal

Close the gaps between Klass Settings → Configs and our tenant configuration:

| Klass setting | Decision |
|---|---|
| Google Analytics Measurement ID | **Build** (`GENERAL.ga_measurement_id`) |
| Disable SMS Notifications | **Build** (`NOTIFICATION.sms_enabled`) |
| Disable Self-Enrollment | **Build** (`STUDENT.self_enrollment_enabled`) — ADR-028 amendment |
| Email From | Deferred (owner did not select; sender address needs SMTP domain verification) |
| Institute Commission | Deferred (tenant default rate feeds settlements — change-controlled) |
| Record Session Views | Not built — watch sessions/progress are always recorded |
| Keep Payment Slip After Acceptance | Not built — slips are always kept ("never delete financial history", duplicate image-hash check) |
| Enable OTP Login | Not built — authentication architecture change, needs its own ADR |
| Enable Device Authentication | Not built — device limits are always enforced (Wave 10) |

## 2. User roles

- **Tenant Admin** (`BRANDING_SETTINGS` CREATE_EDIT): edits the three settings on
  Settings → Configs.
- **Student**: is tracked by the tenant's GA tag on public + student pages; cannot
  self-enroll when the tenant disabled it; stops receiving SMS broadcasts when disabled.
- **Staff with enroll permission**: may still enroll students on their behalf.
- **Platform Admin**: unaffected.

## 3. Acceptance criteria

GA:
- `ga_measurement_id` accepts `G-` + 4–20 uppercase alphanumerics (`^G-[A-Z0-9]{4,20}$`); anything else → 400.
- Unset by default; `null` clears it.
- Exposed on `GET /api/v1/public/tenant-config/home` as `analytics.gaMeasurementId` (public by nature — it is in every page's source).
- The gtag script loads on the tenant's public pages and student portal only when set, never on tenant-admin/teacher/platform-admin screens.

SMS:
- `NOTIFICATION.sms_enabled` boolean, default `true`.
- When `false`, every SMS delivery is finalized `SKIPPED` with reason `SMS_DISABLED`
  before any provider call. EMAIL/WHATSAPP/IN_APP unaffected. Already-SENT rows untouched.
- Authentication OTP/login SMS are not "notifications" and are out of scope (they do not
  use the notification pipeline).

Self-enrollment:
- `STUDENT.self_enrollment_enabled` boolean, default `true` (current behavior).
- When `false`, `POST /api/v1/enrollments/my/courses/{courseId}` → 409
  `SELF_ENROLLMENT_DISABLED` for a non-participant; an existing participant still gets
  the idempotent 200.
- Staff enroll-on-behalf and free-card grants are unaffected.
- Existing participations are never removed; payments/entitlements unaffected.
- Exposed on the public home response as `studentOptions.selfEnrollmentEnabled` so the
  student UI hides the Enroll button (UX only; backend is the enforcement).

## 4. Database impact

None. All three are rows in the existing tenant-owned `tenant_config_entry`
(`tenant_id NOT NULL`, tenant-scoped). No migration.

## 5. Backend impact

- `ConfigPropertyRegistry`: register the three properties (+ a `NOTIFICATION` domain list).
- `TenantConfigApi`: `isSmsEnabled(tenantId)`, `isSelfEnrollmentEnabled(tenantId)` defaults.
- `NotificationDeliveryDispatchService.dispatchOne`: SMS gate → `markSkipped(SMS_DISABLED)`.
- `CourseParticipationService.selfEnroll`: gate after the existing-participant check;
  new `SelfEnrollmentDisabledException` (409).
- `PublicHomeController` / `PublicHomeResponse`: `analytics`, `studentOptions`.

## 6. API contract (additive only)

- `GET/PUT /api/v1/tenant-config/{GENERAL|STUDENT|NOTIFICATION}` — new keys, existing shape.
- `GET /api/v1/public/tenant-config/home` — adds
  `"analytics": {"gaMeasurementId": "G-XXXX" | null}` and
  `"studentOptions": {"selfEnrollmentEnabled": true}`.
- `POST /api/v1/enrollments/my/courses/{courseId}` — new 409 `SELF_ENROLLMENT_DISABLED`.

## 7. Frontend impact (separate step)

- New `tenant-admin/settings/configs` page: GA ID field, SMS and self-enrollment switches;
  link card on the settings index.
- Gtag loader (`next/script`) in the public and student layouts from the public home data.
- Student catalog/course page: hide Enroll when `selfEnrollmentEnabled` is false and
  handle the 409 code.

## 8. Security impact

- GA ID strictly pattern-validated so it can never inject script/URL content; rendered
  only as a gtag config argument.
- Self-enrollment gate is server-side; UI hiding is convenience only.
- **Change control:** the self-enrollment switch amends ADR-028 (enrollment is free and
  payment-independent — still true; a tenant may now restrict *who* creates it to staff).
  Approved by the owner on 2026-11-12; ADR-028 gets an amendment note.
- No secrets touched. Config changes are audit-logged by the existing config service.

## 9. Tenant impact

All reads use `TenantConfigApi` with the tenant from `TenantContext` (request) or the
delivery row's `tenant_id` already set into `TenantContextHolder` by the dispatcher.
No caller-supplied tenant id anywhere.

## 10. Tests

Backend: registry validation (GA pattern, booleans); self-enroll blocked/allowed/
existing-participant/staff path; dispatcher skips SMS only for the disabled tenant
(tenant B with SMS on still sends — cross-tenant); public home exposes the new fields
for the host's tenant only.
Frontend: Playwright for the Configs page save and the gtag tag presence/absence.

## 11. Documentation

`docs/api/tenant-configuration-management.md`, ADR-028 amendment note,
`docs/parity/klass-parity-matrix.md`.
