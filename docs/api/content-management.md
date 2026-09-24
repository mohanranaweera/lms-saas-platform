# content-management — API Contract

Covers Lessons and Learning Materials (MVP-009 / `com.lms.contentmanagement`). Derived
directly from the shipped backend implementation (`MaterialController`, its DTOs, and
`GlobalExceptionHandler`), not from the pre-implementation draft in
`docs/plans/MVP-009 Lessons and Learning Materials.md` §10 — that draft is superseded by
this file. Written retroactively, after a full module review found this contract file had
never been produced despite the plan requiring it before frontend work began (§19/§20 step
5); see the "Process gap" note at the bottom. This follows the exact same retroactive
pattern already used for `docs/api/course-management.md`.

**Extended in Wave 5** (`docs/parity/waves/wave-05-plan.md` §3/§4, PAR-06-02/03/05,
PAR-27-01) with the `materialType` discriminator (`LINK`/`NOTE`/`VIDEO`/`RECORDING` in
addition to the pre-existing uploaded-file types), an optional Session association, an
availability window + download-limit, and — the single biggest security-relevant change
in that wave — an actual enrollment check on the Student read path (see "Authorization
model" below). See `docs/api/video-access-management.md` for the separate, new
`/api/v1/videos` contract that `VIDEO`/`RECORDING` materials reference by
`videoAssetId`.

## Response envelope

Every endpoint below returns `com.lms.common.api.ApiResponse<T>` — see
`docs/api/identity-access-service.md`'s "Response envelope" section for the exact shape
(`success`/`data`/`error`/`timestamp`/`traceId`); it is identical here, not repeated.

## Auth requirements

Every endpoint below requires a valid `Authorization: Bearer <accessToken>` header.
`@PreAuthorize("isAuthenticated()")` is a coarse gate only — the real, combined
staff-matrix-or-Teacher-ownership-or-interim-Student authorization check happens in
`MaterialAccessGuard.requireLessonAccess(...)`, called independently on every method
(including reads), never cached or inherited across requests within the same handler.

## Authorization model

`MaterialAccessGuard.requireLessonAccess(courseId, moduleId, lessonId, action)` runs this
precedence on every call:

1. Resolve lesson ownership via `coursemanagement.api.CourseLookupApi.resolveLessonOwnership(lessonId)`,
   scoped to the caller's own resolved tenant context. `Optional.empty()`, or a path-segment
   (`moduleId`/`courseId`) mismatch against the resolved result, → `404 NOT_FOUND` ("Lesson
   not found") — never a distinguishing `400`/`403` that would leak which segment was wrong.
2. **Teacher / Teacher Assistant** callers: allowed only if `ownership.teacherId()` equals
   their own user id. **Teacher Assistant is currently non-functional in production** — no
   TA-to-course assignment data exists anywhere in this codebase, so this branch can only
   succeed if the caller's own id happens to equal the course's sole `teacherId`, which never
   happens for a genuine distinct TA. Denial is `403 FORBIDDEN`.
3. **Student** callers: allowed only for `action == VIEW` **and** `ownership.coursePublished()`
   **and** [Wave 5 fix — see below] `EnrollmentAccessApi.resolveAccessState(studentId,
   courseId).state() == ACTIVE`. Any other case (a mutating action, an unpublished
   course, or a not-currently-enrolled Student) → `404 NOT_FOUND` ("Lesson not found") —
   a Student never receives a `403` from this guard; every denial reason (cross-tenant,
   wrong course, unpublished course, not enrolled/expired enrollment) is
   anti-enumeration-collapsed into the identical generic 404. A **`HIDDEN`** material
   additionally 404s for a Student at the single-fetch/download-url layer
   (`MaterialService#loadMaterial`), same generic message, same code — not
   distinguishable from "doesn't exist."

   **Wave 5 security fix (corrects a stale claim in `docs/parity/klass-parity-matrix.md`
   PAR-06-02):** before this wave, this branch checked only
   `ownership.coursePublished()` — **any** authenticated Student in the tenant could
   list/view/download materials of any published course, enrolled or not, given a
   courseId/moduleId/lessonId. The parity matrix's Wave-0-era PAR-06-02 row had
   (incorrectly) recorded this as "scoped by enrollment / MATCHES," which did not match
   the actual code. This wave adds the `EnrollmentAccessApi` call above, closing the
   gap — it is a security fix reusing the exact pattern already established by
   `liveclassmanagement.support.LiveClassAccessGuard#requireEntitlement`, not a new
   enrollment-activation mechanism (root `CLAUDE.md`'s change-controlled "enrollment
   activation rules" is untouched).
4. Otherwise: `permissionCheckService.requirePermission(DomainArea.MATERIALS, action)` — the
   existing RBAC-2 matrix (`VIEW`/`CREATE_EDIT`/`DELETE`). Tenant Admin and Content Manager
   hold full `V/C/E/D`; Course Coordinator and Read-only Auditor hold `V` only (mutation
   attempts → `403`); Finance Staff, Student Support, Exam Manager, Attendance Operator hold
   no grant at all (any call → `403`).

`GET .../materials` additionally, server-side, filters the returned list to
`visibility == VISIBLE` only when the caller is a Student — never left to the client to
filter.

## Endpoints

Base path: `/api/v1/courses/{courseId}/modules/{moduleId}/lessons/{lessonId}/materials`

### `GET .../materials`

List a lesson's materials, ordered by `sequence` ascending. **Success — `200`**
(`ApiResponse<MaterialResponse[]>`). Student callers see `VISIBLE`-only materials; staff/
owning-Teacher callers see all (including `HIDDEN`).

### `GET .../materials/{materialId}`

Single material metadata — **never** a raw storage URL or byte stream in this response.
Independently re-runs the full authorization + visibility check (never inherited from a
prior list call). **Success — `200`** (`ApiResponse<MaterialResponse>`).

### `GET .../materials/{materialId}/download-url`

Returns a short-lived signed URL (5-minute TTL, `MaterialService.DOWNLOAD_URL_TTL`) minted
fresh on every call via `ObjectStorageApi.generateSignedDownloadUrl` — never cached, never a
stable/predictable URL. Same authorization + visibility re-check as the single-fetch
endpoint. **Success — `200`**:

```jsonc
{ "url": "https://...", "expiresAt": "2026-08-17T00:05:00Z" }
```

**Wave 5 (PAR-06-03) — type-dependent behavior, enforced in this order:**

1. `NOTE`, `VIDEO`, and `RECORDING` materials have no download action at all — `NOTE`'s
   content is `noteContent`, already returned inline by `GET .../materials`/`GET
   .../materials/{materialId}`; `VIDEO`/`RECORDING` materials are served exclusively
   through `video-access-management`'s own playback-session endpoint (see
   `docs/api/video-access-management.md`), never this generic signed-download-URL path.
   Both throw **`404 NOT_FOUND`** ("Material not found") here — modeled the same way this
   guard already models "hidden material for a Student," a plain 404 for an action that
   structurally cannot apply to this resource type.
2. Availability-window check: **`403 MATERIAL_NOT_YET_AVAILABLE`** if `now` is before
   `availableFromAt`; **`403 MATERIAL_EXPIRED`** if `now` is after `expiryAt`. Deliberately
   **not** the anti-enumeration 404 shape — the caller already legitimately sees this
   material listed (it passed the guard/visibility check), so a 403 with a
   machine-readable code is more useful to the client than a 404 here.
3. `LINK` materials return the raw `externalUrl` directly in the `url` field (`expiresAt`
   is `null` — there is nothing of ours to sign or expire), consistent with this wave's
   PAR-27-03 judgment call that externally-linked video/content is exempt from secure-video
   controls (see `docs/api/video-access-management.md`'s "Explicit judgment calls"
   section) — no `ObjectStorageApi` call is made for a `LINK` material.
4. For every other type (`PDF`/`IMAGE`/`DOCUMENT`/`OTHER`): an atomic guarded
   `download_count` increment (`MaterialRepository#incrementDownloadCountIfUnderLimit`)
   runs before the signed URL is minted — **`403 DOWNLOAD_LIMIT_REACHED`** if the update
   affects zero rows (i.e. `maxDownloads` has already been reached). This is a
   guarded-`UPDATE`-affecting-zero-rows check, not a read-then-write race — concurrent
   requests against a `maxDownloads`-limited material can never both succeed past the
   limit.

**No longer always `503` as of Wave 5** for the file-backed types above: a real
`S3ObjectStorageApi` (`integrationmanagement.storage`, AWS SDK v2 against any
S3-compatible endpoint) is now wired in dev/test against the already-provisioned dev
MinIO (`infrastructure/docker-compose.dev.yml` / `infrastructure/.env.dev`). The bean
selection is fail-closed: `S3ObjectStorageApi` is active only when `object-storage.bucket`
is configured; `UnavailableObjectStorageApi` (still `503 SERVICE_UNAVAILABLE`) remains the
default in any environment where it isn't — see `docs/api/video-access-management.md`'s
note and `docs/parity/waves/wave-05-plan.md` §10 item 1 for why the **production** vendor
choice remains open (a separate, escalated business/procurement decision, not resolved by
this wire-up). Any SDK-level failure against a configured real endpoint (network,
throttling, credentials, missing bucket) is translated to `503 SERVICE_UNAVAILABLE`
without leaking the underlying SDK exception detail to the client.

### `POST .../materials`

Create a material. **Multipart** (`multipart/form-data`), not JSON — **extended in Wave 5**
with a `materialType` discriminator and its per-type required fields (plan §3/§4):

| Part | Type | Notes |
|---|---|---|
| `title` | text | Required, max 255 chars. |
| `materialType` | text | Optional; one of `PDF`/`IMAGE`/`DOCUMENT`/`LINK`/`NOTE`/`VIDEO`/`RECORDING`/`OTHER`. `null`/omitted defaults to `OTHER` (mirrors V50's own honest backfill default for pre-Wave-5 rows) — every pre-Wave-5 caller that only ever supplied `title`+`file` keeps working unchanged. |
| `file` | file | Required **only** for an uploaded-file type (`PDF`/`IMAGE`/`DOCUMENT`/`OTHER`); omitted for `LINK`/`NOTE`/`VIDEO`/`RECORDING`. When present, validated in order: size (`app.content.material.max-file-size-bytes`, currently `26214400` = 25 MiB) then magic-byte content-sniffing (`ContentSniffer`) against an allow-list of `application/pdf`, `image/png`, `image/jpeg`, `image/gif`, `text/plain` — never the client-declared `Content-Type` or file extension. |
| `externalUrl` | text | **Required iff `materialType == LINK`** (PAR-06-05/PAR-27-01, e.g. a YouTube/Vimeo URL) — must be an absolute URL with a host (shape validation only, never dereferenced/fetched server-side). |
| `noteContent` | text | **Required iff `materialType == NOTE`.** |
| `videoAssetId` | UUID | **Required iff `materialType` is `VIDEO`/`RECORDING`.** Must reference a video asset already uploaded via `POST /api/v1/videos` (see `docs/api/video-access-management.md`) that is `READY` **and** owned by the caller's own tenant — verified via `VideoAccessApi#isVideoAssetReadyAndOwnedByTenant`, the only cross-module dependency this domain has on `video-access-management`. A cross-tenant or not-yet-ready `videoAssetId` is rejected as `400 VALIDATION_ERROR` on the `videoAssetId` field, never silently accepted. |
| `sessionId` | UUID | Optional — an alternative/additional association to the existing `lessonId` path param, letting a material be filed under a `live-class-management` class session in addition to (or instead of) its lesson. A material must have at least one of `lessonId`/`sessionId`; the path's `lessonId` remains required by the URL shape itself. |
| `maxDownloads` | integer | Optional, positive. Enforced only for file-backed types (`LINK`/`NOTE`/`VIDEO`/`RECORDING` have no download action — see the `download-url` endpoint below). |
| `availableFromAt` | text | Optional, raw ISO-8601 instant string (e.g. `2024-01-01T00:00:00Z`); a malformed value is rejected as a clean `400 VALIDATION_ERROR`, never an unhandled parse exception. |
| `expiryAt` | text | Optional, same ISO-8601 parsing rule as `availableFromAt`. |

`sequence` is **not** a request field — the server always appends
(`max(existing sequence in lesson) + 1`). **Success — `201`** (`ApiResponse<MaterialResponse>`).
**`400 VALIDATION_ERROR`** if a type's required field is missing/invalid (e.g. `LINK`
without `externalUrl`, `NOTE` without `noteContent`, `VIDEO`/`RECORDING` without a
valid `videoAssetId`, or an upload-required type without a `file`) — a clean 400 raised by
`MaterialService#validateFieldsForType` in front of V51's own `ck_material_*_required`
CHECK constraints, which remain the real backstop. **`413 PAYLOAD_TOO_LARGE`** on oversize
(also enforced at the container level via `spring.servlet.multipart.max-file-size: 25MB`,
independently of the service-layer constant; file-backed types only). **`415
UNSUPPORTED_MEDIA_TYPE`** on a failed content-sniff (file-backed types only). On any
validation failure, **zero** storage calls are made and **zero** rows are persisted
(ordering: authorization → per-type field validation → [file-backed types only] size →
content-sniff → only then `ObjectStorageApi.store(...)` → only then the `material` row is
persisted, in a separate step, per `.claude/rules/backend.md`'s "never span a transaction
across an outbound call" rule). See the `download-url` endpoint below for whether
`503 SERVICE_UNAVAILABLE` still applies to file-backed uploads post-Wave-5.

### `PATCH .../materials/{materialId}`

**Full-resource replace, not a partial patch or a dedicated reorder endpoint** — this is the
one deliberate divergence from the plan's own §10 draft (which proposed a separate
`PATCH .../materials/reorder` bulk endpoint). The shipped design instead reuses the exact
pattern already established for `course_module`/`course_lesson` reorder
(`docs/api/course-management.md`): a single per-item `PATCH` requiring `title`, `sequence`,
and `visibility` together on every call, all three `@NotNull`/required
(`MaterialUpdateRequest`):

```jsonc
{
  "title": "Lecture 1 slides",   // required, max 255
  "sequence": 3,                 // required, positive int
  "visibility": "VISIBLE"        // required, one of VISIBLE | HIDDEN
}
```

Renaming must resend the material's current `sequence`/`visibility` unchanged; toggling
visibility must resend the current `title`/`sequence`; reordering (move up/down) is done via
three sequential `PATCH` calls per swap — park the moving item on a temporary out-of-range
sequence, move the displaced neighbor into the mover's old sequence, then move the mover into
the neighbor's old sequence (identical "safe swap" dance to `frontend/src/lib/courses/reorder.ts`,
implemented for materials in `frontend/src/lib/courses/material-reorder.ts`) — this avoids a
409 against the `uq_material_sequence UNIQUE (tenant_id, lesson_id, sequence)` constraint
that a naive direct two-call swap would hit. **Success — `200`** (`ApiResponse<MaterialResponse>`).
**`409 CONFLICT`** if the target `sequence` is already held by a different material in the
same lesson (`existsByLessonIdAndSequenceAndIdNot`). Upload-time-only fields (`file`,
`mimeType`, `storageObjectKey`, `originalFilename`, `sizeBytes`) are immutable after
creation — not accepted by this DTO at all.

### `DELETE .../materials/{materialId}`

Deletes the row and publishes `MaterialDeletedEvent` synchronously in the same
`@Transactional` boundary (actor, tenant, target material id, lesson/module/course id,
timestamp, and a before-state snapshot — title/mime-type/uploaded-by/storage-key). A
`@TransactionalEventListener(phase = AFTER_COMMIT)` then calls `ObjectStorageApi.delete(...)`
after commit (logged, not propagated, if it fails — the DB deletion has already succeeded by
that point). **Success — `200`** (`ApiResponse<null>`). **`409 CONFLICT`** is *not* possible
here directly, but note: deleting a **lesson or module** that still has an attached material
fails with `409` instead, via `fk_material_lesson`'s composite FK (no `ON DELETE CASCADE`, by
design — see "Cross-module interaction" below); the material itself is never force-cascaded
out from under an intact lesson.

