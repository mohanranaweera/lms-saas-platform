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
  "allowDownload": false,
  "heartbeatIntervalSeconds": 20,                 // null for Teacher preview
  "resumePositionSeconds": 0,                     // null for Teacher preview
  "remainingWatchSeconds": null                  // null when unlimited or Teacher preview
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
{
  "playbackToken": "<jwt>",
  "positionSeconds": 42,
  "watchedDeltaSeconds": 5,       // legacy telemetry; never authoritative
  "state": "PLAYING"              // PLAYING | PAUSED | IDLE; omitted legacy bodies infer IDLE/PLAYING from delta
}
```

The server is authoritative: it charges only while the client reports `PLAYING`: forward progress from the session's previous position, capped at
2 x server elapsed (the fastest player speed; owner decision 2026-10-08: with speed allowed, video
time watched counts); no forward progress while playing (rewind, rewatch, stall) counts server
elapsed. Elapsed is capped at 300 s per heartbeat. Pause/idle count nothing. The finite budget is snapshotted
per tenant/student/video from the tenant watch-time policy and the asset policy. Progress is flushed on
pause/end/exhaustion and stale ACTIVE sessions are expired by backend cleanup; Redis is only ephemeral
coordination/cache.

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
6. Watch-time accounting (rule above), measured from the SESSION's previous heartbeat
   position, so rewatching an earlier part is charged. The legacy client delta is not trusted.
7. When the snapshotted budget reaches zero, the session is revoked with
   `WATCH_TIME_EXHAUSTED`, audited, and the call returns `409 PLAYBACK_WATCH_LIMIT`. The
   refusal commits (the final accounting and the revoke are kept). Issuing a new session for
   that video (`POST /videos/{id}/playback-sessions` or `POST /playback/sessions`) is then
   refused with `409 PLAYBACK_WATCH_LIMIT`; the check runs after every access check.
8. Otherwise: the heartbeat is recorded (`authorized_watch_seconds` and the legacy
   `total_watched_seconds` accumulate; `last_position_seconds`/`furthest_position_seconds`
   advance; `last_position_seconds` is the resume point (latest), `furthest_position_seconds` the maximum).

**Success — `200`** (`ApiResponse<null>`). Any IP/device mismatch or policy-violation
revocation on this path writes a security audit-log entry, per
`.claude/rules/security.md`'s IP/device-anomaly revocation requirement.

### `POST /videos/playback-sessions/{id}/end`

Client-driven graceful end. Verifies the caller owns the session (`404 NOT_FOUND`
otherwise — anti-enumeration, matching the rest of this domain's Student-facing shape).
Idempotent: `ACTIVE → ENDED` if currently active, a no-op otherwise. **Success — `200`**
(`ApiResponse<null>`).

## Per-video watch-time limit (`/api/v1/videos/{id}/watch-time-limit`) — VIDEO Phase 12

Owner decision 2026-10-09: the limit is set per video (when it is added to a lesson, or later) and
**the video's own limit wins over the institute rule, higher or lower**. The institute-wide rule
(Tenant Admin video configuration) is only the default for videos without their own limit. It is
stored in `video_playback_policy.max_watch_duration_seconds` (the same field as the playback rules'
"Watch-time limit"); before this decision the lower of the two applied.

- `GET` → `200 {videoAssetId, videoLimitSeconds|null, instituteDefaultSeconds|null,
  instituteDefaultKnown, effectiveSeconds|null}` (`instituteDefaultKnown=false` when the institute rule
  needs the video's length and it isn't known yet).
- `PUT {limitSeconds: 60-3600000 | null}` → `200` (same shape). `null` removes the video's own limit.
  A change clears every Student's budget snapshot for the video, so it applies from their next
  heartbeat; time watched and staff grants are untouched. If the video had no policy row, one is
  created with playback's defaults (seeking allowed, no download, watermark on).
- Permission: the same ownership-aware gate as `PUT /videos/{id}/policy` (§2: the course's Teacher, or
  staff with `MATERIALS` CREATE_EDIT). `403` otherwise; `404` for another tenant's video; `400` outside
  the range. Audit `video.watch_time_limit_changed` on `video_asset` with `before`/`after` seconds
  (`"institute_rule"` when none) and `studentsAffected`.
- The full `PUT /videos/{id}/policy` also clears the snapshots when it changes the limit.
- UI: the Media Library picker has an optional "Watch-time limit (minutes)" for the video being added;
  each attached lesson video shows its limit with "Edit limit"; the material upload form's playback
  rules take the limit in minutes.

Tests: `VideoWatchTimeLimitIntegrationTest` (change applies to a blocked student, audit, defaults
kept, owner/other-teacher/student/staff permissions, cross-tenant), `WatchTimeServiceTest`
(video limit wins even when higher), Playwright `e2e/lesson-media.spec.ts`.

## Watch-time grants (`/api/v1/students/{studentId}/watch-time`) — VIDEO Phase 12

Staff view of a Student's watch time per video and extra time granted on top of the limit. `{studentId}`
is the student profile id (as for `/students/{studentId}/devices`). Permission `DomainArea.ACCESS_EXPIRY`:
VIEW to list (Tenant Admin, Finance Staff, Student Support, Read-only Auditor), CREATE_EDIT to grant
(Tenant Admin). Extra watch time is an access extension: every grant is audited.

**Rule.** Allowed time = the budget snapshot on `video_watch_progress` (or, before the Student's first
heartbeat, the video's own limit, else the institute rule) **plus the sum of grants**. Time watched
(`authorized_watch_seconds`) is never reduced. Grants live in `video_watch_time_grant` (V80): append-only
(DB trigger rejects UPDATE/DELETE), tenant-scoped, composite FKs to `video_asset` and `tenant_user`. The
heartbeat accounting, the start-time block (`409 PLAYBACK_WATCH_LIMIT`) and `remainingWatchSeconds` all use
the extended allowance, so a grant takes effect on the Student's next Play.

### `GET /students/{studentId}/watch-time`

`200` - one entry per video the Student has started, most recently watched first:

```json
{
  "videoAssetId": "…", "title": "Week 1 lecture",
  "limit": "LIMITED",            // LIMITED | NONE (no limit applies) | UNKNOWN (needs the video's length)
  "usedSeconds": 600, "baseSeconds": 600, "grantedSeconds": 1800,
  "allowedSeconds": 2400, "remainingSeconds": 1800, "exhausted": false,
  "lastWatchedAt": "2026-10-09T08:00:00Z",
  "grants": [{ "id": "…", "extraSeconds": 1800, "reason": "…", "grantedBy": "…", "grantedAt": "…" }]
}
```

`allowedSeconds`/`remainingSeconds` are null when no limit applies. `title` falls back to the upload's
file name for platform-storage videos (they have no title of their own). Another tenant's student:
`404`. The Watch time tab searches by video name and can show only used-up videos.

### `POST /students/{studentId}/watch-time/{videoAssetId}/grants`

Body `{ "extraMinutes": 1-6000, "reason": "1-500 chars" }` → `201` with the updated entry (shape above).

- `400 VALIDATION_ERROR` (`fieldErrors` on `extraMinutes` / `reason`).
- `403` without `ACCESS_EXPIRY`/`CREATE_EDIT`.
- `404` for an unknown/other-tenant student or video.
- `409` when no limit applies to the video (nothing to extend) or its limit needs a video length that
  isn't known yet.
- Audit: `video.watch_time_granted` on `student_profile` `{studentId}`, reason, metadata `videoAssetId`,
  `studentUserId`, `grantId`, `extraSeconds`, `usedSeconds`, `before`/`after` allowed seconds.

Tests: `StudentWatchTimeIntegrationTest` (grant lifts the block and keeps history, append-only trigger,
validation, permissions, cross-tenant), `WatchTimeServiceTest`, Playwright `e2e/student-watch-time.spec.ts`.
UI: Tenant Admin → Students → a student → **Watch time** tab ("Add time" with a required reason).

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
| Access window / max-views / device-fingerprint-mismatch violation | `POLICY_VIOLATION` | `409` |
| Server-authoritative watch-time budget exhausted | `PLAYBACK_WATCH_LIMIT` | `409` |
| Forward seek beyond tolerance while `allowSeeking = false` | `SEEK_NOT_ALLOWED` | `409` |
| Concurrency-cap race lost (cap = 1, two concurrent session requests) | `CONFLICT` | `409` |
| Playback token failed signature/expiry/`jti`-to-session validation, or its session is no longer `ACTIVE` | `PLAYBACK_TOKEN_INVALID` | `401` |

## Tenant isolation notes

- `video_asset`, `video_asset_status_event` (V70, append-only, DB trigger),
  `video_playback_policy`, `video_watch_session`, `video_watch_progress` (V79 watch-time
  counters, resume position, budget snapshot and heartbeat state) all carry
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
- Teacher / Teacher Assistant: their **own uploads**, plus media already attached to lessons in
  courses they teach (VIDEO Phase 6 / Q-010). Mutations remain limited to their own uploads;
  attachment/detachment uses the LessonMedia endpoint and course ownership checks.
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
  "uploadedBy": "<uuid>", "createdAt": "...", "attachedLessonCount": 2,
  "capabilities": { "supportsUpload": true, "supportsOverlayWatermark": true, "...": "14 booleans" },
  "enabledPlaybackModes": ["INTERNAL_SIGNED_URL"] }                    // provider-supported ∩ platform feature flags
```

