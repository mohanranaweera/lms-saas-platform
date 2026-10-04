# video-access-management — API Contract

Covers secure video upload, playback-policy management, and entitlement-checked
playback-session issuance/heartbeat/end (Wave 5, PAR-17-01/PAR-20-01–04,
`com.lms.videoaccessmanagement`). `video-access-management` is a first-implementation
of a domain already reserved in `.claude/rules/architecture.md`'s confirmed backend
domain list — this file did not exist before this wave. Derived directly from the
shipped implementation (`VideoController`, its DTOs, `VideoAssetService`,
`VideoPlaybackSessionService`, `VideoAccessGuard`), not from a pre-implementation draft.

This domain is called by `content-management` only through its own `api` package
(`VideoAccessApi`) — see `docs/api/content-management.md`'s "Cross-module interaction"
section for the reverse direction (a `Material` referencing a `videoAssetId`).
`video-access-management` in turn depends on `content-management` only through
*its* `api` package (`MaterialLookupApi#resolveVideoAssetOwnership`), never on
`content-management`'s `domain`/`repository` classes — the two domains resolve each
other's ownership exclusively via `api` interfaces, per
`.claude/rules/architecture.md`'s cross-module boundary rule.

## Response envelope

Every endpoint below returns `com.lms.common.api.ApiResponse<T>` — see
`docs/api/identity-access-service.md`'s "Response envelope" section for the exact
shape; identical here, not repeated.

## Auth requirements

Every endpoint requires a valid `Authorization: Bearer <accessToken>` header
(`@PreAuthorize("isAuthenticated()")` is a coarse gate only). The real authorization
runs inside the service layer / `VideoAccessGuard`, independently on every call, never
cached or inherited across requests — see "Authorization model" below.

## Authorization model

Three distinct gates exist in this domain, deliberately not unified into one guard,
because each endpoint has a different amount of ownership context available at the
time it runs:

### 1. Upload (`POST /videos`) — loose, pre-attach gate