## Response shape

**`MaterialResponse`** — extended in Wave 5 with `sessionId`, `materialType`,
`externalUrl`, `noteContent`, `videoAssetId`, `maxDownloads`, `downloadCount`, and
`availableFromAt` (the pre-Wave-5 `expiryAt` field is now genuinely enforced — see the
`download-url` endpoint above — rather than the forward-compat/unenforced placeholder it
was before this wave):

```jsonc
{
  "id": "<uuid>", "lessonId": "<uuid>", "sessionId": null, "materialType": "PDF",
  "title": "...", "originalFilename": "slides.pdf", "mimeType": "application/pdf",
  "sizeBytes": 204800, "externalUrl": null, "noteContent": null, "videoAssetId": null,
  "sequence": 1,
  "visibility": "VISIBLE",              // VISIBLE | HIDDEN
  "maxDownloads": null, "downloadCount": 0,
  "availableFromAt": null, "expiryAt": null,   // now enforced server-side on download-url, not merely forward-compat
  "uploadedBy": "<uuid>", "createdAt": "2026-08-17T00:00:00Z", "updatedAt": "2026-08-17T00:00:00Z"
}
```

`downloadCount` is response-only — server-maintained exclusively via
`MaterialRepository#incrementDownloadCountIfUnderLimit`, never client-settable
(`MaterialUpdateRequest` does not accept it, nor any of the new per-type/limit fields —
those stay create-time-only/immutable for this wave, mirroring the existing
upload-fields-are-immutable convention below).