`capabilities.supportsOverlayWatermark` / `supportsServerSideRevocation` are `false` for
YouTube. The UI must not imply that YouTube-hosted media is content-protected.

## Media Library management — VIDEO Phase 5

The visibility rules above apply.
- Teacher / Teacher Assistant: only their own uploads (Q-010).
- Other roles need `MATERIALS`: VIEW to read, CREATE_EDIT to edit, DELETE to delete (Q-011).

**`MediaAssetResponse` additions** (additive):
```jsonc
"uploadProgress": { "state": "TRANSFERRING",   // AWAITING_PARTS|QUEUED|TRANSFERRING|RETRY_WAIT|QUOTA_PAUSED|PARKED_RECONNECT|COMPLETED|FAILED|CANCELLED
                    "bytesSent": 8388608, "totalBytes": 734003200,
                    "message": null } | null,     // client-safe reason when paused / parked / failed; null for legacy uploads
"attached": false,                               // a lesson material uses it; it cannot be deleted
"attachedLessonCount": 0,                       // distinct lessons referencing this asset (Phase 6)
"youtubeUrl": "https://www.youtube.com/watch?v=…" | null   // Phase 5c: once YouTube has it
```

### `GET /media/upload-limits`

For whoever may upload (Student → `403`):

```jsonc
{ "uploadsEnabled": true, "channel": "CONNECTED",  // NOT_CONNECTED | CONNECTED | EXPIRED | DISABLED (platform switch-off)
  "editingEnabled": true,                           // false for channels connected before ADR-018 Amendment 3
  "dailyLimit": 20, "usedToday": 3, "maxFileSizeBytes": 10737418240, "partSizeBytes": 16777216,
  "allowedMimeTypes": ["video/mp4", "video/quicktime", "video/webm"] }
```

