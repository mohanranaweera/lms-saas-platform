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
`BRANDING` have registered properties — every other domain resolves to an empty
property list (`200` with `[]`, never `404`) until a later wave populates it.

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
