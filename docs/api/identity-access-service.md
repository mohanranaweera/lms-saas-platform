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

**Failure:**

| Status | `error.code` | Condition |
|---|---|---|
| `400` | `VALIDATION_ERROR` | `email` blank/malformed, or `password` blank |
| `401` | `INVALID_CREDENTIALS` | Unknown email, or wrong password (including a wrong password against a suspended account — anti-enumeration by design, never distinguished) |
| `403` | `TENANT_UNAVAILABLE` | Tenant subdomain unresolvable, or tenant is `suspended`/`cancelled` — rejected before credentials are evaluated, by `TenantResolutionFilter`, not this handler |
| `403` | `USER_SUSPENDED` | Password verified successfully, but the `tenant_user` row's `status` is `suspended` |

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
| `401` | `INVALID_REFRESH_TOKEN` | Cookie missing/blank, or token not found / revoked / expired / already rotated — never distinguished |

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
| `SESSION_REVOKED` | 401 | Valid JWT, invalid session (revoked/expired/tenant-mismatch/account-inactive) |

## Tenant resolution caveat (environment-level, not an endpoint detail)

`TenantResolutionFilter` extracts the tenant subdomain from the **Host header** of the
incoming request itself (`hostWithoutPort.substring(0, firstDot)`; if there's no `.` in
the host, resolution fails with `TENANT_UNAVAILABLE`). This means:

- Every tenant-scoped request (`/api/v1/auth/**`) must be made to a host that actually
  has a tenant subdomain label, e.g. `tenant1.lms.local`, not a bare `localhost` or a
  generic `api.` host — a bare `localhost:8080` has no dot before the port and will
  always fail tenant resolution.
- The frontend's `NEXT_PUBLIC_API_BASE_URL` (currently `http://localhost:8080/api` per
  `.env.example`) does not satisfy this for the tenant-scoped endpoints as configured.
  Local dev needs either a `/etc/hosts` (or Windows `hosts` file) entry mapping a
  subdomain like `demo.lms.local` to `127.0.0.1`, with the frontend calling that host
  instead, or an Nginx layer that forwards a subdomain-carrying request through unchanged.
  This is an infrastructure/devops decision outside this contract's scope — flagging it
  here because it blocks a real (non-mocked) end-to-end login test locally until resolved.
- Platform Admin endpoints have no such requirement — they never resolve a tenant.

## CORS caveat (environment-level)

No CORS configuration exists anywhere in the backend (`SecurityFilterChainConfig` calls
`.csrf(...).disable()` but never configures `.cors(...)`, and no `WebMvcConfigurer`
bean adds CORS mappings). A browser running the Next.js frontend on a different
origin/port than the backend cannot make a credentialed (cookie-carrying) request to
these endpoints — the preflight will fail. This must be resolved on the backend/infra
side (an explicit CORS configuration scoped to the frontend's known origin(s), with
`allowCredentials(true)`) before real browser-based login can work end-to-end outside
same-origin deployment (e.g. behind the same Nginx host). Not something the frontend can
work around from its side.

## Process gap

Per `docs/api/README.md`, a contract file is supposed to be written by the
`review-api-contract` skill *before* `implement-backend`/`implement-frontend` work
begins on an endpoint. That did not happen for this module — the backend was
implemented and merged first. This file was produced after the fact, by reading the
shipped backend source, to unblock frontend implementation. Flagging so future modules
don't repeat the skip.
