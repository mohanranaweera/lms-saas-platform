# integration-management — Media & Meeting Provider Connections API Contract

MEDIA-INT-01 / ADR-018. This is the source of truth for `implement-backend` and
`implement-frontend`. Reviewed 2026-09-28 (`review-api-contract`); implemented 2026-09-29.
Updated after the security review (2026-09-29): the OAuth callback no longer saves a
connection by itself - the signed-in teacher confirms it (S1, see "Confirm a finished
connection"), and disconnecting wipes stored credentials (S2).
Updated for **MEDIA-INT-01A** (2026-09-29, ADR-018 Amendment 1): Bunny.net is connected with an
account API key only (`PUT /bunny` no longer takes a library id / CDN hostname / token-auth key),
each teacher gets their own Bunny library (`/bunny/libraries`), and the teacher overview gains
`bunnyConnected` / `bunnyLibrary`.

## Conventions

- Every response uses the `ApiResponse<T>` envelope (see `identity-access-service.md`). Errors
  are `ApiError {code, message, fieldErrors[]}`, and `fieldErrors` is always present (possibly
  empty).
- Every endpoint except the OAuth callback and the Zoom webhook needs
  `Authorization: Bearer <accessToken>`. Missing or expired auth → `401 UNAUTHENTICATED`.
- **No endpoint accepts `tenantId`, `ownerUserId`, a role, or a return URL.** The tenant comes
  from `TenantContext`, the teacher from the authenticated principal, and the OAuth return host
  from the request's own resolved tenant host.
- Path `{provider}` values are lowercase: `zoom`, `youtube`, `vimeo`, `dyntube`, `bunny`. JSON
  enum values are uppercase (`ZOOM`, ...). An unknown `{provider}` → `404 NOT_FOUND`.
- A connection id belonging to another tenant, or (for a teacher) to another teacher → `404
  NOT_FOUND`, never 403. This is anti-enumeration.
- Collections are **unpaginated**. They are bounded by provider count (≤5) or by the tenant's
  teachers × 3 providers. `teacherConnections` is sorted by `teacherName` ASC, then `provider`.
- Timestamps are ISO-8601 UTC strings. Nullable fields are always present with `null`, never
  omitted.

### New error codes (`ApiErrorCodes`)

| Code | HTTP | Meaning |
|---|---|---|
| `PROVIDER_DISABLED_BY_TENANT` | 409 | Tenant Admin has switched this teacher provider off |
| `PROVIDER_NOT_CONNECTED` | 409 | Required connection missing / not ACTIVE (e.g. session's teacher has no Zoom) |
| `PROVIDER_LICENSE_REQUIRED` | 409 | Zoom account is Basic (free); licensed account needed for live classes |
| `PROVIDER_RECONNECT_REQUIRED` | 409 | Connection is NEEDS_RECONNECT (token revoked/expired, or missing scope) |
| `PROVIDER_CREDENTIALS_INVALID` | 400 | DynTube/Bunny test with supplied keys was rejected by the provider (also carries `fieldErrors`) |
| `SERVICE_UNAVAILABLE` (existing) | 503 | OAuth app / encryption key not configured, or provider unreachable. Nothing is stored |

Other failures use the existing codes: `VALIDATION_ERROR` 400, `FORBIDDEN` 403, `NOT_FOUND` 404,
`CONFLICT` 409.

## Shared shapes

### `ConnectionView`

```jsonc
{
  "id": "0192…",
  "provider": "ZOOM",                    // ZOOM | YOUTUBE | VIMEO | DYNTUBE | BUNNY
  "scope": "TEACHER",                    // TEACHER | TENANT
  "status": "ACTIVE",                    // ACTIVE | NEEDS_RECONNECT | DISCONNECTED
  "accountLabel": "jane@example.test",   // Zoom email / YouTube channel title / Vimeo name / "DynTube" / "Bunny.net account"; nullable
  "externalAccountIdMasked": "****a1b2", // last 4 only; nullable
  "accountType": "LICENSED",             // Zoom: BASIC | LICENSED; others: null
  "grantedScopes": ["meeting:write"],    // [] for API-key providers
  "settings": { "keyType": "ACCOUNT" },  // BUNNY only; null otherwise. A legacy (MEDIA-INT-01) row still shows { libraryId, cdnHostname, tokenAuthConfigured } and must be reconnected
  "secretConfigured": true,              // always true for a stored row; never the secret itself
  "connectedAt": "2026-09-28T10:00:00Z",
  "lastVerifiedAt": "2026-09-28T10:00:00Z", // nullable
  "lastError": null,                     // sanitised, ≤500 chars, never provider body/token; nullable
  "disconnectedBy": null,                // OWNER | TENANT_ADMIN | PROVIDER | SYSTEM; nullable
  "disconnectedAt": null,
  "disconnectedReason": null
}
```

It **never** contains an access/refresh token, API key, token-auth key, or unmasked external id.
`secretConfigured` is `false` for a `DISCONNECTED` connection: disconnecting (or replacing)
wipes the stored credentials, while the row itself is kept as history.

### `ConnectionTestResult`

```jsonc
{ "ok": true, "status": "ACTIVE", "lastVerifiedAt": "2026-09-28T10:05:00Z", "message": "Connected as jane@example.test" }
```

A failed test is still `200`, with `ok: false`, the new `status` (e.g. `NEEDS_RECONNECT`), and a
sanitised `message`. A provider that is unreachable → `ok:false`, with the status unchanged.

## Teacher — own connections: `/api/v1/teacher/integrations`

Callers need role `TEACHER` or `TEACHER_ASSISTANT`, otherwise → `403 FORBIDDEN`. The caller acts
only on connections where `owner_user_id = principal.userId`.

### `GET /api/v1/teacher/integrations`

`200` → `TeacherIntegrationsResponse`:

```jsonc
{
  "providers": [
    {
      "provider": "ZOOM",
      "enabledByTenant": true,   // tenant toggle
      "available": true,         // platform OAuth app + encryption key configured
      "connection": null         // current non-DISCONNECTED ConnectionView, else the latest DISCONNECTED one (so "disconnected by admin: reason" can be shown), else null
    }
    // always exactly ZOOM, YOUTUBE, VIMEO in that order
  ],
  "bunnyConnected": true,        // the institute has an ACTIVE Bunny.net account (MEDIA-INT-01A)
  "bunnyLibrary": {              // the teacher's own latest Bunny library, else null. No ids or keys
    "status": "ACTIVE",          // PROVISIONING | ACTIVE | FAILED | UNAVAILABLE
    "libraryName": "Demo Institute - Jane Perera [a1b2c3d4e5f6]"
  }
}
```

`UNAVAILABLE` means Bunny.net is not connected, or the current Bunny account key cannot see the
library (e.g. the institute switched to a different Bunny account). The stored row is not
changed. This read is not transactional: working out `UNAVAILABLE` may make one Bunny listing
call.

### `POST /api/v1/teacher/integrations/{provider}/connect`

Only `zoom|youtube|vimeo` are accepted; `dyntube|bunny` → `404`. There is no request body.

- `200` → `{ "authorizationUrl": "https://zoom.us/oauth/authorize?…" }`. The frontend navigates
  to this URL with a full-page redirect.
- `409 PROVIDER_DISABLED_BY_TENANT`
- `503 SERVICE_UNAVAILABLE`: the OAuth app is not configured, the encryption key is missing, or
  Redis is unavailable.

The same endpoint is used for reconnect. The old row is only replaced once the callback
succeeds, so an abandoned OAuth attempt leaves the current connection untouched.

### `POST /api/v1/teacher/integrations/pending/{pendingId}/confirm`

Saves a finished provider authorization (the OAuth callback's `?pending=` result). No body.
The teacher's settings page calls this automatically, once, when it loads with `?pending=`.

- `200` → `ConnectionView` (the saved ACTIVE connection). Any current connection for the same
  provider is replaced (old row DISCONNECTED/SYSTEM, credentials wiped).
- `404 NOT_FOUND` ("This connection request has expired or was already used. Connect again.")
  when the pending id is unknown, expired (10 minutes), already used, **or belongs to another
  teacher or tenant**. The pending entry is consumed either way, and in the "someone else's"
  case the provider grant is revoked and a security-log entry is written - so a consent link
  forwarded to another person can never attach their account to the teacher's profile.
- `409 PROVIDER_DISABLED_BY_TENANT` when the institute switched the provider off meanwhile (the
  grant is revoked).
- `401`/`403` for a signed-out, suspended or non-teacher caller.

### `POST /api/v1/teacher/integrations/connections/{id}/test`

`200` → `ConnectionTestResult`. `404` if not own. `409 CONFLICT` if DISCONNECTED.

### `GET /api/v1/teacher/integrations/connections/{id}/impact`

`200` → `{ "upcomingSessionCount": 3 }`. This counts SCHEDULED/LIVE class sessions hosted on this
connection with `scheduledEnd > now`. It is `0` for non-Zoom providers. `404` if not own.

### `POST /api/v1/teacher/integrations/connections/{id}/disconnect`

Body: `{ "reason": null }` (optional, ≤500 chars).

- `200` → `ConnectionView` (status DISCONNECTED, disconnectedBy OWNER).
- `404` if not own.
- `409 CONFLICT` if already DISCONNECTED.

Provider-side token revoke is best-effort after commit and does not affect the response.

## OAuth callback (public): `GET /api/v1/integrations/oauth/{provider}/callback`

`permitAll`, no tenant resolution by subdomain. Query parameters: `state` plus either `code` or
`error` (other params are ignored). The response is always a **302**; the body is never
rendered.

| Case | Redirect |
|---|---|
| Valid state + code, exchange and identity fetch OK | `https://{tenantHost}/teacher/settings/integrations?pending={pendingId}&provider={provider}` - **nothing is saved yet**; see "Confirm a finished connection" |
| Valid state + `error=access_denied` | `…/teacher/settings/integrations?error=denied&provider={provider}` |
| Valid state, exchange/identity/persist failed | `…/teacher/settings/integrations?error=failed&provider={provider}` |
| Unknown/expired/replayed state, or `{provider}` ≠ state's provider | `{platformBaseUrl}/integration-error` (security log entry) |

`tenantHost` is built from the tenant's own stored subdomain (`integrations.media.tenant-url-template`)
for the tenant recorded in the state - never from the callback request. The tokens are exchanged
and the account identity fetched here, then parked in Redis for 10 minutes (credentials encrypted)
under a fresh single-use `pendingId` that only this browser receives.

## Tenant Admin: `/api/v1/tenant-admin/integrations`

Permission `DomainArea.INTEGRATIONS`: `VIEW` for GETs, `CREATE_EDIT` for everything else.
Missing permission → `403 FORBIDDEN`.

### `GET /api/v1/tenant-admin/integrations`

`200` → `TenantIntegrationsResponse`:

```jsonc
{
  "teacherProviders": {
    "zoom":    { "enabled": true, "available": true },
    "youtube": { "enabled": true, "available": false },
    "vimeo":   { "enabled": false, "available": true }
  },
  "videoHost": "NONE",                      // NONE | DYNTUBE | BUNNY
  "dyntube": null,                          // current (or latest DISCONNECTED) ConnectionView, else null
  "bunny": null,
  "teacherConnections": [                   // every non-DISCONNECTED teacher connection in the tenant
    { "teacherId": "…", "teacherName": "Jane Perera", "connection": { /* ConnectionView */ } }
  ]
}
```

### `PUT /api/v1/tenant-admin/integrations/teacher-providers`

Body: `{ "zoomEnabled": true, "youtubeEnabled": true, "vimeoEnabled": false }`. All three are
required booleans.

- `200` → the `teacherProviders` object.
- `400 VALIDATION_ERROR`

Existing connections are not disconnected; they are just unused while the provider is off.

### `PUT /api/v1/tenant-admin/integrations/dyntube`

Body: `{ "apiKey": "…" }`. `apiKey` is optional when a DynTube key is already stored (blank keeps
it), required otherwise, and ≤512 characters.

- `200` → `ConnectionView`.
- `400 VALIDATION_ERROR`.
- `400 PROVIDER_CREDENTIALS_INVALID` with fieldErrors `[{field:"apiKey"}]`.
- `503`: the key is not configured or DynTube is unreachable.

The credentials are tested before anything is stored. A replacement marks the old row
DISCONNECTED (TENANT_ADMIN) and inserts a new one. If nothing changed, it returns the current
row with no new row.

### `PUT /api/v1/tenant-admin/integrations/bunny`

*Changed by MEDIA-INT-01A (ADR-018 Amendment 1).*

Body: `{ "apiKey": "…" }` - a bunny.net **account** API key (a sub-user key limited to Stream is
recommended). Same rules as DynTube: optional when an ACTIVE key is stored (blank keeps it),
required otherwise, ≤512 characters.

- The key is checked against Bunny's account API (`GET /videolibrary`) before anything is stored.
- `200` → `ConnectionView` with `accountLabel: "Bunny.net account"` and
  `settings: { "keyType": "ACCOUNT" }`.
- `400 VALIDATION_ERROR` naming the field if the retired MEDIA-INT-01 fields `libraryId`,
  `cdnHostname` or `tokenAuthKey` are sent with any non-null value ("is no longer accepted").
- `400 PROVIDER_CREDENTIALS_INVALID`, fieldErrors `[{field:"apiKey"}]`, when Bunny rejects the
  key - including a library-only Stream key ("use an account API key, not a video library key").
- `503`: the encryption key is not configured or Bunny is unreachable.

A connection saved under MEDIA-INT-01 (library key) is not migrated; its next test moves it to
`NEEDS_RECONNECT`.

### `GET /api/v1/tenant-admin/integrations/bunny/libraries`

*MEDIA-INT-01A.* `VIEW` permission. `200` → `TeacherLibraryRow[]`, sorted by `teacherName` ASC:

```jsonc
[
  {
    "teacherUserId": "0192…",
    "teacherName": "Jane Perera",
    "library": {                            // the teacher's latest library row, else null
      "id": "0192…",                        // our row id - never the Bunny library id
      "status": "ACTIVE",                   // PROVISIONING | ACTIVE | FAILED | UNAVAILABLE
      "libraryName": "Demo Institute - Jane Perera [a1b2c3d4e5f6]",
      "createdAt": "2026-09-29T08:00:00Z",
      "lastError": null                     // sanitised; "Not visible to the current Bunny.net account." for UNAVAILABLE
    },
    "canCreateLibrary": false               // Bunny connected + teacher approved & active + no library, a FAILED one, or a PROVISIONING one older than 5 minutes (interrupted)
  }
]
```

- Lists every **approved, active** teacher, plus any teacher who already has a library (for
  example one suspended since).
- `UNAVAILABLE` is worked out on read, not stored: Bunny is not connected, or the library was
  created under an earlier connection and the current key can't see it. That check makes at
  most **one** Bunny listing call; a Bunny outage leaves statuses as stored.
- No response contains a key or the Bunny numeric library id.

### `POST /api/v1/tenant-admin/integrations/bunny/libraries/{teacherUserId}`

*MEDIA-INT-01A.* `CREATE_EDIT` permission. No body. Creates the teacher's library in the
institute's Bunny account, or adopts one left by an interrupted attempt (found by the tag in its
name).

- `201` → `BunnyLibraryView` (the `library` object above), created or adopted.
- `200` → the teacher's existing ACTIVE library, unchanged (idempotent; Bunny is not called).
- `404 NOT_FOUND`: not an approved, active teacher **of this tenant** (another tenant's user
  id is a 404 and Bunny is never called).
- `409 CONFLICT`: no ACTIVE Bunny connection; the library is already being set up (a
  `PROVISIONING` row younger than 5 minutes); the stored key was rejected by Bunny (the
  connection moves to `NEEDS_RECONNECT`); or the matching Bunny library is already assigned
  to another teacher.
- `503 SERVICE_UNAVAILABLE`: Bunny could not be reached; the attempt is kept as a `FAILED`
  row and can be retried.

Every create, adoption and failure writes an audit entry
(`media_integration.bunny_library_created` / `media_integration.bunny_library_failed`).

For other domains (MEDIA-INT-03), `integrationmanagement.api.BunnyLibraryApi.ensureTeacherLibrary(teacherUserId)`
does the same; the caller must be that teacher, or hold `INTEGRATIONS` `CREATE_EDIT`.

### `POST /api/v1/tenant-admin/integrations/connections/{id}/test`

`200` → `ConnectionTestResult`. It works for tenant connections and teacher connections in the
same tenant. `404` if the id is in another tenant.

### `GET /api/v1/tenant-admin/integrations/connections/{id}/impact`

Same as the teacher version, for any connection in the tenant.

### `POST /api/v1/tenant-admin/integrations/connections/{id}/disconnect`

Body: `{ "reason": "Teacher left the institute" }`. `reason` is **required** (1–500 characters)
for TEACHER-scope connections and optional for TENANT-scope ones.

- `200` → `ConnectionView` (disconnectedBy TENANT_ADMIN).
- `400 VALIDATION_ERROR`.
- `404`.
- `409 CONFLICT` if already DISCONNECTED, or if it is the active `videoHost` connection. Set the
  video host to `NONE` first.

### `PUT /api/v1/tenant-admin/integrations/video-host`

Body: `{ "videoHost": "BUNNY" }` (`NONE | DYNTUBE | BUNNY`).

- `200` → `{ "videoHost": "BUNNY" }`.
- `400 VALIDATION_ERROR`.
- `409 PROVIDER_NOT_CONNECTED` if the chosen provider has no ACTIVE connection.

## Tenant Admin: SMS / WhatsApp messaging providers (Wave 11, ADR-023)

`/api/v1/tenant-admin/integrations/messaging` — `INTEGRATIONS` VIEW (Tenant Admin,
Read-only Auditor) / CREATE_EDIT (Tenant Admin). Tenant from the authenticated context.
Credentials are **write-only**: encrypted (AES-256-GCM, integrations key, AAD
`messaging:{tenant}:{channel}:{id}`), never returned, logged or audit-logged. Responses
carry only `credentialHint` (`••••` + last 4 characters when the secret has ≥ 12, else
`••••`).

Only adapters enabled in the running environment can be configured. Wave 11 ships only
`SANDBOX` (in-process, no network; `app.messaging.sandbox-enabled`, local/test only). In
production no provider is available until a vendor ADR adds one, so both channels stay off.

### `GET /api/v1/tenant-admin/integrations/messaging`

```json
{ "accounts": [
    { "channel": "SMS", "configured": true, "id": "uuid", "provider": "SANDBOX",
      "senderId": "ACADEMY", "credentialHint": "••••9876", "status": "ACTIVE",
      "disabledAt": null, "updatedAt": "…", "operational": true },
    { "channel": "WHATSAPP", "configured": false, "status": "NOT_CONFIGURED", … } ],
  "availableProviders": [ { "provider": "SANDBOX", "channels": ["SMS","WHATSAPP"], "credentialKeys": ["apiKey"] } ] }
```

### `PUT /api/v1/tenant-admin/integrations/messaging/{SMS|WHATSAPP}`

Body `{ "provider": "SANDBOX", "senderId": "ACADEMY", "credentials": { "apiKey": "…" } }`.
Replaces the channel's account: the previous ACTIVE row is disabled (rows are never
deleted — deliveries reference the account that sent them).

- `200` → `MessagingAccountView`.
- `400 MESSAGING_PROVIDER_NOT_AVAILABLE` — unknown provider or not enabled here.
- `400 VALIDATION_ERROR` — sender id (1–40 of letters, digits, space, `+ . _ -`), missing,
  oversize (> 512) or unexpected credential fields.
- `503` if the encryption key is not configured (fail closed).

### `POST …/messaging/{channel}/disable`

`200` → view with `status: DISABLED`; `409` if not configured. Queued deliveries for that
channel are then skipped (`CHANNEL_NOT_AVAILABLE`).

### `POST …/messaging/{channel}/test`

Body `{ "destination": "+94771234567" }` (7–15 digits). `200` →
`{ channel, destination (masked), providerMessageId }`; `409` with the fixed error code when
the provider refuses. Audit-logged with the masked destination.

Audit actions: `messaging.account_configured` (channel, provider, sender id, credential
*key names* only), `messaging.account_disabled`, `messaging.test_sent`.

## Zoom webhook (public): `POST /api/v1/integrations/webhooks/zoom`

`permitAll`, with headers `x-zm-signature` and `x-zm-request-timestamp`.

- **Verification comes first.** The signature must equal `v0=` + hex(HMAC-SHA256(secretToken,
  "v0:{timestamp}:{rawBody}")), compared in constant time. A timestamp more than 300 s from now,
  or a secret that isn't configured → `401` with zero writes.
- `event = endpoint.url_validation` → `200 {"plainToken": "<p>", "encryptedToken": hex(HMAC-SHA256(secretToken, p))}`.
- `event = app_deauthorized` → every non-DISCONNECTED ZOOM connection whose
  `external_account_id` equals `payload.user_id` → DISCONNECTED (PROVIDER), audited per tenant.
  Returns `200`, including when nothing matched.
- Meeting events (`meeting.started`, `meeting.ended`) are resolved **only** through
  `payload.object.id` → `class_session.provider_reference` (ADR-011). Unknown meetings → `200`
  no-op.
- Idempotency: key = `event` + `event_ts` + object id, stored in the existing
  `class_session_provider_event (provider='ZOOM', provider_event_id=key)`. A duplicate → `200`
  no-op.
- `recording.completed` is **accepted and ignored** (`200`) in this phase. See the live-class
  deltas below.

## Live-class contract deltas (`docs/api/live-class-management.md`)

These are additive changes to an approved contract, approved with the plan and ADR-018
(2026-09-28). No response field is removed or renamed.

1. **`POST /class-sessions`** has new pre-checks, run before anything is persisted. They are
   local DB lookups only:
   - `409 PROVIDER_DISABLED_BY_TENANT`: Zoom is switched off for the tenant.
   - `409 PROVIDER_NOT_CONNECTED`: the course's teacher has no ACTIVE Zoom connection.
   - `409 PROVIDER_RECONNECT_REQUIRED`: the connection is NEEDS_RECONNECT.
   - `409 PROVIDER_LICENSE_REQUIRED`: `accountType = BASIC`.

   When the platform Zoom OAuth app is not configured **and**
   `live-class.provider.fake-fallback-enabled=true` (local/test only), the fake adapter is used
   and these checks are skipped. A Zoom API failure *after* persist keeps today's behaviour:
   `providerStatus = FAILED`, retryable.
2. **`POST /class-sessions/{id}/retry-provisioning`** is widened. Besides `PENDING`/`FAILED`, it
   also re-provisions a `PROVISIONED` session whose host connection is not ACTIVE. It creates a
   new meeting on the teacher's *current* connection and replaces `providerReference`. This is
   the "Recreate meeting" action. `PROVISIONED` with an ACTIVE connection stays a no-op. The
   same pre-check 409s as create apply.
3. **`POST /class-sessions/{id}/join`**: the response shape is unchanged
   (`{joinUrl, expiresAt}`):
   - The **course-owning Teacher/TA** receives the Zoom host **start URL**, fetched fresh.
     `expiresAt` = now + 2 h, which is Zoom's start-URL lifetime.
   - **Students and staff** receive a personal **registrant join URL**. The caller is registered
     (idempotently) with their platform name and email, with Zoom registrant emails turned off.
     `expiresAt` = now + 2 h (advisory - call `join` fresh every time).
   - New `409 PROVIDER_NOT_CONNECTED`: the session's host connection is DISCONNECTED or
     NEEDS_RECONNECT. The message is client-safe ("The teacher's Zoom account is disconnected").
   - Existing 404/409/403 rules are unchanged.
4. **`GET /class-sessions/{id}/recording`**: for sessions hosted on a real Zoom connection it
   returns `404 NOT_FOUND` ("Recording not available") in this phase. Zoom cloud-recording links
   are not short-lived per-viewer URLs, so they can't satisfy `.claude/rules/security.md`.
   Recording delivery moves to MEDIA-INT-04 (export to the teacher's YouTube/Vimeo or the tenant
   video host). Fake-provider sessions are unchanged.
5. **`ClassSessionResponse`** gets one new field:
   `"hostConnectionStatus": "ACTIVE" | "NEEDS_RECONNECT" | "DISCONNECTED" | null`. It is `null`
   for fake-provider sessions and for Student callers, and it drives the "Zoom account
   disconnected / Recreate meeting" UI.
6. The webhook `POST /api/v1/integrations/webhooks/live-class` (generic HMAC) remains only for
   the fake provider (local/test).

## Cross-module (Java `api`, not REST)

- `LiveClassProviderApi`:
  - `createMeeting(tenantId, sessionId, hostTeacherUserId, title, start, end)` →
    `MeetingCreationResult{providerReference, connectionId}`
  - `getJoinUrl(connectionId, providerReference, participant)` where
    `participant = {userId, role HOST|ATTENDEE, firstName, lastName, email}`
  - `cancelMeeting(connectionId, providerReference)`
  - `getRecordingPlaybackUrl` is unchanged (fake only)
  - `verifySignature` is unchanged (generic webhook only)
- `MediaConnectionApi.findTeacherConnection(teacherUserId, MediaProvider)` →
  `Optional<TeacherConnectionSummary{connectionId, status, accountType}>`, plus
  `isTeacherProviderEnabled(MediaProvider)` and `connectionStatuses(Set<UUID>)` for
  `hostConnectionStatus`.
- The student/staff name and email for Zoom registration come from the existing user-management
  `api` lookup. They are never taken from the client.
