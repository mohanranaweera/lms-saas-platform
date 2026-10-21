# identity-access-service — Authentication API Contract

Covers the Authentication Foundation module (MVP-002 / `AUTH-1`, `AUTH-2`, `AUTH-3`).
Derived directly from the shipped backend implementation (commit `df6ddc6`), not from
the pre-implementation draft in `docs/plans/MVP-002 Authentication Foundation.md` §10 —
that draft is superseded by this file. Written retroactively because the backend
implementation shipped without a contract file; see the "Process gap" note at the
bottom.

## Response envelope

Every endpoint below returns `com.lms.common.api.ApiResponse<T>`:

```jsonc
{
  "success": true,
  "data": { /* T, or null on error */ },
  "error": null, // or ApiError, never both populated
  "timestamp": "2026-08-06T12:34:56.789Z", // ISO instant
  "traceId": "..." // correlation id, or "unknown"
}
```

`ApiError`:

```jsonc
{
  "code": "INVALID_CREDENTIALS", // see "Error codes" table
  "message": "Invalid email or password",
  "fieldErrors": [] // FieldError[]: {field, message} — always an array, never null
}
```

## Auth requirements

- `POST /api/v1/auth/login`, `POST /api/v1/auth/refresh`, `POST /api/v1/platform-admin/auth/login`,
  `POST /api/v1/platform-admin/auth/refresh` are `permitAll` (`SecurityFilterChainConfig`).
- Every other endpoint, including both `logout` endpoints, requires a valid
  `Authorization: Bearer <accessToken>` header. Missing/invalid/expired token →
  `401 UNAUTHENTICATED`. No further role/permission check exists yet in this module
  (RBAC is Module 3) — any authenticated principal of the correct kind (tenant user vs.
  platform admin) may call the corresponding logout endpoint for their own session.
- No endpoint in this contract accepts a client-supplied `tenant_id` field, in the body,
  a header, or a query/path parameter. Tenant identity is resolved exactly once, by
  `TenantResolutionFilter`, from the **Host header's subdomain** (the label before the
  first `.`) — see "Tenant resolution" caveat below. `platform-admin/**` paths are
  explicitly excluded from this filter and never resolve/require a tenant.

## Endpoints

### `POST /api/v1/auth/login`

Tenant-scoped login. Tenant is resolved from the request's Host header before this
handler runs; rejected upstream (see Tenant resolution) if unresolvable/suspended.

**Request body** (`LoginRequest`):

```jsonc
{ "email": "user@example.com", "password": "..." } // both required; email must be valid format
```

No `tenant_id` field — never accepted.

**Success — `200`** (`ApiResponse<LoginResponse>`):

```jsonc
{
  "accessToken": "<JWT>",
  "expiresInSeconds": 900, // 15 minutes
  "sessionId": "<uuid>", // device_session.id — also the JWT's session_id claim
  "mustChangePassword": false
}
```

Side effect: sets a `Set-Cookie` header —
`lms_refresh_token`; `HttpOnly`; `Secure`; `SameSite=Strict`; `Path=/api/v1/auth`;
`Max-Age` = the configured refresh-token TTL (`app.security.session.refresh-token-ttl`,
30 days by default). **The raw refresh token is never present in the JSON body.**
Because the cookie is `Secure`, it will not be set/sent at all over plain HTTP — local
dev must run the frontend+backend over HTTPS or `localhost` (browsers special-case
`localhost` as a secure context) for this cookie to work.

Wave 10 (ADR-022): the request may carry the `lms_device_id` cookie from an earlier sign-in;
the response always sets it (`HttpOnly`; `SameSite=Strict`; `Path=/api/v1/auth`; `Secure`
except under the `local` profile; `Max-Age` = `app.security.device.cookie-max-age`, 400 days).
It is a server-issued random token naming this browser as a registered device; only its hash is
stored. A known, active device is reused; otherwise a new device is registered - for a STUDENT
only while a device slot is free (see "Devices" below).

**Failure:**

