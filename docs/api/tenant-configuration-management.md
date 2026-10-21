# tenant-configuration-management — API Contract

Covers Wave 1's typed tenant configuration framework (`com.lms.tenantmanagement`
config/domain/service/web classes). Not a separate top-level domain — configuration
lives inside `tenant-management`, extending the `Tenant` aggregate it already owns
(`.claude/rules/architecture.md` has no `tenant-configuration-management` in the
confirmed domain list; see `docs/parity/klass-parity-matrix.md` PAR-XC-02 for the
architectural rationale).

## Response envelope

`com.lms.common.api.ApiResponse<T>` — see `docs/api/identity-access-service.md`.

## Data shape

One narrow table, `tenant_config_entry` (V36), one row per `(tenant_id, config_domain,
config_key)`, storing a JSONB `value`. Each key's type/default/validator/permission/
`sensitive` flag is defined in code (`ConfigPropertyRegistry`/`ConfigPropertyDefinition`),
not inferred from the client — so this is *not* the "one unvalidated arbitrary JSON
settings blob" master instruction §7 forbids; it is N individually-typed,
individually-validated entries sharing one physical table for extensibility (a new
property in an existing domain is a new registry entry, no migration required).

`ConfigDomain` has all 17 values from master instruction §7: `GENERAL, BRANDING,
ACADEMIC, STUDENT, TEACHER, COURSE, PAYMENT, FINANCE, ATTENDANCE, EXAM, CONTENT, VIDEO,
NOTIFICATION, SECURITY, DEVICE, DOMAIN, INTEGRATION`. As of Wave 1, only `GENERAL` and
`BRANDING` had registered properties. **Wave 3 populates `STUDENT`** (below) — every
other domain still resolves to an empty property list (`200` with `[]`, never `404`)
until a later wave populates it.

### GENERAL properties

| Key | Type | Default | Validation |
|---|---|---|---|
| `institute_name` | STRING | none (required on write) | non-blank, ≤255 chars |
| `support_email` | STRING | none | basic email format, ≤255 chars |
| `support_phone` | STRING | none | any string, ≤255 chars |
| `default_timezone` | STRING | `"UTC"` | must be a valid IANA zone id |
| `default_currency` | STRING | `"USD"` | `^[A-Z]{3}$` (shape only, no hardcoded allowlist) |

### BRANDING properties

| Key | Type | Default | Validation |
|---|---|---|---|
| `primary_color` | STRING | none | `^#[0-9A-Fa-f]{6}$` |
| `secondary_color` | STRING | none | same hex pattern, **plus** a cross-field WCAG AA contrast check (≥4.5:1) against `primary_color` when both are present in the same update |
| `logo_url` | STRING | none | `http(s)://` URL, ≤2048 chars |
| `favicon_url` | STRING | none | `http(s)://` URL, ≤2048 chars |

No file-upload pipeline exists yet — `logo_url`/`favicon_url` are URL references only.
No `sensitive: true` property exists yet, but the masking path (a sensitive value is
never returned in a `GET`/`PUT` response body, and never written into audit metadata)
is implemented now so a later wave's `INTEGRATION` credentials can use it, per master
instruction §7's "sensitive integration credentials must never be returned to the
frontend after persistence."

### STUDENT properties (Wave 3, master instruction §10 — registration configuration)

All eight are plain `BOOLEAN`s, validated only for type (`instanceof Boolean`) — the
first simple boolean-typed property registered in this framework (every `GENERAL`/
`BRANDING` property above is `STRING`-typed).

| Key | Type | Default | Meaning |
|---|---|---|---|
| `public_registration_enabled` | BOOLEAN | `true` | Whether `POST /api/v1/students/register` is reachable at all for this tenant — `false` makes it `404 NOT_FOUND` ("Student self-registration is not available"), the cross-tenant-404-style convention. |
| `approval_required` | BOOLEAN | `false` | A self-registered account starts `tenant_user.status = SUSPENDED` (cannot log in) until staff activates it, when `true`. |
| `otp_required` | BOOLEAN | `false` | Email-only OTP verification (`POST .../register/otp/send`/`.../otp/verify`) required before registering, when `true`. SMS/WhatsApp OTP remains **BLOCKED** — provider selection pending, per `implementation-roadmap.md` §5 — a tenant enabling this with a phone-based expectation in mind needs product-owner communication that only email delivers today. |
| `require_guardian_info` | BOOLEAN | `false` | `guardianName` AND `guardianPhone` both required on registration when `true`. |
| `require_school` | BOOLEAN | `false` | `school` required on registration when `true`. |
| `require_grade` | BOOLEAN | `false` | `grade` required on registration when `true`. |
| `require_stream` | BOOLEAN | `false` | `stream` required on registration when `true`. |
| `require_mobile` | BOOLEAN | `false` | `mobile` required on registration when `true`. |