`VideoAssetService#requireUploadOrPolicyAuthorization`: Teacher/Teacher-Assistant (any
course, no ownership check), or staff holding `DomainArea.MATERIALS`/`CREATE_EDIT`.
This is intentionally loose: a freshly-uploaded video has no owning `Material` yet, so
there is nothing to resolve course-ownership *from* at this point. The real,
ownership-scoped check happens later, at attach-to-material time (see
`docs/api/content-management.md`'s `POST .../materials` `VIDEO`/`RECORDING` branch,
which calls `VideoAccessApi#isVideoAssetReadyAndOwnedByTenant`). This is a documented
two-step flow ("upload first, loosely gated" → "attach second, where the real
tenant/ownership check runs"), not a gap.

### 2. Policy upsert (`PUT /videos/{id}/policy`) — as-fixed, ownership-aware gate

`VideoAssetService#requirePolicyAuthorization` resolves the video asset's owning
`Material` via `MaterialLookupApi#resolveVideoAssetOwnership`:

- **If the asset is already attached to a `Material`** (the normal case for any asset a
  Student could ever reach): a Teacher/TA may upsert the policy **only if they own the
  resolved course** (`ownership.teacherId()` must equal the caller's own user id) —
  `403 FORBIDDEN` otherwise. Staff still uses the flat `MATERIALS`/`CREATE_EDIT` grant
  (ownership doesn't apply to staff, matching every other guard in this codebase).
- **If the asset is not yet attached to any `Material`** (the narrow pre-attach window
  right after upload): there is nothing to resolve ownership from yet, so this falls
  back to the same loose gate as upload (§1 above) — any Teacher/TA in the tenant, or
  staff with `MATERIALS`/`CREATE_EDIT`.
- This is a security-review fix, not the original shipped behavior: the loose
  upload-time gate was initially reused for policy upsert unconditionally, which was a
  genuine intra-tenant IDOR — Teacher B could silently strip Teacher A's
  watermark/download/view-limit protections off a video Teacher B doesn't own. The
  as-fixed behavior above is what ships. **Do not assume the loose gate applies once a
  video is attached to a Material** — it only ever applies in the pre-attach window.

### 3. Playback (`POST /videos/{id}/playback-sessions` and all `/playback-sessions/{id}/...` endpoints) — `VideoAccessGuard`

`VideoAccessGuard#requireEntitlement(videoAssetId, action)` resolves ownership via
`MaterialLookupApi#resolveVideoAssetOwnership` and applies this precedence:

1. **Not resolvable at all** (nonexistent id, cross-tenant id, or simply not yet
   attached to any material) → `404 NOT_FOUND` ("Video not found") for **every role**,
   including Teacher/staff. This is deliberately stricter than
   `MaterialAccessGuard`'s anti-enumeration rule (which only applies to Student) —
   there is no lesson/course path segment here to already prove tenant membership the
   way a nested material URL does.
2. **Teacher / Teacher Assistant**: allowed only if they own the resolved course
   (`ownership.teacherId()` equals their own user id); `403 FORBIDDEN` otherwise.
3. **Student**: allowed only for `action == VIEW` **and** `ownership.coursePublished()`,
   **and** `EnrollmentAccessApi.resolveAccessState(studentId, courseId).state() ==
   ACTIVE` — any other case → `404 NOT_FOUND` ("Video not found"), never a
   distinguishing `403`. This mirrors `MaterialAccessGuard`'s Wave 5 enrollment fix
   exactly (see `docs/api/content-management.md`).
4. Otherwise (staff): `permissionCheckService.requirePermission(DomainArea.MATERIALS,
   action)`.

A Teacher/TA who passes this gate gets a **preview session** with no policy limits
applied at all (see "Teacher preview" under the playback-session endpoint below) — an
already-verified course owner is never subject to their own video's playback policy.

## Endpoints

Base path: `/api/v1/videos`

### `POST /videos`

Upload a video asset. **Multipart** (`multipart/form-data`).

| Part | Type | Notes |
|---|---|---|
| `file` | file | Required. Server-side validated in order: size (`app.video-access.asset.max-file-size-bytes`, currently `26214400` = 25 MiB) then magic-byte content-sniffing (`VideoContentSniffer`) against an allow-list of `video/mp4`, `video/webm`, `video/quicktime` — never the client-declared `Content-Type` or file extension. |

Gate: §1 above. **Success — `201`** (`ApiResponse<VideoAssetResponse>`):

```jsonc
{
  "id": "<uuid>", "originalFilename": "lecture-01.mp4", "mimeType": "video/mp4",
  "sizeBytes": 52428800, "durationSeconds": null,   // best-effort probe; not populated this wave — see Section 11 deferral note
  "status": "READY",                                 // upload is fully synchronous — INTERNAL assets are always created READY
  "uploadedBy": "<uuid>", "createdAt": "2026-08-17T00:00:00Z"
}
```

**`413 PAYLOAD_TOO_LARGE`** on oversize. **`415 UNSUPPORTED_MEDIA_TYPE`** on a failed
content-sniff. On any validation failure, zero storage calls are made and zero rows are
persisted — storage `store()` must fully succeed before a `video_asset` row is ever
constructed (no partial write on a storage error).

A freshly-uploaded asset is **not yet attachable to a Material for real use** until it
is referenced as a `videoAssetId` on `POST .../materials` (see
`docs/api/content-management.md`) — until then it has no course/enrollment entitlement
resolvable against it, so `VideoAccessGuard` (playback) rejects it with `404` for every
role except the loose pre-attach policy-upsert window (§2 above).

### `PUT /videos/{id}/policy`

Create or update the `VideoPlaybackPolicy` for `id` (upsert — one policy per asset).
Gate: §2 above (as-fixed ownership-aware behavior).

Request (`VideoPlaybackPolicyRequest`):

```jsonc
{
  "accessStartAt": null,              // optional, ISO-8601 instant
  "accessEndAt": null,                // optional; if both set, must be after accessStartAt
  "maxViewsPerStudent": null,         // optional, positive int
  "maxWatchDurationSeconds": null,    // optional, positive int
  "allowSeeking": true,
  "allowDownload": false,
  "watermarkEnabled": true,
  "maxConcurrentSessions": 1          // optional, positive int; null defaults to the platform default of 1
}
```

**Success — `200`** (`ApiResponse<VideoPlaybackPolicyResponse>`, same shape as the
request plus `videoAssetId`). **`400 VALIDATION_ERROR`** if `accessEndAt` is not after
`accessStartAt`, or any of `maxViewsPerStudent`/`maxWatchDurationSeconds`/
`maxConcurrentSessions` is supplied as `<= 0`. **`404 NOT_FOUND`** if `id` does not
resolve to a `video_asset` in the caller's tenant. **`403 FORBIDDEN`** per §2's
ownership rule.

A missing policy row (no `PUT` ever called for an asset) is not an error anywhere in
this domain — every read path treats it as "platform default policy" in effect:
`allowSeeking=true`, `allowDownload=false`, `watermarkEnabled=true`,
`maxConcurrentSessions=1`, no access-window/view/duration limits.

### `POST /videos/{id}/playback-sessions`

The entitlement-checked playback-session issuance endpoint — the PAR-17-01/PAR-20-01
mechanism. Gate: §3 above (`VideoAccessGuard`).

**Teacher/TA (preview)**: no `VideoWatchSession` row, no JWT, no policy limits applied
at all — returns a short-lived signed storage URL only (`watchSessionId`/
`playbackToken` are `null` in the response). This is a deliberate design choice
(documented in `VideoPlaybackSessionService`'s class javadoc): an already
ownership-verified course owner never needs a second, shorter-lived, revocable grant
layered on top of their own normal login session.

**Student**: resolves the effective policy (asset's own row, or the platform default
above), then in order:

1. `accessStartAt`/`accessEndAt` window check → `409 POLICY_VIOLATION` if outside the
   window ("not yet available" / "availability window has expired").
2. `maxViewsPerStudent` check against `video_watch_progress.views_count` → `409
   POLICY_VIOLATION` ("maximum view limit has been reached") if already at/over the cap.
3. Concurrency-cap enforcement: if the student already has `maxConcurrentSessions` (or
   more) `ACTIVE` sessions for this video, the single oldest is atomically revoked
   (`SUPERSEDED_BY_NEW_SESSION`) to make room. For the default cap of 1, a DB-level
   partial unique index (`uq_video_watch_session_single_active`) is the real backstop
   against a genuine race between two concurrent requests — a race loser gets `409
   CONFLICT` ("Another playback session request for this video is already in
   progress..."), safely retryable.
4. On success: creates a new `VideoWatchSession`, increments `views_count`, caches the
   session's `jti` in Redis (bounded TTL), issues a signed **3-minute**, single-use JWT
   playback token (`PlaybackTokenService.TOKEN_TTL`), and mints a short-lived signed
   storage URL from `ObjectStorageApi` with the same TTL.

**Success — `201`** (`ApiResponse<PlaybackSessionResponse>`):

```jsonc
{
  "watchSessionId": "<uuid or null (Teacher preview)>",
  "playbackToken": "<jwt or null (Teacher preview)>",
  "signedUrl": "https://...",
  "expiresAt": "2026-08-17T00:03:00Z",
  "watermarkText": "<studentId> · <tenantId>",   // null if watermarkEnabled=false or Teacher preview
  "allowSeeking": true,
  "allowDownload": false
}
```

**`404 NOT_FOUND`** per §3's anti-enumeration rule (unresolvable/cross-tenant asset,
unenrolled/expired-enrollment Student, Teacher-not-owner is `403` not `404` — see §3
item 2). **`409 POLICY_VIOLATION`** for window/view-limit denial. **`409 CONFLICT`** on
the rare genuine concurrency-cap race.

### `POST /videos/playback-sessions/{id}/progress`

Playback heartbeat — re-validates the token **and** the session server-side on
**every** call (PAR-20-02), which is what makes "every playback request re-validated
server-side" true in practice, not just at session-issuance time (the signed storage
URL shares the playback token's short TTL, so a long video forces the client back
through this endpoint before it can fetch a fresh signed URL).

Request (`PlaybackProgressRequest`):

```jsonc
{ "playbackToken": "<jwt>", "positionSeconds": 42, "watchedDeltaSeconds": 5 }
```

Validation order, on every call:

1. Token signature/expiry parse (`PlaybackTokenService.parseAndValidate`) — any failure
   → `401 PLAYBACK_TOKEN_INVALID`.
2. Token claims (`watchSessionId`, `tenantId`, `studentId`) must match the path
   `{id}`/caller's resolved tenant/caller's own user id → `401 PLAYBACK_TOKEN_INVALID`
   otherwise (never distinguished — a caller probing with someone else's token/session
   id learns nothing about which check failed).
3. Session lookup: `jti` must match the token's `jti`, `studentId` must match the
   caller, and `status` must be `ACTIVE` → `401 PLAYBACK_TOKEN_INVALID` otherwise.
4. Device-fingerprint check: if the session recorded a fingerprint hash at issuance and
   the current request's hash differs, the session is revoked
   (`POLICY_VIOLATION`/`device_fingerprint_mismatch`, audit-logged) and the call is
   rejected — `409 POLICY_VIOLATION` ("...used from a different device").
5. Seek check: if the effective policy's `allowSeeking = false` and
   `positionSeconds` exceeds `furthest_position_seconds + 5` seconds tolerance → `409
   SEEK_NOT_ALLOWED` ("Seeking ahead is not allowed for this video"). The heartbeat is
   rejected outright — no partial progress is ever applied.
6. Max-watch-duration check: if the effective policy's `maxWatchDurationSeconds` is set
   and `total_watched_seconds + watchedDeltaSeconds` would exceed it, the session is
   revoked (`POLICY_VIOLATION`/`max_watch_duration_exceeded`, audit-logged) and the call
   is rejected — `409 POLICY_VIOLATION` ("...maximum watch duration has been reached").
7. Otherwise: the heartbeat is recorded (`total_watched_seconds` accumulates,
   `furthest_position_seconds` advances).

**Success — `200`** (`ApiResponse<null>`). Any IP/device mismatch or policy-violation
revocation on this path writes a security audit-log entry, per
`.claude/rules/security.md`'s IP/device-anomaly revocation requirement.

### `POST /videos/playback-sessions/{id}/end`

Client-driven graceful end. Verifies the caller owns the session (`404 NOT_FOUND`
otherwise — anti-enumeration, matching the rest of this domain's Student-facing shape).
Idempotent: `ACTIVE → ENDED` if currently active, a no-op otherwise. **Success — `200`**
(`ApiResponse<null>`).

## Response shapes

**`VideoAssetResponse`** — metadata only, **never** a raw storage URL or key:

```jsonc
{ "id": "<uuid>", "originalFilename": "...", "mimeType": "video/mp4", "sizeBytes": 52428800,
  "durationSeconds": null, "status": "READY", "uploadedBy": "<uuid>", "createdAt": "..." }
```

**`VideoPlaybackPolicyResponse`** — see the `PUT .../policy` request/response shape
above (identical fields plus `videoAssetId`).

**`PlaybackSessionResponse`** / progress and end — see the corresponding endpoint
sections above.

## Error codes

Standard `ApiErrorCodes` (see `docs/api/identity-access-service.md`'s error-codes
table) apply. Video-access-management-specific cases:

| Case | Code | Status |
|---|---|---|
| Video asset unresolvable in caller's tenant (nonexistent, cross-tenant, or not yet attached to any material) | `NOT_FOUND` | `404` (uniform for every role on the playback path — see §3) |
| Teacher/TA not owning the video's resolved course (playback, or policy once attached) | `FORBIDDEN` | `403` |
| Staff without `MATERIALS` grant | `FORBIDDEN` | `403` |
| Student not enrolled / expired enrollment / unpublished course, on playback-session issuance | `NOT_FOUND` | `404` (anti-enumeration) |
| Oversized video upload | `PAYLOAD_TOO_LARGE` | `413` |
| Failed video content-sniff | `UNSUPPORTED_MEDIA_TYPE` | `415` |
| Invalid playback-policy field combination | `VALIDATION_ERROR` | `400` |
| Access window / max-views / max-watch-duration / device-fingerprint-mismatch violation | `POLICY_VIOLATION` | `409` |
| Forward seek beyond tolerance while `allowSeeking = false` | `SEEK_NOT_ALLOWED` | `409` |
| Concurrency-cap race lost (cap = 1, two concurrent session requests) | `CONFLICT` | `409` |
| Playback token failed signature/expiry/`jti`-to-session validation, or its session is no longer `ACTIVE` | `PLAYBACK_TOKEN_INVALID` | `401` |

## Tenant isolation notes

- `video_asset`, `video_asset_status_event` (V70, append-only, DB trigger),
  `video_playback_policy`, `video_watch_session`, `video_watch_progress` all carry
  `tenant_id NOT NULL` with tenant-leading indexes; every repository extends the shared
  tenant-aware base. `video_asset.provider_connection_id` is a composite
  `(tenant_id, …)` FK into `media_provider_connection`, so an asset can only use its own
  tenant's provider connection.
- `playback_jti` is globally unique (JWT identity requirement), but every lookup *from*
  a token back to a session still re-checks the token's own `tenant_id`/`studentId`
  claims against the resolved row before trusting it — a token cannot be replayed
  across tenants even if a `jti` were somehow guessed.
- Cross-tenant negative tests are mandatory and shipped for this domain: a Student (or
  Teacher) from tenant A requesting a playback session for a tenant B video asset by id
  → `404`, never `200`-with-filtered-data or a distinguishing `403`.

## Explicit judgment calls carried into this contract

- **`LINK`-type materials never reach this API at all.** A `Material` of type `LINK`
  (external YouTube/Vimeo URL) has no `videoAssetId` and is never routed through
  `video-access-management` — see `docs/api/content-management.md`'s note on this
  wave's PAR-27-03 decision. There is no server-side control over a third-party
  player, so no playback token, watermark, or view/duration limit exists for `LINK`
  materials once the entitlement-checked external URL is handed to the student.
- **No new `DomainArea`** was added for video management — staff authorization reuses
  the existing `MATERIALS` permission row (video is modeled as a material sub-type,
  not a separately staff-permissioned resource).
- **Status set widened by V70 (VIDEO Phase 2).** `status` is now one of `CREATED`,
  `UPLOADING`, `UPLOADED`, `PROCESSING`, `READY`, `FAILED`, `DELETED`. `PENDING` was never
  persisted and was removed. The `POST /videos` upload is still fully synchronous, so
  every asset it creates is an `INTERNAL` asset that is `READY` immediately. The other
  states belong to externally hosted (YouTube) assets from Phase 4 onward.

## Media Library (`/api/v1/media`) — VIDEO Phase 2

Provider-neutral, read-only view of `video_asset` (the MediaAsset). This is additive:
`/videos/*` above is unchanged. Plan: `docs/plans/multi-tenant video platform + aoc secure player.md`.

**Authorization.**
- Teacher / Teacher Assistant: their **own uploads** only.
- Any other role needs `MATERIALS`/`VIEW` and sees the whole tenant library. Tenant
  Admin is the usual case.
- Student: `403 FORBIDDEN`.
- An asset the caller may not see (another teacher's, another tenant's, unknown or
  soft-deleted) returns `404 NOT_FOUND`.
- The tenant always comes from the authenticated context; a `tenantId` query or body
  parameter is ignored.

### `GET /media`

Query (all optional):

| Param | Type | Notes |
|---|---|---|
| `status` | `VideoAssetStatus` | exact match |
| `provider` | `INTERNAL` \| `YOUTUBE` | exact match |
| `q` | string ≤ 200 | case-insensitive substring of title or original filename; `%`/`_` are matched literally |
| `page` | int ≥ 0 | default 0 |
| `size` | 1–100 | default 20; out of range → `400 VALIDATION_ERROR` |

Sorted newest first. **Success — `200`**, `ApiResponse<PageResponse<MediaAssetResponse>>`.

### `GET /media/{id}`

**Success — `200`**, `ApiResponse<MediaAssetResponse>`. Not visible → `404`.

**`MediaAssetResponse`** — never contains the storage object key, provider connection
id or any credential:

```jsonc
{ "id": "<uuid>", "provider": "INTERNAL", "title": "lecture-1.mp4",   // INTERNAL assets fall back to the filename
  "description": null, "thumbnailUrl": null, "durationSeconds": null,
  "privacyStatus": null,                                               // PRIVATE|UNLISTED|PUBLIC for external providers
  "processingStatus": "READY", "failureReason": null,
  "uploadedBy": "<uuid>", "createdAt": "...",
  "capabilities": { "supportsUpload": true, "supportsOverlayWatermark": true, "...": "14 booleans" },
  "enabledPlaybackModes": ["INTERNAL_SIGNED_URL"] }                    // provider-supported ∩ platform feature flags
```

`capabilities.supportsOverlayWatermark` / `supportsServerSideRevocation` are `false` for
YouTube. The UI must not imply that YouTube-hosted media is content-protected.

## Tenant video configuration (`/api/v1/tenant-admin/video-configuration`) — VIDEO Phase 3

Tenant-wide video settings (plan §11).
- Requires `INTEGRATIONS` VIEW (`GET`) / CREATE_EDIT (`PUT`). Teacher/Student → `403`.
- One row per tenant, always the authenticated tenant's; a `tenantId` in the body is ignored.
- `max_registered_devices` is **not** here; it stays in the ADR-022 device policy.

`GET` → `{settings, customized, updatedAt, updatedBy}`. `customized=false` means the platform
defaults (no row yet).

`PUT` (full replacement) body = `settings`:

| Field | Allowed | Default |
|---|---|---|
| `defaultProvider` | `INTERNAL`, `YOUTUBE` | `INTERNAL` |
| `defaultPrivacyStatus` | `UNLISTED`, `PRIVATE` | `UNLISTED` |
| `uploadsEnabled`, `nativePlayerRequired` | boolean | `true`, `false` |
| `watermarkEnabled` | boolean | `true` |
| `watermarkOpacity` | 0.05–0.60 | 0.25 |
| `watermarkMovementIntervalSeconds` | 5–120 | 30 |
| `watermarkFields` | non-empty subset of `STUDENT_DISPLAY_ID, MASKED_EMAIL, MASKED_PHONE, SESSION_ID, TIMESTAMP` | `STUDENT_DISPLAY_ID, TIMESTAMP` |
| `captureProtectionEnabled` / `captureProtectionRequired` | boolean; `required` needs `enabled` | `true` / `false` |
| `maxConcurrentSessions` | 1–5 | 1 |
| `watchTimePolicy` | `NONE`, `VIDEO_DURATION_MULTIPLIER` (+ `watchTimeMultiplier` 1.00–10.00), `FIXED_MINUTES` (+ `watchTimeFixedMinutes` 1–10000) | `NONE` |
| `launchCodeTtlSeconds` | 15–60 | 45 |
| `heartbeatIntervalSeconds` | 10–60 | 20 |

- Invalid input → `400 VALIDATION_ERROR` with field errors.
- A watch-time value that doesn't belong to the chosen policy is dropped, not rejected.
- Every change is audited as `video_configuration.updated`, with the full `before` (or
  `PLATFORM_DEFAULTS`) and `after`.
- These settings are stored now; they are enforced by Phases 7–12.