| Status | `error.code` | Condition |
|---|---|---|
| `400` | `VALIDATION_ERROR` | `email` blank/malformed, or `password` blank |
| `401` | `INVALID_CREDENTIALS` | Unknown email, or wrong password (including a wrong password against a suspended account — anti-enumeration by design, never distinguished) |
| `403` | `TENANT_UNAVAILABLE` | Tenant subdomain unresolvable, or tenant is `suspended`/`cancelled` — rejected before credentials are evaluated, by `TenantResolutionFilter`, not this handler |
| `403` | `USER_SUSPENDED` | Password verified successfully, but the `tenant_user` row's `status` is `suspended` |
| `403` | `DEVICE_LIMIT_REACHED` | Wave 10: password verified, the account is a STUDENT signing in on a NEW device, and every device slot is taken (active devices plus removed ones still in their reset cooldown). Nothing is persisted. |

`error.message` is always the same generic copy for `INVALID_CREDENTIALS` regardless of
which underlying check failed — do not attempt to further distinguish in the UI.

### `POST /api/v1/auth/refresh`

Rotates the access/refresh token pair. Reads the `lms_refresh_token` cookie — **not** a
request body. The client must call this with credentials included (`fetch(..., {
credentials: "include" })`) so the cookie is sent; there is nothing to pass in the body.

**Request:** no body.

**Success — `200`** (`ApiResponse<RefreshResponse>`):

```jsonc
{ "accessToken": "<JWT>", "expiresInSeconds": 900 }
```

Side effect: sets a new rotated `lms_refresh_token` cookie (same attributes as login).
The prior refresh token is invalidated in the same operation — a second call with the
old cookie value will fail.

**Failure:**

| Status | `error.code` | Condition |
|---|---|---|
| `401` | `INVALID_REFRESH_TOKEN` | Cookie missing/blank, or token not found / revoked / expired / already rotated, or (Wave 10) the `lms_device_id` cookie is missing / belongs to another device / that device was removed, or the session predates device registration — never distinguished |

### `POST /api/v1/auth/logout`

Revokes the caller's own session (from the authenticated principal, not a body param).

**Request:** no body. Requires `Authorization: Bearer <accessToken>`.

**Success — `200`** (`ApiResponse<null>`): `data` is `null`. Side effect: revokes the
`device_session` row and its Redis cache entry, and clears the `lms_refresh_token`
cookie (`Max-Age=0`).

**Failure:**

| Status | `error.code` | Condition |
|---|---|---|
| `401` | `UNAUTHENTICATED` | No/invalid/expired access token |

### `POST /api/v1/platform-admin/auth/login`

Structurally separate path — never resolves or requires a tenant (`TenantResolutionFilter`
explicitly excludes `/api/v1/platform-admin/**`). Same request/response/error shape as
tenant login, with these differences:

- No `TENANT_UNAVAILABLE` case (nothing to resolve).
- Refresh cookie name is **`lms_platform_admin_refresh_token`**, `Path=/api/v1/platform-admin/auth`.
- `USER_SUSPENDED` applies to the `platform_admin_user` row instead of `tenant_user`.

### `POST /api/v1/platform-admin/auth/refresh`

Mirrors `/api/v1/auth/refresh`, reading the `lms_platform_admin_refresh_token` cookie.
Same success/error shape.

### `POST /api/v1/platform-admin/auth/logout`

Mirrors `/api/v1/auth/logout` for the platform-admin session. Same success/error shape.

### Session invalidity on any other authenticated endpoint

Not an auth endpoint itself, but applies to every protected request once this module
ships: if `JwtAuthenticationFilter` finds a structurally valid, non-expired JWT whose
session has since been revoked/expired, or whose `tenant_id` claim doesn't match the
tenant resolved for the current request, or whose live `tenant_user`/`platform_admin_user`
row is no longer `active`, the request is rejected:

| Status | `error.code` | Condition |
|---|---|---|
| `401` | `SESSION_REVOKED` | Signature/expiry-valid JWT, but session revoked/expired/not-found, tenant-claim mismatch, live account no longer active, or a platform-admin token used outside `/api/v1/platform-admin/**` |
| `401` | `UNAUTHENTICATED` | Missing/malformed/signature-invalid/expired JWT |

The frontend's silent-refresh path must treat both codes the same way: attempt one
refresh, and on that also failing, redirect to login (`?reason=session_expired`) — do
not try to parse a distinction between these two codes into different UX.

## Error codes reference

