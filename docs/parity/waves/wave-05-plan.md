# Wave 5 Plan — Learning Materials + Secure Video

Status: **DONE.** Backend (`content-management` material-type extension + `MaterialAccessGuard`
enrollment fix, new `video-access-management` domain, `S3ObjectStorageApi` adapter), frontend
(Teacher material/video-policy authoring, Student per-type consumption + secure video player),
tests (1845 backend JUnit/Testcontainers tests incl. cross-tenant/IDOR/concurrency/token-validation
coverage; 654 frontend Playwright tests), a dedicated security review and tenant-isolation review
(each independently spawned), three security fixes those reviews produced, and documentation are
all complete — see the Wave 5 completion report appended at the end of this document for the full
verification summary and the security-review findings/fixes in detail.

Parity IDs in scope: **PAR-06-02** (fix — see Phase A finding below), **PAR-06-03**,
**PAR-06-04** (verify-only), **PAR-06-05 / PAR-27-01**, **PAR-17-01**, **PAR-20-01
through PAR-20-04**. **PAR-20-05** (real production object-storage vendor) and
**PAR-27-03** (whether externally-linked video is exempt from secure-video controls)
are resolved as explicit judgment calls in Section 10, not silently assumed.
**PAR-XC-04** (centralized cross-domain `AccessPolicyService`) and **PAR-09-04 /
PAR-18-02** (cross-type expiry unification) are explicitly deferred to Wave 6 —
Section 11.

---

## 1. Phase A — Analysis (findings)

### Materials today (`com.lms.contentmanagement.material`)

- `Material` (V16, MVP-009) is a single-file-per-row model: `tenantId`, `lessonId`
  (**required**, opaque cross-domain id — no JPA relation), `title`,
  `originalFilename`, `storageObjectKey`, `mimeType`, `sizeBytes`, `sequence`,
  `visibility` (`VISIBLE`/`HIDDEN` only), `expiryAt`, `uploadedBy`.
- **`expiryAt` is a dead column** — set nowhere, read nowhere. No `MaterialType`
  discriminator exists at all (one material = one uploaded file). No download-count
  or max-views tracking exists. No Course- or Session-level association exists
  (Lesson-only).
- Upload path: bounded stream read → `ContentSniffer.sniff()` (PDF/image/text
  allow-list) → `ObjectStorageApi.store()`. Download: a fresh
  `objectStorageApi.generateSignedDownloadUrl(key, Duration.ofMinutes(5))` on every
  request — correct "never a stable URL" shape, but with no view/download-count
  enforcement layered on top.

### Pre-existing security gap found (fix in scope this wave, not a new activation mechanism)

- `MaterialAccessGuard`'s Student branch checks **only `ownership.coursePublished()`**
  — **no enrollment check at all.** Any authenticated Student in the tenant can
  list/view/download materials of any published course, enrolled or not, given a
  courseId/moduleId/lessonId. `docs/parity/klass-parity-matrix.md` row PAR-06-02
  currently (incorrectly) claims this is "scoped by enrollment / MATCHES" — that is
  stale relative to the actual code and will be corrected in Phase F.