## Error codes

Standard `ApiErrorCodes` (see `docs/api/identity-access-service.md`'s error-codes table for
the full platform list) apply. Content-management-specific cases:

| Case | Code | Status |
|---|---|---|
| Cross-tenant / wrong-course / unpublished-course / **not-enrolled-or-expired-enrollment (Wave 5)** / hidden-material lesson or material id, for a Student | `NOT_FOUND` | `404` (uniform message, anti-enumeration — never distinguishable from "doesn't exist") |
| Cross-tenant / not-owned-by-Teacher lesson id, for staff/Teacher | `NOT_FOUND` | `404` |
| Staff without `MATERIALS` grant, or Teacher/TA not owning the lesson's course | `FORBIDDEN` | `403` |
| Sequence collision on `PATCH` | `CONFLICT` | `409` |
| Deleting a lesson/module with an attached material | `CONFLICT` | `409` (via DB FK violation, `GlobalExceptionHandler.handleDataIntegrityViolation` — generic message, does not name the cause) |
| Missing/invalid per-`materialType` field on create (Wave 5) | `VALIDATION_ERROR` | `400` |
| Invalid/not-`READY`/cross-tenant `videoAssetId` on create (Wave 5) | `VALIDATION_ERROR` | `400` |
| Malformed `availableFromAt`/`expiryAt` timestamp (Wave 5) | `VALIDATION_ERROR` | `400` |
| Oversized upload | `PAYLOAD_TOO_LARGE` | `413` |
| Failed content-sniff | `UNSUPPORTED_MEDIA_TYPE` | `415` |
| `available_from_at` in the future for the caller, on download-url (Wave 5, PAR-06-03) | `MATERIAL_NOT_YET_AVAILABLE` | `403` |
| `expiry_at` has passed for the caller, on download-url (Wave 5, PAR-06-03) | `MATERIAL_EXPIRED` | `403` |
| `max_downloads` already reached, on download-url (Wave 5, PAR-06-03) | `DOWNLOAD_LIMIT_REACHED` | `403` |
| download-url requested for a `NOTE`/`VIDEO`/`RECORDING` material (Wave 5 — action doesn't apply to this type) | `NOT_FOUND` | `404` |
| Object storage not configured (no `object-storage.bucket` set — `UnavailableObjectStorageApi` fail-closed default; see the download-url endpoint above for the Wave 5 `S3ObjectStorageApi` wiring) | `SERVICE_UNAVAILABLE` | `503` |
| Bean Validation failure | `VALIDATION_ERROR` | `400` |

## Cross-module interaction: material vs. course/module/lesson deletion

`material.lesson_id` carries a composite FK, `(tenant_id, lesson_id) REFERENCES
course_lesson (tenant_id, id)`, **without** `ON DELETE CASCADE` — unlike
`course_module`/`course_lesson`'s own FKs back to their parents. This is intentional: an
implicit DB-level cascade from a lesson/module/course delete would silently bypass
`MaterialDeletedEvent`'s audit-logging requirement and orphan the corresponding
object-storage entry. The consequence: `course-management`'s `DELETE
/api/v1/courses/{id}`, and its lesson/module delete endpoints, now fail with `409` whenever
any lesson in scope still has an attached material — the caller must delete the material(s)
first via this module's own `DELETE .../materials/{materialId}`. No Java/API-level coupling
exists between the two modules for this (only a SQL-level `FOREIGN KEY`), consistent with
`.claude/rules/architecture.md`'s cross-module boundary rules.

## Cross-module interaction: video-access-management and live-class-management (Wave 5)

- `material.video_asset_id` is an **opaque cross-domain pointer only** — this module
  never imports `video-access-management`'s `VideoAsset` entity or repository, per
  `.claude/rules/architecture.md`'s cross-module boundary rule. The only dependency is
  `VideoAccessApi#isVideoAssetReadyAndOwnedByTenant` (create-time validation) — see
  `docs/api/video-access-management.md` for the reverse dependency
  (`MaterialLookupApi#resolveVideoAssetOwnership`, used by that domain's own guards).
- `material.session_id` is likewise an opaque pointer into `live-class-management`'s
  `class_session` table (composite FK `(tenant_id, session_id)`, no JPA relation, no
  entity import) — a material attaches to a Lesson, a Session, or both (never neither).

## Process gap

This file did not exist when frontend implementation began — the module plan
(`docs/plans/MVP-009 Lessons and Learning Materials.md` §19, §20 step 5) required it be
produced via the `review-api-contract` skill *before* frontend work started, but frontend
was built directly against the plan's own draft contract table (§10) and the controller
itself. A later multi-agent module review caught the gap; this file is the retroactive
correction, reflecting the actual shipped contract — including the full-resource-PATCH-based
reorder design, which diverges from the plan's draft — rather than the superseded draft.