`public_registration_enabled` is the only property defaulting `true` — the public
self-registration endpoint is reachable out of the box; every other property defaults
`false`, so a tenant must explicitly opt into approval/OTP/per-field requirements,
matching this registry's existing "safe, unsurprising default" convention. See
`docs/api/user-management.md`'s "Public student self-registration" section for the full
registration-endpoint contract these properties gate.

**Deprecated (STU-PROF-01):** the five `require_*` properties are superseded by the
per-field student profile field settings below. They are read only as the default
required-ness for a tenant that has never saved its field settings; once a tenant saves,
they are ignored for that tenant. They stay registered so existing values remain valid.

## Student profile field settings (STU-PROF-01)

A platform-defined catalogue of student profile fields (`StudentProfileField`): Email and
Full name (locked — always collected), Birthday, Profession, Special talent, Mobile number,
Fixed telephone, WhatsApp number, Telegram number, Tute delivery phone 1/2, Address line
1/2, City, Postal code, Country, NIC number, Passport number, Driving licence number
(the three identity numbers are `sensitive`), School, Grade, A/L stream, A/L year,
University, Learning medium, Guardian (name + phone), Has siblings, Referral code. For
each non-locked field a tenant sets:

- `enabled` — students have this field (shown on their profile page, and to staff);
- `required` — students must fill it in (implies `enabled`);
- `collectAtRegistration` — also asked on the public registration form (implies `enabled`).

Stored in `student_profile_field_setting` (V59, one row per tenant per field). A tenant with
no rows gets defaults that reproduce pre-STU-PROF-01 behaviour: Guardian/School/Grade/
A/L stream/Mobile enabled and asked at registration (required per their legacy
`require_*` flag), Address line 1 and City enabled on the profile only, everything else off.
Disabling a field never deletes students' stored values.

### `GET /api/v1/tenant-config/student-profile-fields`

`BRANDING_SETTINGS`/`VIEW` (Tenant Admin, Read-only Auditor). **`200`**:

```jsonc
{ "fields": [
    { "key": "NIC_NUMBER", "label": "NIC number", "group": "IDENTITY", "type": "TEXT",
      "maxLength": 20, "sensitive": true, "locked": false,
      "enabled": true, "required": false, "collectAtRegistration": false }
] }
```

Always the full catalogue, in display order, locked rows included (display only).

### `PUT /api/v1/tenant-config/student-profile-fields`

`BRANDING_SETTINGS`/`CREATE_EDIT` (Tenant Admin only). Body:
`{ "fields": [ { "key", "enabled", "required", "collectAtRegistration" } ] }` — rows not
listed keep their current settings; the save writes a row for every non-locked field,
all-or-nothing. **`200`** → same shape as `GET`. `400 VALIDATION_ERROR`
(`field = "fields[i].key"`/`"fields[i].enabled"`) for an unknown, duplicate or locked key,
or `required`/`collectAtRegistration` without `enabled`. `403` for any other role. Writes one
`student_profile_fields.updated` audit entry (target `tenant`) listing each changed field's
before/after flags. Last write wins (no optimistic lock in v1).

## Auth requirements and authorization model

Every endpoint except the public branding read requires a valid
`Authorization: Bearer <accessToken>` and the `DomainArea.BRANDING_SETTINGS` grant —
reused deliberately as the gate for **all** Institute Configuration domains (Tenant
Admin: `VIEW`+`CREATE_EDIT`; Read-only Auditor: `VIEW`; every other role: no access),
since it is the only row in `docs/requirements/user-roles-and-permissions.md` §2
covering configuration at all. `tenant_id` is never an accepted parameter in any
form — resolved exclusively from `TenantContext` via `TenantAwareRepository`.

## Endpoints

### `GET /api/v1/tenant-config/domains`

Auth: `BRANDING_SETTINGS`/`VIEW`. Lists all 17 domains and whether each has registered
properties.