Since VIDEO Phase 5b, `upload-limits` also returns `canUpload` (false for a teacher who is not on
the institute channel's allowed-teachers list) and `notAllowedReason`.

Since VIDEO Phase 5d (named YouTube integrations - Media Library tabs):
- `GET /api/v1/media/channels` → `{ "integrations": [ { "integrationId", "name", "connected",
  "canUpload" } ], "hasArchived": bool }` - the tabs the caller sees. Staff with `MATERIALS` VIEW:
  every live integration. A Teacher / TA: the integrations they may upload to plus any holding
  their own videos. The Uploads tab (platform storage) is always shown by the UI.
- `GET /api/v1/media` adds `integrationId=` (one tab; another tenant's id → `404`) and
  `archived=true` (videos of deleted integrations); the Uploads tab uses `provider=INTERNAL`.
- `GET /api/v1/media/upload-limits?integrationId=` - limits and that integration's channel state
  (no id → `NOT_CONNECTED`; a deleted or foreign one → `404`). `canUpload` reflects that card's
  teacher list.
- `POST /api/v1/media` requires `integrationId` (missing → `400`; foreign or deleted → `404`; not
  connected → `409 VIDEO_PROVIDER_NOT_CONNECTED`; switched off → `409
  VIDEO_PROVIDER_DISABLED_BY_PLATFORM`; teacher not on its list → `403 VIDEO_UPLOAD_NOT_ALLOWED`).
  Retry stays on the video's own integration.
- Media views carry `integrationId` (null for platform-stored videos).

Since VIDEO Phase 5c:
- `upload-limits` returns `uploadsDisabledBy` (`PLATFORM` = the platform's
  `video.features.youtube-upload` flag / `VIDEO_YOUTUBE_UPLOAD_ENABLED` is off; `INSTITUTE` = the
  institute's "Video uploads" setting is off; `null` when uploads are on) and
  `uploadsDisabledReason`. The platform flag wins. `POST /media` answers `409
  VIDEO_UPLOADS_DISABLED` with the same distinct message.
- Media Library views carry `youtubeUrl` (the YouTube watch page) once the video has a YouTube id.
  It is only on `/api/v1/media` responses (staff with `MATERIALS` VIEW and the uploading teacher).
  An unlisted video can be watched by anyone with the link, so it must never be added to a
  student-facing view; student playback stays on the playback-token path.

**Allowed teachers (Phase 5b):** if the Tenant Admin has picked teachers for the institute
channel, `POST /media` and `/upload/retry` by any other Teacher / Teacher Assistant →
`403 VIDEO_UPLOAD_NOT_ALLOWED`. Their existing media can still be viewed, edited, cancelled and
deleted. Staff with `MATERIALS` are not affected. No teachers picked = every teacher may upload.

### `PATCH /media/{id}`

Body: any of `title` (1–100, no `<`/`>`), `description` (≤ 5000, no `<`/`>`; blank clears it) and
`privacyStatus` (`UNLISTED` | `PRIVATE`; YouTube media only). Returns `200` → `MediaAssetResponse`.

| Asset state | Effect |
|---|---|
| `CREATED` (file not sent yet) | LMS only. YouTube receives the new values when the upload starts. |
| `UPLOADING` / `UPLOADED` | `409 MEDIA_UPLOAD_STATE_CONFLICT`: the YouTube session already carries the old values |
| On YouTube (`PROCESSING` / `READY`, or `FAILED` after reaching YouTube) | Sent to YouTube first (`videos.update`; other YouTube settings are kept), then stored |

YouTube errors leave the LMS unchanged:

| Cause | Response |
|---|---|
| Channel connected without the edit scope | `409 VIDEO_PROVIDER_SCOPE_MISSING` (reconnect) |
| Grant revoked / channel replaced | `409 VIDEO_PROVIDER_AUTH_EXPIRED` |
| Channel switched off by the platform | `409 VIDEO_PROVIDER_DISABLED_BY_PLATFORM` |
| Video gone on YouTube | `422 MEDIA_UPLOAD_FAILED` |
| YouTube unreachable | `503` |

Audit: `media_asset.updated` with before/after title, description and privacy.

### `DELETE /media/{id}`

`200` (`data: null`, the codebase's DELETE convention). A **soft delete in the LMS only**: the YouTube video is kept (Q-017). The UI should say
so and point to YouTube Studio.

| Case | Response |
|---|---|
| A lesson material uses it | `409 MEDIA_ATTACHED` |
| An upload is still live | `409 MEDIA_UPLOAD_STATE_CONFLICT` (cancel first) |
| Already deleted / not visible | `404` |

The status history gets `→ DELETED` with the actor, and status sync stops. Audit:
`media_asset.deleted` (`provider`, `statusBefore`, `title`, `providerAssetId`, `providerCopyKept`).

## Media upload to YouTube (`/api/v1/media`) — VIDEO Phase 4

Upload into the tenant's own YouTube channel (ADR-018 Amendment 2), per plan §13 and VID-ADR-004:
1. the browser asks to start an upload;
2. it PUTs the file **directly to S3 staging** with presigned multipart part URLs;
3. it calls `complete`;
4. a background worker streams the staged file to YouTube's resumable upload;
5. a status sync moves the asset to `READY` / `FAILED`.

Video bytes never pass through a request thread.

**Who.**
- Teacher / Teacher Assistant: may upload, and act on **their own** uploads only.
- Any other role needs `MATERIALS` CREATE_EDIT (Tenant Admin, permitted staff).
- Student → `403`.
- Another teacher's, another tenant's, an `INTERNAL` or a deleted asset → `404`.
- The tenant, uploader and staging key always come from the server. A `tenantId` in the body
  is ignored.

**Gates, in order** (create; retry re-checks the switches and the channel):

| Check | Failure |
|---|---|
| role / permission | `403` |
| platform flag `video.features.youtube-upload` and tenant `uploadsEnabled` | `409 VIDEO_UPLOADS_DISABLED` |
| tenant YouTube channel connected | `409 VIDEO_PROVIDER_NOT_CONNECTED` |
| channel switched off by a Platform Admin (TD-008) | `409 VIDEO_PROVIDER_DISABLED_BY_PLATFORM` |
| channel needs reconnecting | `409 VIDEO_PROVIDER_AUTH_EXPIRED` |
| request fields | `400 VALIDATION_ERROR` (all field errors at once) |
| daily budget (`tenant_video_configuration.daily_upload_limit`, default 20 per Pacific day, Q-014) | `429 UPLOAD_QUOTA_EXCEEDED` |

### `POST /media`

```jsonc
{ "title": "Lesson 1",               // required, ≤ 100 chars, no < or >
  "description": "…",                // optional, ≤ 5000 chars, no < or >
  "privacyStatus": "UNLISTED",       // UNLISTED | PRIVATE (default: tenant defaultPrivacyStatus); PUBLIC → 400
  "provider": "YOUTUBE",             // optional; anything else → 400
  "fileName": "lesson-1.mp4",        // required, ≤ 255
  "mimeType": "video/mp4",           // video/mp4 | video/webm | video/quicktime
  "sizeBytes": 734003200 }           // 1 … video.upload.max-file-size-bytes (default 10 GiB)
```

**`201`** →
```jsonc
{ "mediaAsset": MediaAssetResponse,             // processingStatus CREATED
  "upload": { "partSizeBytes": 16777216, "partCount": 44,
              "parts": [ { "partNumber": 1, "url": "https://<s3>/…", "expiresAt": "…" } ] } }   // first ≤ 100 parts, 15-min URLs
```

The browser PUTs part *n* (bytes `[(n-1)·partSize, n·partSize)`) to its URL and keeps each
response's `ETag`.

### `POST /media/{id}/upload/parts`

Body `{ "partNumbers": [101, 102] }` (1–100 distinct numbers ≤ `partCount`). Returns
`UploadInstructions` with fresh URLs (also for re-signing expired ones). Only while the upload
waits for its parts; otherwise `409 MEDIA_UPLOAD_STATE_CONFLICT`.

### `POST /media/{id}/upload/complete`

Body `{ "parts": [ { "partNumber": 1, "etag": "\"…\"" } ] }`: every part exactly once. The server:
1. assembles the staging object;
2. checks its **size** equals `sizeBytes`;
3. reads its first 64 KiB to check the **real content type**;
4. queues the transfer.

**`202`** → `MediaAssetResponse` (`UPLOADING`). A size or content mismatch →
`422 MEDIA_UPLOAD_FAILED`: the staged file is deleted, the asset is `FAILED` and the budget slot
is returned.

### `POST /media/{id}/upload/cancel`

Cancels the live upload in any state, including a running transfer. The staged file and any
YouTube session are discarded. Before YouTube was contacted, the budget slot is returned.
`200` → asset `FAILED` ("Upload cancelled."). No live upload → `409 MEDIA_UPLOAD_STATE_CONFLICT`.

### `POST /media/{id}/upload/retry`

Re-queues a **failed transfer** whose staged file still exists (kept 48 h). It uses no new
budget slot. `202` → asset `UPLOADING`. Otherwise `409 MEDIA_UPLOAD_STATE_CONFLICT`: upload the
file again.

### Lifecycle and background behaviour

Asset: `CREATED → UPLOADING → UPLOADED → PROCESSING → READY`, or `FAILED` from any of them.
Worker and sync transitions are recorded in `video_asset_status_event` with `actor_id = NULL`
(Q-013). Job states (`media_upload_job.status`, never returned by the API):

| Situation | Job | Effect |
|---|---|---|
| 5xx, timeout, rate limit | `RETRY_WAIT` | exponential backoff 30 s → 30 min; `FAILED` after 8 attempts; resumes from the byte YouTube confirmed |
| Session expired (404/410) | `RETRY_WAIT` | new session from byte 0 |
| YouTube quota exhausted | `QUOTA_PAUSED` | resumes after the next Pacific midnight; other tenants unaffected |
| Grant revoked (`invalid_grant`, 401 after one refresh) | `PARKED_RECONNECT` | connection → NEEDS_RECONNECT; resumes once the **same channel** is reconnected (fails after 7 days) |
| Channel switched off by the platform | `PARKED_RECONNECT` | resumes when a Platform Admin re-enables it |
| YouTube refuses the video | `FAILED` | asset `FAILED` with a client-safe reason |

- **Concurrency:** at most `video.upload.max-concurrent-transfers` (default 2) per instance, and
  one transfer per tenant.
- **Memory:** one `chunk-size-bytes` buffer (default 8 MiB) per transfer.
- **Status sync:** `videos.list` in batches of 50, backing off 1 min → 30 min. "Still processing"
  for about 26 h → `FAILED`. A channel that cannot be asked postpones the check; it never fails it.

### Staging bucket (devops prerequisite, not deployed by the app)

Staging objects live in the `object-storage.bucket` under `video-staging/{tenantId}/{assetId}/…`.
They are deleted after transfer. The app sweeps abandoned and failed ones after 48 h. Production
also needs:
- a **lifecycle rule** on `video-staging/`: abort incomplete multipart uploads after 2 days and
  expire objects after 3 days;
- **CORS** on the bucket for the tenant origins: `PUT` only, exposing the `ETag` header.

Without `object-storage.*` configured, every upload call answers `503`.

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