| Code | HTTP status(es) seen in this module | Meaning |
|---|---|---|
| `VALIDATION_ERROR` | 400 | Request body failed `@Valid` (Bean Validation); `fieldErrors` populated |
| `UNAUTHENTICATED` | 401 | No/invalid/expired access token on a protected endpoint |
| `FORBIDDEN` | 403 | Reserved for authorization failures beyond this module's scope (not raised by any endpoint above) |
| `INVALID_CREDENTIALS` | 401 | Login: wrong email/password, generic |
| `TENANT_UNAVAILABLE` | 403 | Tenant unresolvable or suspended/cancelled |
| `USER_SUSPENDED` | 403 | Specific user row suspended, post-password-check |
| `INVALID_REFRESH_TOKEN` | 401 | Refresh cookie missing/invalid/expired/already-rotated |
| `SESSION_REVOKED` | 401 | Valid JWT, invalid session (revoked/expired/tenant-mismatch/account-inactive, or its device was removed) |
| `DEVICE_LIMIT_REACHED` | 403 | Wave 10: student sign-in from a new device with no free slot |
| `DEVICE_NOT_AUTHORIZED` | 403 | Wave 10: course content opened on a device outside the course device limit |
| `DEVICE_SELF_SERVICE_DISABLED` | 403 | Wave 10: a student tried to remove a device but the tenant manages devices |

## Tenant resolution (TEN-DOM-01 / ADR-021)

`TenantResolutionFilter` resolves the tenant from the host the browser addressed
(`request.getServerName()`), through `TenantLookupApi.resolveByHost`:

- `<subdomain>.<PLATFORM_DOMAIN>` (exactly one label) resolves by subdomain;
- any other host resolves only as an `ACTIVE` custom domain of a tenant with custom domains
  switched on;
- anything else (unknown host, a bare `localhost`, a multi-label platform subdomain) is
  `403 TENANT_UNAVAILABLE`, never a fallback tenant.

With `PLATFORM_DOMAIN` empty, the old behaviour applies (the first label of any host is the
subdomain) and custom domains never resolve.

**Same-origin API.** The frontend calls `/api` on the site's own address
(`NEXT_PUBLIC_API_BASE_URL`, default `/api`). A proxy forwards it here: the Next dev server's
rewrite (`BACKEND_ORIGIN`, default `http://localhost:8080`) locally, Nginx in production. The
proxy passes the site's address in `X-Forwarded-Host`. With
`server.forward-headers-strategy: native` (local and prod profiles), Tomcat trusts that header
only from its internal-proxy ranges (loopback and private networks) and reports it as the server
name; from anyone else the Host header is used as is.

Locally, map each tenant host to `127.0.0.1` in the hosts file (`demo.lms.test`,
`abc.lms.test`, or a custom domain such as `learn.abc.test`) and browse to `http://<host>:3000`.

## CORS

Browser calls are same-origin, so production configures no CORS. The local profile still allows
`http://localhost:3000` and `http://*.lms.test:3000`, for a frontend that sets an absolute
`NEXT_PUBLIC_API_BASE_URL`.

## Process gap

Per `docs/api/README.md`, a contract file is supposed to be written by the
`review-api-contract` skill *before* `implement-backend`/`implement-frontend` work
begins on an endpoint. That did not happen for this module — the backend was
implemented and merged first. This file was produced after the fact, by reading the
shipped backend source, to unblock frontend implementation. Flagging so future modules
don't repeat the skip.

## AUTH-PWD-01 — Account setup, forgot password, change password (ADR-020)

Added 2026-09-30. Tenant host only; the tenant comes from the subdomain. No body carries a tenant or user id.

| Endpoint | Auth | Body | Responses |
|---|---|---|---|
| `POST /api/v1/auth/password-tokens/verify` | public, 20 per 10 min per IP | `{ token }` | `200 { purpose: ACCOUNT_SETUP\|PASSWORD_RESET, emailMasked, expiresAt }`; `400 TOKEN_INVALID`; `429 TOO_MANY_REQUESTS` |
| `POST /api/v1/auth/password-tokens/complete` | public, same limit | `{ token, newPassword }` | `204`; `400 VALIDATION_ERROR` (`newPassword`: 8-128 characters, not the email); `400 TOKEN_INVALID`; `429` |
| `POST /api/v1/auth/password-reset/request` | public | `{ email }` | always `202 { message }` |
| `POST /api/v1/auth/password` | signed-in tenant user | `{ currentPassword, newPassword }` | `200 { accessToken, expiresInSeconds }`; `400 VALIDATION_ERROR` (`currentPassword` "is incorrect"; `newPassword` rules, also "must be different from your current password"); `429` after 5 wrong attempts in 15 min |