```jsonc
{ "success": true, "data": [ { "domain": "GENERAL", "hasProperties": true }, { "domain": "ACADEMIC", "hasProperties": false } ] }
```

### `GET /api/v1/tenant-config/{domain}`

Auth: `BRANDING_SETTINGS`/`VIEW`. `{domain}` binds to `ConfigDomain` (`400`, not `500`,
on an unrecognized name). Returns the resolved value for every registered property of
that domain — the tenant's persisted override if one exists, else the registry
default.

```jsonc
{ "success": true, "data": [ { "key": "institute_name", "value": "Example Institute", "type": "STRING", "defaultValue": null, "sensitive": false } ] }
```

### `PUT /api/v1/tenant-config/{domain}`

Auth: `BRANDING_SETTINGS`/`CREATE_EDIT` (Tenant Admin only — Read-only Auditor gets
`403` here). Body: a plain object of only the changed keys. All-or-nothing: an unknown
key or a value failing its validator rejects the whole batch with `400` and one
`FieldError` per invalid key, persisting nothing. On success, every changed key's
upsert and its `AuditLogApi.record` call happen inside one transaction, and the
response is the domain's full resolved state (same shape as the `GET`).

### `GET /api/v1/public/tenant-config/branding`

**Unauthenticated** — the one deliberate exception. Tenant is resolved from the
existing `TenantResolutionFilter` host/subdomain resolution (never a client-supplied
id), reusing `tenant-management`'s sole tenant-resolution mechanism rather than
building a second one. Returns only five public-safe fields, with platform-level
fallback defaults for a tenant with nothing set — branding must render for anonymous
visitors and for authenticated students/teachers who hold no `BRANDING_SETTINGS` grant.

```jsonc
{ "success": true, "data": { "primaryColor": "#1D4ED8", "secondaryColor": "#0F172A", "logoUrl": null, "faviconUrl": null, "instituteName": "" } }
```

No public/portal screen consumes this endpoint yet (no shared theming pipeline exists
in this codebase to apply it) — see `docs/parity/klass-parity-matrix.md` PAR-14-04.

### `GET /api/v1/public/tenant-config/student-registration-policy` (Wave 3)

**Unauthenticated**, second deliberate exception, modeled directly on `GET
/api/v1/public/tenant-config/branding` above — same tenant-resolution mechanism (request
subdomain via `TenantResolutionFilter`, never a client-supplied tenant id/query param),
same rationale (the public, unauthenticated student self-registration page must know,
before a prospective student fills out anything, whether registration is open and which
fields/steps to render — display/form-shape information, not a privileged settings read,
so it deliberately bypasses `TenantConfigController`'s domain-level permission gate).

Returns only the three registration-flow `STUDENT` properties, the five legacy per-field
`require*` booleans and the fields asked at registration — never the full generic
`tenant-config` property list or the tenant's full field configuration:

```jsonc
{
  "success": true,
  "data": {
    "publicRegistrationEnabled": true,
    "approvalRequired": false,
    "otpRequired": false,
    "requireGuardianInfo": false,
    "requireSchool": false,
    "requireGrade": false,
    "requireStream": false,
    "requireMobile": false,
    "registrationFields": [   // STU-PROF-01 (additive)
      { "key": "MOBILE", "label": "Mobile number", "group": "CONTACT", "type": "PHONE",
        "maxLength": 50, "required": false }
    ]
  }
}
```

`registrationFields` lists the non-locked fields with `collectAtRegistration = true`, in
display order. Since STU-PROF-01 the five `require*` booleans are derived from the student
profile field settings (`required` AND `collectAtRegistration` of Guardian, School, Grade,
A/L stream, Mobile) and kept only for older clients.

Consumed by `(auth)/register`'s frontend form before rendering any field — see
`docs/api/user-management.md`'s "Public student self-registration" section for how the
registration endpoints themselves use these same eight properties server-side.

## Clearing optional keys

Sending JSON `null` for a key with no default that is not required (e.g. PAYMENT bank keys, VIDEO player links, GENERAL support_email/phone, BRANDING colours/urls) deletes the tenant's value (audited `tenant_config.cleared`); null for required or defaulted keys returns 400. See `docs/specs/klass-enrollment-payment-workflows.md` for the PAYMENT keys `facility_fee_mode`, `facility_fee_amount`, `bank_name`, `bank_branch`, `bank_account_holder`, `bank_account_number` and VIDEO keys `player_android_url`, `player_ios_url` (https only) added in 2026-10.