- The correct, already-established pattern in this codebase is
  `LiveClassAccessGuard.requireEntitlement` (Wave 4): for `STUDENT_ROLE`, call
  `EnrollmentAccessApi.resolveAccessState(studentId, courseId)` and require
  `ACTIVE`, else throw `NotFoundException` (anti-enumeration — never a plain 403, so
  a Student cannot distinguish "wrong tenant" from "not enrolled" from
  "doesn't exist"). Wave 5 applies the identical pattern to
  `MaterialAccessGuard` and to the new video entitlement guard. This directly serves
  the wave objective ("complete parity **without weakening** protected-content
  security") — it closes a gap, it does not introduce a new enrollment-activation
  mechanism, so it is not a change-controlled "enrollment activation rules" edit
  under root `CLAUDE.md`.

### Object storage (`com.lms.integrationmanagement`)

- `ObjectStorageApi` (`store`, `delete`, `generateSignedDownloadUrl`) is a narrow
  port. `UnavailableObjectStorageApi` is the **only** bean today — throws 503 on
  every call. No real provider is wired up.
- `infrastructure/docker-compose.dev.yml` **already provisions MinIO** (S3-compatible,
  dev-only, credentials from `.env.dev`) — it just has no backend adapter wired to
  it yet. Per `.claude/rules/architecture.md`, video/content storage must be
  external, never self-hosted on the app VPS, and owned exclusively by
  `integration-management`. Wiring a real `S3ObjectStorageApi` adapter (AWS SDK v2
  S3 client, endpoint-configurable so it points at MinIO in dev and can point at any
  S3-compatible vendor in production) against the **already-provisioned dev
  infrastructure** is completing existing wiring, not a new vendor decision — see
  Section 10 for the narrow piece that *is* still an open decision (which vendor
  backs production).
- `ShortLivedPlaybackLink` already exists (used today only by
  `LiveClassProviderApi.getRecordingPlaybackUrl` for Zoom-hosted recordings) — Wave 5
  reuses this same shape for video-asset playback links.

### Enrollment/entitlement (`com.lms.enrollmentmanagement.api`)

- `EnrollmentAccessApi.resolveAccessState(studentId, courseId)` is the single
  trusted source of "is this student's access currently active" (self-healing into
  `EnrollmentExpiryEvent` on first observed expiry). This is what both the
  `MaterialAccessGuard` fix and the new video entitlement guard call.

### Device/session security (`com.lms.identityaccessservice`)

- `DeviceSession`/`DeviceSessionCacheService`/`DeviceFingerprint` are **login/session**
  concepts only (device fingerprint hashing, Redis fast-path cache over a Postgres
  source of truth). There is **no device-limit enforcement or override-precedence
  engine anywhere** — the codebase explicitly defers that to a later phase, outside
  this wave. There is also **no concurrent-playback-session concept** — that is
  100% new in this wave, scoped narrowly to video (`VideoWatchSession`), and reuses
  `DeviceFingerprint`'s existing server-side hashing utility rather than building a
  second one.

### Existing Video/Zoom/Recording (Wave 4, reused not duplicated)

- `ClassSessionRecording` (Wave 4) is a **Zoom-hosted** recording reference
  (`providerRecordingReference`, opaque) surfaced through
  `ClassSessionService.getRecordingPlaybackUrl`, which already calls
  `LiveClassAccessGuard.requireEntitlement` correctly but applies **no**
  view/duration/concurrency policy on top — unlimited reuse of each freshly-issued
  link. That flow is untouched this wave (it is Live-Class-Management's, not
  Content-Management's). Wave 5's `VIDEO`/`RECORDING` **material** types are a
  distinct, self-uploaded video path (Teacher uploads a file — e.g. an exported
  class recording, or original instructional video — as course material), not a
  duplication of the Zoom-recording-join flow. See Section 10 for this distinction
  as an explicit judgment call.

### Confirmed domain ownership (`.claude/rules/architecture.md`)

`video-access-management` is already a **reserved top-level domain** in the
confirmed backend domain list — Wave 5 is the first wave to implement it, not a new
domain invented for this wave. It sits alongside, and is called by,
`content-management` via `api` interfaces only (never a repository/entity import
across the boundary).

### Verdict

1. Fix `MaterialAccessGuard`'s missing Student entitlement check (security fix,
   reuses existing pattern).
2. Add a `MaterialType` discriminator + relax the "always an uploaded file" shape so
   `LINK` (external URL) and `NOTE` (inline text) materials don't require
   `storageObjectKey`, and add an optional Session association alongside the
   existing required-or Lesson association.
3. Build `com.lms.videoaccessmanagement` as a real new domain: `VideoAsset` (the
   uploaded file + processing status), `VideoPlaybackPolicy` (per-asset rules),
   `VideoWatchSession` (a short-lived, single-use, revocable playback grant — the
   PAR-20-01/PAR-17-01 mechanism), `VideoWatchProgress` (cumulative
   views/duration/furthest-position, for max-views and no-seeking-ahead
   enforcement).
4. Wire a real `S3ObjectStorageApi` adapter against the already-provisioned dev
   MinIO so upload/download/signed-URL flows are testable end-to-end in this
   environment, without deciding the production vendor (Section 10).
5. Watermarking is a **client-side overlay**, deterrent only, never claimed as
   prevention — no per-student duplicate video files are ever created.

---

## 2. Target behavior (this wave)

| Parity ID | Target | In scope this wave? |
|---|---|---|
| PAR-06-02 | Materials scoped by **actual** enrollment, not just course-published | **Yes (fix)** |
| PAR-06-03 | Material expiry date, view/download limits, static watermarking | **Yes** |
| PAR-06-04 | Drag-and-drop ordering + keyboard alternative | **Verify only** — up/down-button reordering with `aria-live` announcements already exists and already satisfies the keyboard-alternative bar; no drag-and-drop added (see Section 10) |
| PAR-06-05 / PAR-27-01 | Attach YouTube/Vimeo external video as material (`LINK` type) | **Yes** |
| PAR-17-01 | Concurrent playback session cap via Redis-cached single-use `jti` | **Yes** |
| PAR-20-01 | Signed short-lived (2-5 min) single-use JWT playback token | **Yes** |
| PAR-20-02 | Every playback request re-validated server-side | **Yes** |
| PAR-20-03 | `VideoAsset`/`VideoPlaybackPolicy`/`VideoWatchSession`/`VideoWatchProgress` domain model | **Yes** |
| PAR-20-04 | Client-side dynamic watermark overlay | **Yes** |
| PAR-20-05 | Real external object-storage **production** vendor selection | **No — escalate, see Section 10**; dev/test wired against already-provisioned MinIO |
| PAR-27-03 | Externally-linked (`LINK`) video exempt from secure-video controls | **Decided this wave, see Section 10** |
| PAR-XC-04 | Centralized cross-domain `AccessPolicyService` | **No — deferred to Wave 6, Section 11** |

---

## 3. Database impact (additive Flyway migrations only, next available version: V50)

New domain package: `com.lms.videoaccessmanagement` (`domain`, `repository`,
`service`, `web`, `web/dto`, `api`, `support`), per `.claude/rules/architecture.md`.

1. **`V50__extend_material_types_and_associations.sql`**
   - `material.material_type VARCHAR NOT NULL DEFAULT 'OTHER' CHECK IN ('PDF',
     'IMAGE','DOCUMENT','LINK','NOTE','VIDEO','RECORDING','OTHER')` — additive
     column, existing rows backfilled to `OTHER` by the default (all pre-existing
     materials are uploaded files of unspecified sub-type; this is a safe, honest
     backfill, not a guess at data that doesn't exist).
   - `material.external_url VARCHAR NULL`, `material.note_content TEXT NULL`,
     `material.video_asset_id UUID NULL`.
   - `ALTER TABLE material ALTER COLUMN lesson_id DROP NOT NULL;` then add
     `material.session_id UUID NULL` with composite FK `(tenant_id, session_id)
     REFERENCES class_session (tenant_id, id)`, and
     `CHECK (lesson_id IS NOT NULL OR session_id IS NOT NULL)` — a material attaches
     to exactly a Lesson, a Session, or both; never neither.
   - `fk_material_video_asset`: composite `(tenant_id, video_asset_id) REFERENCES
     video_asset (tenant_id, id)` — added in this same migration but the referenced
     table is created by V51; **ordered as V50 depending on V51 is invalid**, so this
     FK is added in a follow-up statement inside **V51** instead, after
     `video_asset` exists (noted here, implemented there, to keep the dependency
     direction correct — no forward-reference migration).
   - `CHECK` (added in V51 alongside the FK above): `storage_object_key IS NOT NULL`
     required when `material_type IN ('PDF','IMAGE','DOCUMENT','OTHER')`;
     `external_url IS NOT NULL` required when `material_type = 'LINK'`;
     `note_content IS NOT NULL` required when `material_type = 'NOTE'`;
     `video_asset_id IS NOT NULL` required when `material_type IN ('VIDEO',
     'RECORDING')`.
   - `material.max_downloads INTEGER NULL CHECK (max_downloads IS NULL OR
     max_downloads > 0)`, `material.download_count INTEGER NOT NULL DEFAULT 0`,
     `material.available_from_at TIMESTAMPTZ NULL` (existing `expiry_at` becomes the
     availability-window end; this adds the start). `download_count` increments are
     an atomic `UPDATE ... SET download_count = download_count + 1 WHERE ... AND
     (max_downloads IS NULL OR download_count < max_downloads)` guarded update
     (0 rows updated = limit reached), not a read-then-write race.
   - New index `(tenant_id, session_id)`; existing `(tenant_id, lesson_id, sequence)`
     unique constraint untouched.
   - No edit to V1-V49, no destructive backfill.

2. **`V51__create_video_access_management_schema.sql`**
   - `video_asset` — `id, tenant_id NOT NULL REFERENCES tenant(id),
     storage_object_key NOT NULL, original_filename NOT NULL, mime_type NOT NULL,
     size_bytes NOT NULL CHECK > 0, duration_seconds INTEGER NULL (best-effort probe;
     null if unavailable), status VARCHAR NOT NULL CHECK IN ('PENDING','READY',
     'FAILED'), uploaded_by NOT NULL, created_at/updated_at/created_by/updated_by`.
     `uq_video_asset_tenant_id UNIQUE (tenant_id, id)` (composite-FK target,
     matching every other table's convention). Index `(tenant_id, status)`.
   - `video_playback_policy` — `id, tenant_id NOT NULL, video_asset_id NOT NULL,
     access_start_at TIMESTAMPTZ NULL, access_end_at TIMESTAMPTZ NULL,
     max_views_per_student INTEGER NULL CHECK (> 0 when set),
     max_watch_duration_seconds INTEGER NULL CHECK (> 0 when set),
     allow_seeking BOOLEAN NOT NULL DEFAULT true, allow_download BOOLEAN NOT NULL
     DEFAULT false, watermark_enabled BOOLEAN NOT NULL DEFAULT true,
     max_concurrent_sessions INTEGER NOT NULL DEFAULT 1 CHECK > 0,
     created_at/updated_at/created_by/updated_by`. `fk_video_playback_policy_asset`:
     composite `(tenant_id, video_asset_id) REFERENCES video_asset (tenant_id, id)`.
     `uq_video_playback_policy_asset UNIQUE (tenant_id, video_asset_id)` — exactly
     one policy per asset (a missing row means "platform default policy" applied in
     code, not a nullable-everything row).
   - `video_watch_session` — `id, tenant_id NOT NULL, video_asset_id NOT NULL,
     student_id NOT NULL, playback_jti UUID NOT NULL, device_fingerprint_hash
     VARCHAR NULL, issued_at TIMESTAMPTZ NOT NULL, expires_at TIMESTAMPTZ NOT NULL,
     revoked_at TIMESTAMPTZ NULL, revoked_reason VARCHAR NULL CHECK IN ('EXPIRED',
     'SUPERSEDED_BY_NEW_SESSION','ENDED','POLICY_VIOLATION'), status VARCHAR NOT
     NULL CHECK IN ('ACTIVE','ENDED','REVOKED')`. `fk_video_watch_session_asset`,
     `fk_video_watch_session_student` (composite, into `tenant_user`).
     `uq_video_watch_session_jti UNIQUE (playback_jti)` (global — a JWT `jti` is
     meaningless if not globally unique). Index `(tenant_id, video_asset_id,
     student_id, status)` (the concurrency/entitlement lookup shape). A **partial
     unique index** `uq_video_watch_session_single_active ON video_watch_session
     (tenant_id, video_asset_id, student_id) WHERE status = 'ACTIVE'`
     schema-enforces the default (and most security-sensitive) case,
     `max_concurrent_sessions = 1`, at the DB level — mirroring
     `.claude/rules/backend.md`'s "prefer schema-enforced invariants for
     device-authentication" guidance. A tenant/policy configuring
     `max_concurrent_sessions > 1` is enforced by a service-layer transactional
     count-and-lock instead (flagged as a judgment call, Section 10 — the general
     N-cap case cannot be expressed as a single unique index).
   - `video_watch_progress` — `id, tenant_id NOT NULL, video_asset_id NOT NULL,
     student_id NOT NULL, views_count INTEGER NOT NULL DEFAULT 0,
     total_watched_seconds INTEGER NOT NULL DEFAULT 0, furthest_position_seconds
     INTEGER NOT NULL DEFAULT 0, last_watched_at TIMESTAMPTZ NULL,
     created_at/updated_at`. `uq_video_watch_progress UNIQUE (tenant_id,
     video_asset_id, student_id)` — one running-total row per student per video
     (mutable running state, not a financial/audit trail, so update-in-place is
     correct here — this table is explicitly out of the append-only-domains list in
     `.claude/rules/backend.md`).
   - This migration also adds `fk_material_video_asset` and the material-type CHECK
     constraints described under V50 above (added here, after `video_asset` exists,
     to avoid a forward reference).
   - All additive, no edit to V1-V50.

---

## 4. API impact

All new/changed endpoints under `/api/v1`, DTO-only, each documented in `docs/api/`
(Phase F).

**`content-management`** (`/api/v1/courses/{courseId}/modules/{moduleId}/lessons/{lessonId}/materials`,
unchanged base path)
- `MaterialAccessGuard` Student branch: **now requires**
  `coursePublished() && enrollmentAccessApi.resolveAccessState(studentId,
  courseId).state() == ACTIVE`, else `NotFoundException` (unchanged
  anti-enumeration shape, now actually correct).
- `POST .../materials` — request body gains `materialType` (required),
  `externalUrl` (required iff `LINK`), `noteContent` (required iff `NOTE`),
  `videoAssetId` (required iff `VIDEO`/`RECORDING` — references an asset already
  uploaded via the video-access-management upload endpoint below),
  `availableFromAt`/`expiryAt` (both optional), `maxDownloads` (optional),
  `sessionId` (optional, alternative/additional to the existing `lessonId` path
  param — a material can be filed under a class session in addition to its lesson).
  File upload (multipart) stays required only for `PDF`/`IMAGE`/`DOCUMENT`/`OTHER`.
- `GET .../materials/{id}/download-url` — now enforces `availableFromAt`/`expiryAt`
  (403 `MATERIAL_NOT_YET_AVAILABLE` / `MATERIAL_EXPIRED` — not anti-enumeration,
  since the student already legitimately sees the material listed) and the atomic
  `download_count`/`max_downloads` guarded update (403 `DOWNLOAD_LIMIT_REACHED`
  on 0-row update). `LINK`-type materials return the raw `externalUrl` directly
  (no signed URL — there is nothing of ours to sign), consistent with the Section 10
  exemption decision.

**`video-access-management`** (new, `/api/v1/videos`)
- `POST /videos` — Teacher/TA (course ownership, resolved the same way
  `MaterialController` resolves lesson ownership) or staff (`MATERIALS`/
  `CREATE_EDIT` — reusing the existing permission row rather than adding a new
  `DomainArea`, since video management is a sub-case of material management; see
  Section 10). Multipart upload, video-MIME allow-list via `ContentSniffer`
  (extended with a video allow-list: mp4/webm/quicktime), stores via
  `ObjectStorageApi`, creates `VideoAsset` `PENDING` → `READY` (or `FAILED` on a
  storage error, no partial write).
- `PUT /videos/{id}/policy` — same actor gate; upserts `VideoPlaybackPolicy`.
- `POST /videos/{id}/playback-sessions` — **the entitlement-checked
  session-issuance endpoint.** Student: resolves the owning Material →
  Lesson/Session → Course, then `EnrollmentAccessApi.resolveAccessState(...) ==
  ACTIVE` (else `NotFoundException`); Teacher: ownership-only preview, no policy
  limits applied. On success: checks `access_start_at`/`access_end_at`,
  `max_views_per_student` (via `video_watch_progress.views_count`), concurrency cap
  (revokes/supersedes the prior `ACTIVE` session when the cap is 1 — logs
  `SUPERSEDED_BY_NEW_SESSION`); creates a new `VideoWatchSession`, caches its
  `jti`→active-session-id mapping in Redis (bounded TTL, mirrors
  `DeviceSessionCacheService`), and returns a signed, single-use, short-lived
  (3 minute) JWT playback token plus a short-lived signed GET URL from
  `ObjectStorageApi` scoped to that object key and expiring with the token, plus
  `watermarkText` and the policy's `allowSeeking`/`allowDownload` flags for the
  player UI to honor.
- `POST /videos/playback-sessions/{id}/progress` — heartbeat: `{positionSeconds,
  watchedDeltaSeconds}`. Re-validates the token server-side (signature, expiry,
  matching `jti`↔session, session `status == ACTIVE`) on **every** call (PAR-20-02).
  Rejects (`409 SEEK_NOT_ALLOWED`) a forward jump beyond
  `furthest_position_seconds + tolerance` when `allow_seeking = false`. Accumulates
  `total_watched_seconds`; if it would exceed `max_watch_duration_seconds`, revokes
  the session (`POLICY_VIOLATION`) and returns 409. Because the signed storage URL
  and JWT share the same short TTL, a long video forces the client back through
  this endpoint before it can request a fresh signed URL — this is what makes
  "every playback request re-validated server-side" true in practice, not just at
  session start.
- `POST /videos/playback-sessions/{id}/end` — client-driven graceful end
  (`status = ENDED`); idempotent.
- Any IP/device mismatch on a progress call against an `ACTIVE` session (device
  fingerprint hash differs from the one recorded at issuance) revokes the session
  server-side and writes an audit-log entry (`.claude/rules/security.md`'s "IP/
  device anomaly... must trigger session revocation... and an audit/security log
  entry").

**`integration-management`**
- New `S3ObjectStorageApi` (`integrationmanagement.storage`) implementing the
  existing `ObjectStorageApi` port against an S3-compatible endpoint (AWS SDK v2),
  `@ConfigurationProperties(prefix = "object-storage")` for
  endpoint/bucket/credentials (env-var-backed, empty-default fail-closed — falls
  back to `UnavailableObjectStorageApi`'s 503 behavior if unconfigured, mirroring
  `PaymentGatewayProperties`'s pattern exactly). Wired to the dev-compose MinIO via
  `infrastructure/.env.dev`; no code change needed to point at a different
  S3-compatible vendor later (Section 10).

---

## 5. Frontend impact

- **Teacher/TA** (course workspace → Lessons/Sessions → Materials): existing
  materials section gains a type selector (file / link / note / video), conditional
  fields per type, and a policy sub-form for `VIDEO`/`RECORDING` materials (access
  window, max views, max watch duration, allow seeking, allow download, watermark
  toggle, concurrent-session cap). Existing up/down reordering keyboard alternative
  is verified unchanged (PAR-06-04 — no drag-and-drop added this wave).
- **Student** consumption: material list now renders per-type (file download card,
  external-link card opening in a new tab, inline note, and a secure video player
  card). The video player is a new component that: requests a playback session on
  play, renders the signed URL in a `<video>` element with `disablePictureInPicture`
  and no visible download affordance, renders the client-side watermark overlay
  (student name/id/tenant, repositioned every ~20s) when `watermarkEnabled`,
  disables the native seek bar when `allowSeeking = false`, sends periodic
  progress heartbeats, and re-requests a playback session when the signed URL
  expires mid-playback (transparent to the student, backed by the same entitlement
  re-check).
- All new/changed screens get the full loading/empty/error/permission-denied/
  expired/limit-reached state set, matching every prior wave's baseline
  (`QueryStateBoundary`).
- New `frontend/src/lib/api/videos.ts` client (playback-session issuance, heartbeat,
  end), extending the existing `materials.ts` client for the new material-type
  fields — no ad hoc fetches in components.

---

## 6. Security impact

- Closes the pre-existing `MaterialAccessGuard` enrollment gap (Section 1) — the
  single biggest security-relevant fix in this wave.
- Playback tokens are a **distinct JWT type** from the login access token (separate
  claims shape: `sub`=studentId, `video_asset_id`, `watch_session_id`, `jti`), signed
  with the existing `TokenService`/`JwtProperties` signing key infrastructure but
  never accepted by the login-authentication filter chain (checked by a dedicated
  audience/type claim) — a leaked playback token cannot be replayed as a login
  session, and vice versa.
- Single-use/session-bound: `jti` is unique, tied 1:1 to a `VideoWatchSession` row,
  and re-validated (signature, expiry, `status == ACTIVE`) on every progress call —
  a revoked or expired token is rejected even if not yet expired by clock time
  alone.
- Concurrent-session blocking is enforced server-side (schema-enforced for the
  default cap of 1; service-layer transactional count for higher caps), never by
  disabling a button client-side.
- Download/seek/watermark/duration limits are all enforced server-side on the
  progress/session endpoints; the frontend UI hides the corresponding controls, but
  hiding is UX, not the actual control — a direct API call is still rejected by the
  same checks.
- No secret (storage credentials, JWT signing key) is ever sent to the frontend or
  logged.
- Explicit, documented limitation (never claimed otherwise in UI copy or docs):
  short-lived signed URLs and disabled UI affordances are **deterrents**, not
  DRM — a sufficiently motivated user with screen-capture tooling can still
  capture playback. This matches the existing watermark note in
  `docs/parity/klass-parity-matrix.md` and is extended to download-prevention
  language too.

---

## 7. Tenant isolation impact

- `video_asset`, `video_playback_policy`, `video_watch_session`,
  `video_watch_progress` all get `tenant_id NOT NULL` + a `tenant_id`-leading index,
  and all repositories extend `TenantAwareRepository` (no hand-rolled tenant
  filtering).
- Every composite FK is `(tenant_id, x_id) → parent(tenant_id, id)` — never a bare
  child-id FK — matching V49's convention exactly.
- `playback_jti` is globally unique (JWT identity), but every lookup **from** a
  token back to a session still re-checks `tenant_id` against the token's own
  `tenant_id` claim before trusting the row, so a token cannot be replayed across
  tenants even if a `jti` were somehow guessed.
- Cross-tenant negative tests required (Section 8): Student from tenant A requesting
  a playback session for a tenant B video asset (by id) → `404`, not `403`
  (anti-enumeration, consistent with `MaterialAccessGuard`).

---

## 8. Test plan

Backend (JUnit + Testcontainers):
- `MaterialAccessGuardTest` — new case: unenrolled Student in a published course is
  denied (`404`); previously-passing "published course" case is preserved for an
  actually-enrolled Student.
- Material type validation: `LINK` without `externalUrl` rejected; `NOTE` without
  `noteContent` rejected; `VIDEO`/`RECORDING` without `videoAssetId` rejected;
  upload-required types without a file rejected.
- Download-limit: N downloads succeed, N+1th returns `DOWNLOAD_LIMIT_REACHED`,
  concurrent-download race test (parallel requests against a `maxDownloads=1`
  material — exactly one succeeds).
- Availability window: before `availableFromAt` → blocked; after `expiryAt` →
  blocked.
- `VideoAccessGuard`/playback-session issuance: enrolled Student succeeds;
  unenrolled/expired-enrollment Student → `404`; cross-tenant Student (by video
  asset id) → `404`; Teacher-not-owner → `404`.
- Concurrency cap: second playback-session request for the same (video, student)
  while cap=1 supersedes the first (first session becomes `REVOKED`/
  `SUPERSEDED_BY_NEW_SESSION`, is then rejected on its next progress call).
- Max views: `views_count` reaches `max_views_per_student` → further session
  requests rejected.
- Max watch duration: progress calls accumulating past the cap → session revoked,
  further progress calls rejected.
- Seek restriction: `allow_seeking = false`, a progress call reporting
  `positionSeconds` far beyond `furthest_position_seconds` → rejected.
- Token validity: expired token rejected; token for a different `video_asset_id`
  rejected; token whose session is `ENDED`/`REVOKED` rejected.
- Direct-API-bypass tests: hitting the progress/end endpoints with a syntactically
  valid but never-issued-by-us JWT (wrong signing key) → rejected; hitting them
  with another student's valid token for a video that student is not entitled to
  → rejected (re-checks entitlement, not just token validity).
- `S3ObjectStorageApi` integration test against a Testcontainers MinIO (or
  Testcontainers' generic S3-compatible image) — store/signed-URL/delete round
  trip.

Frontend (Playwright):
- Teacher: create each material type, edit a video policy, verify validation
  errors per type.
- Student: view each material type's card; play a video end-to-end against a test
  asset (mocked/fixture playback session), verify watermark overlay renders,
  verify seek bar is disabled when policy says so, verify a "not entitled"/expired
  material renders the correct empty/error state, verify download button
  disappears once the limit is reached.

---

## 9. Migration strategy / rollout risk

- Both migrations are purely additive; `material_type` backfills existing rows to
  `OTHER` (honest default, not a guess). No existing query changes shape except the
  now-tightened `MaterialAccessGuard` check, which is a strict narrowing (fewer
  Students can see materials than before) — the only rollout risk is a currently-
  unenrolled-but-viewing Student losing access they should never have had; this is
  the intended fix, not a regression to soften.
- `S3ObjectStorageApi` ships alongside `UnavailableObjectStorageApi`; the active
  bean is selected by whether `object-storage.*` env vars are set (fail-closed to
  `Unavailable` otherwise) — no environment is forced onto the new adapter before
  it's configured.

---

## 10. Explicit judgment calls (flagged for product-owner awareness, not silently decided)

1. **PAR-20-05 (production object-storage vendor)** — genuinely an open business/
   procurement decision (S3 vs. GCS vs. Azure Blob vs. a different S3-compatible
   vendor), not something engineering should default silently. This wave unblocks
   itself by wiring the adapter against the **already-provisioned dev MinIO**
   (S3-compatible), which exercises the real code path end-to-end without
   prejudging the production vendor — the adapter is vendor-agnostic (any
   S3-compatible endpoint) by construction. Escalate the production vendor choice
   separately.
2. **PAR-27-03 (is `LINK`-type video exempt from secure-video controls?)** —
   decided **yes, exempt**, for this wave: we have no server-side control over a
   third-party player (YouTube/Vimeo), so no `VideoAsset`/policy/watch-session is
   created for `LINK` materials; the entitlement check (enrollment) still applies at
   the material level (a `LINK` material is still gated by
   `MaterialAccessGuard`), but no playback token, watermark, or view/duration limit
   exists once the student is handed the external URL. This is the only technically
   coherent answer, but it is a product-facing claim ("secure video" doesn't apply
   to embedded third-party content) worth the product owner explicitly signing off
   on rather than discovering later.
3. **`VIDEO` vs. `RECORDING` material type** — both use the identical
   `VideoAsset`/`VideoPlaybackPolicy` mechanism; the distinction is purely semantic
   labeling (original instructional video vs. a recorded class session made
   available afterward as a material), not a different security posture. This is
   separate from, and does not touch, Wave 4's Zoom-hosted
   `ClassSessionRecording`/join-recording flow.
4. **No new `DomainArea` for video** — video upload/policy management reuses the
   existing `MATERIALS` permission row rather than adding a `VIDEO_CONTENT` row,
   since video is modeled as a material sub-type, not a separate manageable
   resource with its own staff permission scoping needs.
5. **Concurrency cap > 1 is service-enforced, not schema-enforced** — the schema
   hard-enforces the default/security-critical cap of exactly 1 active session via
   a partial unique index; a tenant configuring a higher cap relies on a
   transactional count-and-lock in the service layer (Section 3). Flagged so a
   reviewer doesn't assume the schema alone prevents over-cap concurrency in the
   N>1 configuration.
6. **PAR-06-04 (drag-and-drop)** — verified, not built: the existing up/down-button
   reordering with `aria-live` announcements is judged to already satisfy "a
   working keyboard alternative" without adding true drag-and-drop, which would be
   net-new UI work the parity item's actual risk (accessibility) doesn't require.

---

## 11. Deferred (explicitly out of scope, not silently skipped)

- **PAR-XC-04** (centralized cross-domain `AccessPolicyService` evaluating course/
  billing/material/video access in one place) — the parity matrix itself
  recommends designing this *before* more per-domain expiry logic accumulates.
  This wave adds a second per-domain entitlement guard (`VideoAccessGuard`,
  alongside `MaterialAccessGuard`/`LiveClassAccessGuard`) rather than building the
  centralized service now, matching the matrix's own note that cross-type
  unification targets **Wave 6** (`PAR-09-04`/`PAR-18-02`). Flagged clearly so Wave
  6 planning starts from "three near-identical guards exist, ready to be
  unified," not from a surprise.
- **Device-limit override-precedence engine** (student/course/tenant/plan) — a
  larger, separately-scoped module already deferred elsewhere in this codebase
  (`DeviceFingerprint`'s own code comment); Wave 5's concurrency cap is scoped to
  per-video watch sessions only, not general login-device limiting.
- **Real transcoding/adaptive-bitrate pipeline** — `duration_seconds` is a
  best-effort probe at upload time; no transcoding, thumbnailing, or multi-rendition
  pipeline is built this wave (not requested by the wave brief).
- **True drag-and-drop reordering** — see judgment call 6 above.

---

## 12. Wave 5 Completion Report

### Backend (Phase B)

Delivered in four sequential passes (split after the combined-scope task twice failed to make
progress — once on a mid-session rate limit, once on a stream stall — both before any code was
written; splitting reduced blast radius and made partial progress independently verifiable):

1. **`MaterialAccessGuard` enrollment fix** — applied directly (not delegated) after a partial
   agent run left the guard's javadoc claiming the fix was done while the actual Student branch
   still only checked `coursePublished()`. Fixed to call `EnrollmentAccessApi.resolveAccessState`
   and require `ACTIVE`, with a corrected/extended unit test proving both the enrolled-allowed and
   unenrolled-denied cases.
2. **`content-management` material-type extension** — `MaterialType` enum, per-type validation,
   availability-window + atomic download-count-limit enforcement, DTOs, controller. Found and fixed
   two real bugs along the way (a missing `@Transactional` on the atomic download-count update, and
   two `NOT NULL` columns V51 had missed relaxing for `LINK`/`NOTE`/`VIDEO` materials).
3. **New `video-access-management` domain** — `VideoAsset`/`VideoPlaybackPolicy`/
   `VideoWatchSession`/`VideoWatchProgress`, `VideoAccessGuard`, `PlaybackTokenService` (a
   structurally separate JWT type from the login token), `VideoPlaybackSessionService`
   (concurrency-cap enforcement, seek/duration/view-limit policy, device-fingerprint-mismatch
   revocation with an audit event). Found and fixed one real bug (a revoke-then-throw transaction
   rollback that was silently undoing the very audit write it was supposed to produce — fixed with
   a `REQUIRES_NEW`-propagation recorder bean).
4. **`S3ObjectStorageApi` adapter** — wired against the already-provisioned dev MinIO, with a
   hand-written `Condition`-based fail-closed bean selection (no existing
   `@ConditionalOnProperty`-style precedent in this codebase to reuse).

Database: two additive migrations (`V50`, `V51`), Flyway-validated against a fresh Postgres before
any application code was written.

### Frontend (Phase C)

One pass: material-type-aware create/edit UI (file/link/note/video, with a nested
instructional-video/recorded-session sub-choice for `VIDEO`/`RECORDING`), a Teacher
"Playback rules" policy sub-form, per-type Student consumption rendering, and a new secure video
player component (session issuance, watermark overlay, custom seek-suppressed controls when
`allowSeeking=false`, progress heartbeat, transparent signed-URL refresh with a bounded
consecutive-error guard, terminal-state handling on `POLICY_VIOLATION`). Zero backend changes were
needed — the contract handed to the frontend implementation matched the real backend exactly.

### Security & tenant-isolation review (Phase D)

Two independent review passes were run in parallel, plus a follow-up fix pass:

- **Tenant-isolation review**: all 7 checklist items PASS (tenant_id present on every new table,
  structural `TenantAwareRepository` filtering, composite FKs, the global `playback_jti` index's
  cross-tenant re-check, anti-enumeration on cross-tenant lookups, genuine two-tenant negative
  tests, no client-side tenant-scoping logic).
- **Security review**: PASS on JWT isolation (playback tokens structurally cannot be replayed as
  login tokens), server-side re-validation on every playback request, concurrency-cap enforcement,
  no leaked secrets. Found one **HIGH** and two **MEDIUM/LOW** issues.

**Fixes applied and verified** (all three, plus a new regression test for each):

1. **HIGH — fixed.** `VideoAssetService.upsertPolicy` had no ownership check beyond tenant
   membership: any Teacher/TA in a tenant could rewrite any other teacher's video's protection
   policy (stripping watermark/download/view-limit controls). Fixed by resolving real course
   ownership via `MaterialLookupApi` (the same pattern `VideoAccessGuard` already used elsewhere)
   before allowing the mutation, falling back to the pre-existing loose gate only in the narrow
   pre-material-attach window where no owner is yet resolvable. New Testcontainers regression test
   proves the non-owning teacher gets 403 and the owning teacher still succeeds.
2. **LOW — fixed.** Removed `VideoWatchSessionRepository#findByPlaybackJtiAcrossTenants`, an
   unused, never-called global lookup flagged as a latent footgun (a future caller could have
   wired it up trusting its javadoc's safety contract without actually performing the documented
   tenant cross-check).
3. **MEDIUM/LOW — fixed.** The object-storage real-vs-fail-closed bean selection previously
   checked only `bucket` being non-blank; a partially-configured environment (bucket set, keys
   blank) would select the real `S3ObjectStorageApi` bean instead of staying on the fail-closed
   `UnavailableObjectStorageApi` default (mitigated at runtime by every real call then failing, but
   the *boundary itself* was wrong). Fixed to require `bucket` AND `access-key` AND `secret-key` all
   non-blank. New test proves the previously-untested "bucket alone" case now correctly stays on
   the fail-closed adapter.

**Findings deliberately not fixed, and why:**
- *Presigned playback URL is scoped to the video only, not to the requesting user/session* (a
  URL, once issued, is redeemable by anyone who obtains it for its ~3-minute TTL). This is a
  systemic tradeoff of using raw S3 presigned URLs, shared with Wave 4's Zoom-recording-link
  pattern, not something Wave 5 introduced — addressing it properly would mean a streaming-proxy/
  token-gated-redemption redesign, out of scope for this wave. Documented as a residual,
  short-TTL-mitigated risk rather than silently assumed to fully satisfy the "scoped to a single
  user/session" bar.
- *`recordProgress` doesn't re-check live enrollment state on every heartbeat, only token/session
  integrity.* Entitlement is re-checked at session issuance (every ~3 minutes, matching the token
  TTL); a mid-session revocation has a narrow window before the next re-issuance would catch it.
  Judged acceptable given the short TTL; flagged for awareness rather than fixed, since closing it
  fully would mean an `EnrollmentAccessApi` call on every heartbeat (a much higher-frequency call
  than this wave's design intended).
- *Device-fingerprint entropy depends on correct reverse-proxy forwarded-header configuration* —
  a pre-existing characteristic of the login flow's `DeviceFingerprint` utility that this wave's
  `RequestDeviceFingerprint` intentionally mirrors rather than fixes; not a Wave-5 regression, just
  newly load-bearing for a security control introduced this wave. Worth an explicit Nginx/Spring
  `forward-headers-strategy` check outside this wave's scope.

### Documentation (Phase E/F)

New `docs/api/video-access-management.md`; `docs/api/content-management.md` extended
(authorization-model correction, per-type create fields, new error codes); `docs/api/README.md`
index updated; `docs/parity/klass-parity-matrix.md` rows updated per §2's target table above (the
stale PAR-06-02 "MATCHES/scoped by enrollment" claim is now corrected to describe the actual fix,
not just re-asserted); `docs/parity/implementation-roadmap.md`'s Wave 5 row and two related
narrative items (PAR-20-05, PAR-27-03) updated.

### Final verification (Phase G)

- Backend: `mvnw.cmd verify` — **1845 tests, 0 failures, 0 errors, 0 skipped.**
- Frontend: `npx tsc --noEmit` clean; `npm run lint` 0 errors; `npm run build` succeeds (54
  routes); `npx playwright test` — **652/654 passed** (1 pre-existing unrelated flake in a file
  never touched this wave, 1 pre-existing unrelated cross-tenant real-backend skip).

### Judgment calls made during implementation (in addition to the ones already recorded in §10)

- **`ContentSniffer`/`DeviceFingerprint` are duplicated, not reused**, inside
  `video-access-management` (`VideoContentSniffer`, `RequestDeviceFingerprint`) — the originals
  live in non-`api` packages of other domains, and importing them would violate the
  "depend only on another module's `api` package" architecture rule. Small, deliberate
  duplication over a boundary violation.
- **Teacher preview sessions never create a `VideoWatchSession` row or playback token** — just a
  signed URL, no policy enforcement, since ownership is already verified by `VideoAccessGuard`
  before that branch is reached.
- **Watermark text is `"{studentId} · {tenantId}"`**, not a resolved display name — resolving a
  human-readable name would require crossing into another domain's user-profile data from a
  security-token-issuing code path, judged not worth the boundary cost for this wave.
- **File-type UI collapsing**: the frontend collapsed PDF/IMAGE/DOCUMENT/OTHER into one "Upload a
  file" choice that always sends `materialType=OTHER`, since the backend's `ContentSniffer`
  determines the real type from file bytes regardless of what the client claims.

Wave 5 is complete. No item remains silently unresolved: every deferred or partially-addressed
item above is explicitly recorded here and in §10/§11, not omitted.
