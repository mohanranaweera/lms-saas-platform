# STU-PROF-01 — Configurable Student Profile Fields

Status: **Backend + frontend implemented, docs updated (2026-09-28)** — awaiting review/commit
Branch: `feat/configurable-student-profile-fields`
Related: MVP-006 Student Management (resolves plan §21 item 3, "exact guardian/school
field list"), Wave 3 registration expansion (V44/V45), PAY-GW-01 billing address (V58).

---

## 1. Business goal

Each institute collects different information about its students (NIC, AL stream,
guardian, tute-delivery phone numbers, ...). Today the field list is hard-coded: the
registration form captures name/email/password plus six optional columns
(`guardian_name`, `guardian_phone`, `school`, `grade`, `stream`, `mobile`), gated by five
`require_*` booleans in `ConfigDomain.STUDENT`, and students can only edit their `name`
and billing address afterwards.

This module lets a **Tenant Admin choose, per field, from a platform-defined catalogue**:

- **Enabled** — the field exists for this tenant's students (shown on the profile page).
- **Required** — the student must fill it in.
- **Ask at registration** — the field also appears on the public registration form.

Registration stays short (only the fields marked "ask at registration"); students
complete the remaining enabled fields from **My Profile**. Required-but-missing fields
are shown to the student as "complete your profile".

## 2. User roles

| Role | Interaction |
|---|---|
| **Tenant Admin** | Settings → *Student fields* table (Field / Enabled / Required / Ask at registration). Saves the whole table at once. |
| **Staff with `STUDENTS` VIEW / CREATE_EDIT** | Sees the enabled fields on the student detail *Profile* tab; can view (VIEW) and correct (CREATE_EDIT) a student's values. |
| **Student** | Fills "ask at registration" fields on the public register page; edits all enabled fields on *My Profile*; sees a banner while required fields are missing. |
| **Prospective student (anonymous)** | Registration form renders from the public registration policy (tenant resolved from host, as today). |
| **Teacher** | No access in v1. |
| **Platform Admin** | No new UI. Catalogue is code-defined, so it is changed by a release, not per tenant. |

## 3. Acceptance criteria

### 3.1 Field catalogue (platform-defined)

Fields are a fixed, code-defined catalogue (`StudentProfileField` enum). Tenants toggle
them; they cannot invent new fields in v1 (see §12, decision 5).

| Key | Label | Type / validation | Storage (`student_profile`) |
|---|---|---|---|
| — | Email | locked: always enabled + required, credential on `tenant_user` | (existing, identity module) |
| — | Full name | locked: always enabled + required | `name` (existing) |
| `MOBILE` | Mobile number | phone, ≤ 50 | `mobile` (existing) |
| `SCHOOL` | School | text ≤ 255 | `school` (existing) |
| `GRADE` | Grade | text ≤ 50 | `grade` (existing) |
| `AL_STREAM` | A/L stream | text ≤ 50 | `stream` (existing) |
| `AL_YEAR` | A/L year | integer 1990–2100 | `al_year` (new, SMALLINT) |
| `GUARDIAN` | Guardian | composite: name ≤ 255 + phone ≤ 50 (both required when required) | `guardian_name`, `guardian_phone` (existing) |
| `BIRTHDAY` | Birthday | date, not in future, not before 1900 | `date_of_birth` (new, DATE) |
| `FIXED_TELEPHONE` | Fixed telephone | phone | `fixed_phone` (new) |
| `WHATSAPP_NUMBER` | WhatsApp number | phone | `whatsapp_number` (new) |
| `TELEGRAM_NUMBER` | Telegram number | phone | `telegram_number` (new) |
| `ADDRESS_LINE_1` | Address line 1 | text ≤ 255 | `address_line` (existing, V58) |
| `ADDRESS_LINE_2` | Address line 2 | text ≤ 255 | `address_line_2` (new) |
| `CITY` | City | text ≤ 100 | `city` (existing, V58) |
| `POSTAL_CODE` | Postal code | text ≤ 20 | `postal_code` (new) |
| `COUNTRY` | Country | ISO-3166 alpha-2 from a fixed list | `country_code` (new, VARCHAR(2)) |
| `NIC_NUMBER` | NIC number | text ≤ 20, **sensitive** | `nic_number` (new) |
| `PASSPORT_NUMBER` | Passport number | text ≤ 20, **sensitive** | `passport_number` (new) |
| `DRIVING_LICENSE_NUMBER` | Driving licence number | text ≤ 20, **sensitive** | `driving_license_number` (new) |
| `HAS_SIBLINGS` | Has siblings | boolean (yes/no) | `has_siblings` (new, BOOLEAN) |
| `PROFESSION` | Profession | text ≤ 255 | `profession` (new) |
| `SPECIAL_TALENT` | Special talent | text ≤ 255 | `special_talent` (new) |
| `UNIVERSITY` | University | text ≤ 255 | `university` (new) |
| `LEARNING_MEDIUM` | Learning medium | text ≤ 50 | `learning_medium` (new) |
| `TUTE_DELIVERY_PHONE_1` | Tute delivery phone 1 | phone | `tute_delivery_phone_1` (new) |
| `TUTE_DELIVERY_PHONE_2` | Tute delivery phone 2 | phone | `tute_delivery_phone_2` (new) |
| `REFERRAL_CODE` | Referral code | text ≤ 50, stored only (no referral logic) | `referral_code` (new) |

"Phone" = `^\+?[0-9 ()-]{6,20}$`. All text is trimmed; blank → `null`.

**Deliberately excluded from the screenshot's list:**
- **Course** — choosing a course at registration is an enrollment action and touches the
  change-controlled enrollment-activation rules. Out of scope (decision 2).
- **First name / Last name** — the platform stores a single `name`. Splitting it changes
  the identity/profile model everywhere (decision 1). "Full name" is the locked field.
- **Email** — the login credential; always required, not toggleable.

### 3.2 Tenant Admin configuration

- AC-1 The table lists every catalogue field with its current Enabled / Required / Ask-at-registration state; Email and Full name are shown as locked (checked, disabled).
- AC-2 `Required` and `Ask at registration` are only selectable when `Enabled` is checked; unchecking `Enabled` clears both (UI and server — server rejects `required=true` or `collectAtRegistration=true` with `enabled=false` → 400).
- AC-3 Save replaces the whole configuration atomically; an unknown field key → 400; partial saves never happen.
- AC-4 A tenant that has never saved gets the defaults (§4.3), which reproduce today's behaviour exactly (no regression for existing tenants).
- AC-5 Each save writes an audit log entry (actor, tenant, before/after of the changed flags).
- AC-6 Disabling a field never deletes stored student values; they are hidden from students and forms and reappear if re-enabled (decision 4).
- AC-7 Users without the settings permission get 403 on both GET and PUT; the Settings tab is hidden for them.

### 3.3 Registration

- AC-8 The public register page renders exactly the fields with `collectAtRegistration=true`, marking required ones.
- AC-9 `POST /api/v1/students/register` rejects (400, per-field errors) a missing required registration field, using the tenant's config resolved server-side — never a client claim.
- AC-10 Values submitted for fields not collected at registration are ignored (not stored).
- AC-11 Existing OTP / approval / self-registration-disabled behaviour is unchanged.

### 3.4 Student profile

- AC-12 *My Profile* shows every enabled field, grouped (Personal, Contact, Address, Identity, Education, Family & other), with current values and required markers.
- AC-13 Saving validates types/lengths for all submitted fields and enforces that every **required** field is non-empty in the resulting profile → 400 with per-field errors otherwise.
- AC-14 A student can only submit values for enabled fields; a disabled/unknown key → 400.
- AC-15 The response includes `missingRequiredFields`; while non-empty, the student dashboard and profile page show a "Complete your profile" banner linking to the form.
- AC-16 Making a field required later does **not** lock out existing students or block any action; they just see the banner (decision 3).
- AC-17 The existing billing-address card (`/me/billing-address`) keeps working unchanged. `ADDRESS_LINE_1` and `CITY` write the same columns, so filling them in either place satisfies PayHere. PayHere's requirement is independent of this config: if a tenant disables those fields, checkout still asks for the billing address.
- AC-18 A student can never read or write another student's profile (identity from the authenticated context only).

### 3.5 Staff view/edit

- AC-19 The student detail *Profile* tab shows enabled fields; sensitive identity numbers are shown in full only to staff with `STUDENTS` CREATE_EDIT (Tenant Admin, Student Support) and masked to the last four characters for view-only staff (security review S-1).
- AC-20 Staff edits (`STUDENTS` CREATE_EDIT) validate type/length but **not** required-ness (admin-created / imported students may legitimately be incomplete).
- AC-21 Staff edits to student profile values write an audit log entry listing the changed field keys (values of sensitive fields are not written to the log).

### 3.6 Edge cases

- Empty config (tenant disabled everything): registration shows only name/email/password; profile page shows name + billing address card + "No additional details are collected by your institute".
- A catalogue key removed in a future release: stored setting rows for unknown keys are ignored on read.
- Concurrent admin saves: last write wins (no optimistic lock in v1); every save is audit-logged with before/after flags.

## 4. Database impact

One new Flyway migration, **`V59__create_student_profile_field_settings.sql`** (next free
number; V58 is the latest). No existing migration is edited.

### 4.1 New table `student_profile_field_setting` (tenant-owned)

| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | |
| `tenant_id` | UUID NOT NULL, FK → `tenant(id)` | |
| `field_key` | VARCHAR(64) NOT NULL | `StudentProfileField` name |
| `enabled` | BOOLEAN NOT NULL | |
| `required` | BOOLEAN NOT NULL | `CHECK (NOT required OR enabled)` |
| `collect_at_registration` | BOOLEAN NOT NULL | `CHECK (NOT collect_at_registration OR enabled)` |
| `created_at/by`, `updated_at/by` | per `Auditable` | |

- `UNIQUE (tenant_id, field_key)` — tenant-leading, doubles as the lookup index (the only query is "all rows for tenant").
- No FK into other tenant-owned tables, so no composite-FK concern.

**Why a table rather than a JSON value in `tenant_config_entry`:** DB-level CHECKs,
per-field uniqueness, simple typed reads, and no first-ever use of `ConfigValueType.JSON`.

### 4.2 New nullable columns on `student_profile`

`date_of_birth DATE`, `fixed_phone`, `whatsapp_number`, `telegram_number`,
`tute_delivery_phone_1`, `tute_delivery_phone_2` (VARCHAR(50)), `address_line_2`,
`profession`, `special_talent`, `university` (VARCHAR(255)), `postal_code` (VARCHAR(20)),
`country_code VARCHAR(2)`, `nic_number`, `passport_number`, `driving_license_number`
(VARCHAR(20)), `has_siblings BOOLEAN`, `al_year SMALLINT` (+ `CHECK al_year BETWEEN 1990 AND 2100`),
`learning_medium VARCHAR(50)`, `referral_code VARCHAR(50)`.

All nullable, for the same reason as V44: required-ness is per tenant and enforced in the
API layer. `student_profile` already has `tenant_id NOT NULL` + tenant-leading indexes.
Purely additive; no existing row is modified; no backfill.

**Why typed columns rather than a key/value (EAV) table:** the catalogue is fixed and
known, types differ (date, boolean, integer), and typed columns keep validation,
reporting and exports simple. Tenant-defined custom fields (decision 5) would
be an EAV table added later.

### 4.3 Defaults (no row for a field)

Resolved in code, so existing tenants behave exactly as today:

| Field | enabled | required | collectAtRegistration |
|---|---|---|---|
| `GUARDIAN`, `SCHOOL`, `GRADE`, `AL_STREAM`, `MOBILE` | true | legacy `require_*` flag | true (the register page always showed them) |
| `ADDRESS_LINE_1`, `CITY` | true | false | false |
| everything else | false | false | false |

When a tenant first saves the table, rows for every catalogue field are written and the
legacy `require_guardian_info/require_school/require_grade/require_stream/require_mobile`
flags stop being read for that tenant. They stay in `ConfigPropertyRegistry` (marked
deprecated) so nothing existing breaks; removing them is a later cleanup.

## 5. Backend impact

As built. The field settings live in **`tenant-management`** (tenant configuration,
foundational module) and `user-management` consumes them through
`tenantmanagement.api` — the existing dependency direction, so no module cycle.

**`tenant-management`** (owns the catalogue and per-tenant settings):
- `api.StudentProfileField` enum (key, label, group, type, maxLength, sensitive, locked, legacy `require_*` key, `defaultPolicy`), `StudentProfileFieldType`, `StudentProfileFieldGroup`, `StudentProfileFieldPolicy` record, `StudentProfileFieldConfigApi`.
- `domain.StudentProfileFieldSetting` + `repository.StudentProfileFieldSettingRepository` (`TenantAwareRepository`; no caller-supplied tenant id).
- `service.StudentProfileFieldConfigService` — effective policies (rows → legacy flags → defaults); validated save that writes a row per non-locked field and one audit entry.
- `web.StudentProfileFieldSettingsController` — `GET/PUT /api/v1/tenant-config/student-profile-fields`.
- `PublicStudentRegistrationPolicyController` / response — additive `registrationFields`; legacy booleans now derived from the effective settings (required AND asked at registration).
- `ConfigPropertyRegistry` — five `require_*` properties marked deprecated (javadoc only).

**`user-management`** (owns the student values):
- `StudentProfile` — V59 columns + `readField`/`writeField` per catalogue field; `GuardianDetails` value.
- `StudentProfileFieldValueParser` — per-type validation/normalisation (text, phone, date, year, boolean, country, guardian).
- `StudentProfileFieldsService` — self view/save (required enforced), staff view/save (required not enforced, audited), registration validation.
- `StudentController` — `/me/profile` and `/{id}/profile-fields`.
- `StudentRegistrationService` — hard-coded `validateRequiredFields` replaced by the effective settings; `StudentRegistrationRequest.profileFields` (legacy properties still mapped).

**Audit actions:** `student_profile_fields.updated` (target `tenant`, before/after flags per changed field) and `student.profile_fields_updated` (target `student_profile`, changed field keys only — never values).

No change to payment, ledger, enrollment, identity, or auth code.

## 6. API contract

All errors use the existing error envelope (`VALIDATION_ERROR` with `fieldErrors[]`,
`FORBIDDEN`, `CONFLICT`). Field keys are the enum names above.

### 6.1 Tenant Admin configuration

`GET /api/v1/tenant-config/student-profile-fields` — same permission as the existing tenant config GET (`BRANDING_SETTINGS` VIEW; see decision 6)
```json
{
  "fields": [
    { "key": "NIC_NUMBER", "label": "NIC number", "group": "IDENTITY", "type": "TEXT",
      "enabled": true, "required": false, "collectAtRegistration": false, "locked": false }
  ] }
```
Locked entries (`EMAIL`, `FULL_NAME`) are included with `locked: true` for display only.

`PUT /api/v1/tenant-config/student-profile-fields` — `BRANDING_SETTINGS` CREATE_EDIT
```json
{
  "fields": [ { "key": "NIC_NUMBER", "enabled": true, "required": true, "collectAtRegistration": false } ] }
```
Keys left out keep their current state. 200 → same shape as GET. 400 unknown key / invalid combination / locked key or duplicate key; 403 no permission.

### 6.2 Public registration policy (additive change to an approved contract)

`GET /api/v1/public/tenant-config/student-registration-policy` — existing fields unchanged, plus:
```json
"registrationFields": [ { "key": "MOBILE", "label": "Mobile number", "type": "PHONE", "required": true } ]
```
Only fields with `collectAtRegistration=true`. Sensitive/internal config is never exposed.

### 6.3 Registration (additive)

`POST /api/v1/students/register` — existing body plus optional
`"profileFields": { "MOBILE": "+94 77 123 4567", "BIRTHDAY": "2008-04-01" }`.
The legacy top-level `guardianName/.../mobile` properties are still accepted and mapped.
400 with `fieldErrors[].field = "profileFields.MOBILE"` for missing/invalid values.

### 6.4 Student self-service

`GET /api/v1/students/me/profile` — authenticated student
```json
{ "fields": [ { "key": "BIRTHDAY", "label": "Birthday", "group": "PERSONAL", "type": "DATE",
                "required": true, "value": "2008-04-01" },
              { "key": "GUARDIAN", "type": "GUARDIAN", "required": false,
                "value": { "name": "…", "phone": "…" } } ],
  "missingRequiredFields": ["NIC_NUMBER"] }
```
`PUT /api/v1/students/me/profile` — body `{ "values": { "KEY": value, ... } }`; keys left out stay unchanged; `null` clears a value. 200 → same as GET. 400 on validation / disabled key / required left empty.

### 6.5 Staff

`GET /api/v1/students/{id}/profile-fields` — `STUDENTS` VIEW → same shape as 6.4.
`PUT /api/v1/students/{id}/profile-fields` — `STUDENTS` CREATE_EDIT → same body; required-ness not enforced. A student id from another tenant → 404.

Existing `GET/PATCH /students/me`, `GET/PUT /students/me/billing-address`, `PATCH /students/{id}` are unchanged.

`review-api-contract` runs on §6 before backend implementation starts.

## 7. Frontend impact

- **Tenant Admin → Settings**: new *Student fields* settings page (`/tenant-admin/settings/student-fields`, also in the Institute Configuration nav) with the fields table (Field / Enabled / Required / Ask at registration), grouped, sticky **Save changes**, dirty-state guard, loading/error/403 states. Linked from `settings/page.tsx`. Plain table + native checkboxes (no shadcn checkbox exists in this app yet).
- **Public register page** (`(auth)/register/page.tsx`): render dynamic fields from `registrationFields` with a typed field renderer (text, phone, date, boolean, country select, guardian pair). Zod schema built from the policy (`lib/validation/student-registration.ts`), server errors mapped per field.
- **Student → My Profile** (`(student)/student/profile/page.tsx`): new *Additional details* form (grouped sections, same renderer), shown above the existing billing-address card. "Complete your profile" banner on profile and student dashboard when `missingRequiredFields` is non-empty.
- **Tenant Admin → Student detail → Profile tab** (`profile-tab.tsx`): show enabled fields; edit dialog for `STUDENTS` CREATE_EDIT.
- Shared: `components/profile-fields/field-renderer.tsx`, `lib/api/student-profile-fields.ts`.

## 8. Security impact

- **AuthZ:** config endpoints gated like existing tenant config; staff endpoints on `STUDENTS` VIEW/CREATE_EDIT; `/me/profile` resolves the student from the authenticated user id only.
- **Sensitive personal data** (NIC, passport, driving licence, birthday): never logged in application logs; audit entries record *which* fields changed, not their values; masked (`•••• 4567`) in any list/search response (the full value only in the single-student profile endpoints). Excluded from existing student list/export DTOs.
- **Input validation:** server-side type/length/format per field; unknown/disabled keys rejected; free text rendered escaped by React (no `dangerouslySetInnerHTML`).
- **Public endpoint:** exposes only labels/types/required for registration fields — no staff-only config.
- No uploads, secrets, auth-architecture, payment or enrollment changes.

## 9. Tenant impact

- `student_profile_field_setting`: `tenant_id NOT NULL` FK, `UNIQUE (tenant_id, field_key)`; repository reads/writes use `TenantContext.getTenantId()` only — no method accepts a caller-supplied tenant id.
- `student_profile` new columns inherit the existing tenant-filtered repository methods; staff access by id goes through the existing tenant-scoped lookup (cross-tenant id → 404).
- Public policy/registration resolve the tenant from the host exactly as today.
- Config is per tenant: tenant A's settings never affect tenant B's registration or validation.
- No change to the multi-tenancy strategy.

## 10. Tests

**Backend (JUnit + Spring Boot integration + Testcontainers Postgres):**
- Unit: catalogue validation per type (phone, date bounds, AL year range, country list, lengths, blank → null); effective-config resolution (rows → legacy flags → defaults); invalid combinations rejected.
- Migration: V59 applies on a V58 database; CHECK constraints reject `required` without `enabled`; unique `(tenant_id, field_key)`.
- Config API: GET defaults for a fresh tenant match today's behaviour; PUT round-trip; 400 unknown/locked/duplicate key; 403 without permission; audit entry written in the same transaction.
- Registration: required registration field missing → 400; non-registration values ignored; legacy top-level properties still work; legacy `require_*` flags still honoured when no rows exist.
- Self profile: GET/PUT round-trip; required enforced; disabled key → 400; `missingRequiredFields` correct after an admin makes a field required; billing-address endpoint and profile form write the same columns.
- Staff: required not enforced; audit entry contains field keys but not sensitive values.
- **Cross-tenant (mandatory):** tenant A admin cannot read/modify tenant B's config (their own tenant is always used); tenant A staff `GET/PUT /students/{B-student-id}/profile-fields` → 404; tenant B's config does not change tenant A's registration validation or public policy.
- **Cross-student:** student cannot address another student's profile (no id-bearing self endpoint; staff endpoints → 403 for student role).

**Frontend (Playwright):**
- Tenant Admin toggles fields, saves, reloads and sees persisted state; Required disabled until Enabled.
- Register page shows exactly the "ask at registration" fields and blocks submit when a required one is empty.
- Student fills Additional details; banner disappears once required fields are filled.
- Staff views/edits a student's fields; 403/hidden settings tab for non-admin staff.
- Existing registration, profile/billing-address and PayHere specs stay green.

## 11. Documentation updates

- `docs/api/user-management.md` — `/me/profile`, `/{id}/profile-fields`, `profileFields` on register.
- `docs/api/tenant-configuration-management.md` — student-profile-fields endpoints; policy `registrationFields`; `require_*` deprecation.
- `docs/architecture/database-architecture.md` — `student_profile_field_setting`, new `student_profile` columns.
- `docs/plans/MVP-006 Student Management.md` — mark §21 item 3 resolved by STU-PROF-01.
- This plan — status updated as steps complete.

## 12. Decisions (confirmed by product owner, 2026-09-28)

1. **Name:** keep the single locked *Full name*; no First/Last split.
2. **"Course" at registration:** out of scope for this module.
3. **Missing required fields block nothing** (no enrollment/payment gating) — banner only.
4. **Disabled-field data** is kept, hidden; never deleted by a config change.
5. **No tenant-defined custom fields or option lists** in v1 — fixed catalogue, free-text values.
6. **Settings page is Tenant Admin only**, gated by the existing `BRANDING_SETTINGS` VIEW/CREATE_EDIT permission; no new `DomainArea`.

## Change-control check

- Multi-tenancy strategy, authentication, payment ledger, enrollment activation, deployment: **unchanged**.
- Database migration history: **additive only** (new V59).
- Approved API contracts: **additive changes** to `POST /students/register` and the public
  registration-policy response (new optional properties only; nothing removed or re-typed).
  Flagged for explicit approval.

## Implementation order (after approval)

1. `database-migration` → V59.
2. `implement-backend` → §5 + backend tests (§10) → commit `backend: add configurable student profile fields`.
3. `implement-frontend` → §7 + Playwright → separate commit.
4. `tenant-isolation-review`, `security-review`, `update-documentation`, `definition-of-done`.
