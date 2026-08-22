# content-management — API Contract

Covers Lessons and Learning Materials (MVP-009 / `com.lms.contentmanagement`). Derived
directly from the shipped backend implementation (`MaterialController`, its DTOs, and
`GlobalExceptionHandler`), not from the pre-implementation draft in
`docs/plans/MVP-009 Lessons and Learning Materials.md` §10 — that draft is superseded by
this file. Written retroactively, after a full module review found this contract file had
never been produced despite the plan requiring it before frontend work began (§19/§20 step
5); see the "Process gap" note at the bottom. This follows the exact same retroactive
pattern already used for `docs/api/course-management.md`.

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
3. **Student** callers: allowed only for `action == VIEW` **and** `ownership.coursePublished()`.
   Any other case (a mutating action, or an unpublished course) → `404 NOT_FOUND` ("Lesson
   not found") — a Student never receives a `403` from this guard; every denial reason
   (cross-tenant, wrong course, unpublished course) is anti-enumeration-collapsed into the
   identical generic 404. A **`HIDDEN`** material additionally 404s for a Student at the
   single-fetch/download-url layer (`MaterialService#loadMaterial`), same generic message,
   same code — not distinguishable from "doesn't exist."
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

**Currently always `503 SERVICE_UNAVAILABLE`** in every environment — `ObjectStorageApi` has
no real implementation yet (`integration-management` doesn't exist; the wired default bean
is `UnavailableObjectStorageApi`, which fails loud rather than silently succeeding against a
fake local store). This is a known, tracked, intentional gap (plan §21 item 1), not a bug.

### `POST .../materials`

Upload a material. **Multipart** (`multipart/form-data`), not JSON:

| Part | Type | Notes |
|---|---|---|
| `file` | file | Required. Server-side validated in order: size (`app.content.material.max-file-size-bytes`, currently `26214400` = 25 MiB) then magic-byte content-sniffing (`ContentSniffer`) against an allow-list of `application/pdf`, `image/png`, `image/jpeg`, `image/gif`, `text/plain` — **never** the client-declared `Content-Type` or file extension. |
| `title` | text | Required, max 255 chars. |

`sequence` is **not** a request field — the server always appends
(`max(existing sequence in lesson) + 1`). **Success — `201`** (`ApiResponse<MaterialResponse>`).
**`413 PAYLOAD_TOO_LARGE`** on oversize (also enforced at the container level via
`spring.servlet.multipart.max-file-size: 25MB`, independently of the service-layer
constant). **`415 UNSUPPORTED_MEDIA_TYPE`** on a failed content-sniff. On any validation
failure, **zero** storage calls are made and **zero** rows are persisted (ordering:
authorization → size → content-sniff → only then `ObjectStorageApi.store(...)` → only then
the `material` row is persisted, in a separate step, per `.claude/rules/backend.md`'s
"never span a transaction across an outbound call" rule). **Currently always `503`** for the
same reason as the download-url endpoint above (no real `ObjectStorageApi` yet).

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

**`MaterialResponse`**:

```jsonc
{
  "id": "<uuid>", "lessonId": "<uuid>", "title": "...", "originalFilename": "slides.pdf",
  "mimeType": "application/pdf", "sizeBytes": 204800, "sequence": 1,
  "visibility": "VISIBLE",              // VISIBLE | HIDDEN
  "expiryAt": null,                     // exists for forward-compat; unenforced AND unwritable at MVP — no request DTO accepts it yet
  "uploadedBy": "<uuid>", "createdAt": "2026-08-17T00:00:00Z", "updatedAt": "2026-08-17T00:00:00Z"
}
```

## Error codes

Standard `ApiErrorCodes` (see `docs/api/identity-access-service.md`'s error-codes table for
the full platform list) apply. Content-management-specific cases:

| Case | Code | Status |
|---|---|---|
| Cross-tenant / wrong-course / unpublished-course / hidden-material lesson or material id, for a Student | `NOT_FOUND` | `404` (uniform message, anti-enumeration — never distinguishable from "doesn't exist") |
| Cross-tenant / not-owned-by-Teacher lesson id, for staff/Teacher | `NOT_FOUND` | `404` |
| Staff without `MATERIALS` grant, or Teacher/TA not owning the lesson's course | `FORBIDDEN` | `403` |
| Sequence collision on `PATCH` | `CONFLICT` | `409` |
| Deleting a lesson/module with an attached material | `CONFLICT` | `409` (via DB FK violation, `GlobalExceptionHandler.handleDataIntegrityViolation` — generic message, does not name the cause) |
| Oversized upload | `PAYLOAD_TOO_LARGE` | `413` |
| Failed content-sniff | `UNSUPPORTED_MEDIA_TYPE` | `415` |
| Object storage not configured (`integration-management` not yet built) | `SERVICE_UNAVAILABLE` | `503` |
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

## Process gap

This file did not exist when frontend implementation began — the module plan
(`docs/plans/MVP-009 Lessons and Learning Materials.md` §19, §20 step 5) required it be
produced via the `review-api-contract` skill *before* frontend work started, but frontend
was built directly against the plan's own draft contract table (§10) and the controller
itself. A later multi-agent module review caught the gap; this file is the retroactive
correction, reflecting the actual shipped contract — including the full-resource-PATCH-based
reorder design, which diverges from the plan's draft — rather than the superseded draft.