- **`TOKEN_INVALID`** is the single answer for an unknown, expired, used, superseded or other-tenant link, or for an account in the wrong state.
- **Links** carry the token in the URL fragment (`#token=`), never the query string.
- **Tokens:** 256 random bits; the database stores only their SHA-256. Single use. Account setup lasts 72 h, reset 60 min. Issuing a new token supersedes the unused ones.
- **Sessions:** completing a link revokes **all** the user's sessions; there's no automatic sign-in. Change password revokes all sessions **except** the current one.
- **Forgot password:** answers the same whether or not the email has an account, and is rate-limited to 5 per IP and 3 per email per hour. `active` accounts get a reset email; `invited` accounts get a new setup email; `suspended` and unknown accounts get nothing.
- **`403 PASSWORD_CHANGE_REQUIRED`:** while the account's `must_change_password` is true, every authenticated request except `POST /auth/password`, `/auth/logout`, `/auth/refresh` and `/api/v1/public/**` is refused. This is read from the account row on each request, not from a token claim, so ADR-007's token payload is unchanged.
- **Login:** an `invited` account's login fails exactly like a wrong password (`401 INVALID_CREDENTIALS`).

## Wave 10 — Devices (ADR-022)

Device identity is the server-issued `lms_device_id` cookie (see login above) — never
User-Agent or IP. A device belongs to one user in one tenant. Devices are never deleted:
removal is a status change that signs the device out (all its sessions are revoked in
Postgres and evicted from Redis in the same transaction) and keeps its slot taken until
`slotReusableAt` (now + the tenant reset cooldown).

Device limits apply to STUDENT accounts only, with precedence student exception > course
override > tenant policy > plan default (`app.security.device.plan-default-limit`, 2).
Sign-in uses student > tenant > plan; the course level is enforced at content time by
`AccessPolicyApi` (`docs/api/enrollment-management.md`).

### `DeviceSlotSummary` / `DeviceView` shapes

```jsonc
{
  "devices": [{
    "id": "<uuid>", "label": "Chrome on Windows", "status": "ACTIVE",   // or REVOKED
    "firstSeenAt": "...", "lastSeenAt": "...", "lastIp": "203.0.113.10",
    "revokedAt": null, "revocationSource": null,                        // ADMIN | STUDENT | SYSTEM
    "revokeReason": null, "slotReusableAt": null, "current": true       // true = the calling session's device
  }],
  "limit": 2,                    // null for staff (not limited)
  "limitSource": "TENANT",       // STUDENT | COURSE | TENANT | PLAN
  "occupiedSlots": 2, "nextSlotFreeAt": null,
  "studentOverride": null, "studentOverrideReason": null,
  "selfServiceEnabled": false, "resetCooldownHours": 24
}
```

The device token and its hash are never returned.

### `GET /api/v1/devices/my`

Any authenticated tenant user; always the caller's own devices. `200` `DeviceSlotSummary`.

### `POST /api/v1/devices/my/{deviceId}/revoke`

Remove one of the caller's OTHER devices. `200` `DeviceSlotSummary`.
`403 DEVICE_SELF_SERVICE_DISABLED` (student, tenant has not enabled self-service),
`409 CONFLICT` (the current device — sign out instead — or already removed),
`404` (not the caller's device, or another tenant's). Audit: `device.revoked_by_user`.

### `GET /api/v1/device-policy` · `PUT /api/v1/device-policy`

`DEVICES` VIEW / CREATE_EDIT (Tenant Admin edits; Student Support and Auditor view).

```jsonc
// PUT body (DevicePolicyRequest)
{ "maxDevices": 3,               // 1-20, null = plan default
  "resetCooldownHours": 24,      // 0-720, required
  "studentSelfServiceEnabled": false }
// response (DevicePolicyView)
{ "maxDevices": 3, "effectiveMaxDevices": 3, "planDefaultLimit": 2,
  "resetCooldownHours": 24, "studentSelfServiceEnabled": false, "updatedAt": "..." }
```

`400 VALIDATION_ERROR` outside the bounds. Audit: `device_policy.updated` (before/after).

Staff device management for one student lives under `/api/v1/students/{id}/devices`
(`docs/api/user-management.md`).
