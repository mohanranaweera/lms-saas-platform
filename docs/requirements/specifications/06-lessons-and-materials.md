# Lessons and Materials

**Domain:** `content-management` (Module 7) · **Portal(s):** Teacher, Tenant Admin, Student

## 1. Business purpose

Let teachers organize course content (lessons/modules/sessions) and upload/attach protected
learning materials (PDFs, images, notes, videos, recordings), with access/visibility/expiry
controls that discourage redistribution.

Source: `docs/requirements/source-requirements.md` Module 7.

## 2. Actors

- **Teacher** — upload/organize, primary owner
- **Teacher Assistant** — create/edit (PROVISIONAL)
- **Tenant Admin / Institute Owner** — full CRUD + oversight
- **Content Manager** — `V/C/E/D` on Materials
- **Course Coordinator** — `V` only
- **Student** — consumer, subject to visibility/expiry/limits

## 3. Preconditions

Course/module/lesson exists to attach material to; uploading actor is authorized for the target
tenant/course/entity; tenant is active.

## 4. Normal flow

1. Teacher/Content Manager opens `Materials Manager`, uploads PDFs/images/notes; attaches videos/Zoom recordings/YouTube-Vimeo links.
2. Backend validates server-side (MIME/content sniffing, size, uploader permission) before accepting — reject on failure with no partial write to storage.
3. Material is organized by lesson/module/session; visibility is set (exact visibility taxonomy not itemized anywhere — Open Decision).
4. Optional: expiry date, view/download limits, watermarking are configured.
5. A course's material list on the student side reflects only materials explicitly attached to it, and enforces visibility at fetch time — not just hidden in navigation.
6. Student views material via `Lesson/Material View`; expired or limit-exceeded material returns a distinct denied state, not a generic error.

## 5. Alternative flows

- Upload fails validation (oversized file, MIME mismatch, e.g. a renamed executable): rejected, no partial storage write.
- A student from tenant A (or a different course in the same tenant) attempts to fetch a protected material by guessing/incrementing its ID: rejected 403/404, not silently empty.
- Drag-and-drop material ordering must have a keyboard-operable equivalent (explicit "move up/down" controls) — required, not optional.
- Bulk-upload partial-failure behavior: unspecified (Open Decision).

## 6. Authorization rules

Per `docs/requirements/user-roles-and-permissions.md` §2, row "Materials": Institute Owner =
`V/C/E/D`; Course Coordinator = `V`; Content Manager = `V/C/E/D`; Read-only Auditor = `V`; others
= `—`. Teacher/Teacher Assistant permissions are covered separately (§3, PROVISIONAL for the
assistant).

## 7. Tenant rules

- Material tables are tenant-owned.
- No binary media may be streamed/stored through the Spring Boot app itself — must go through `integration-management`'s external object-storage `api`.
- Boundary: video-specific protected playback belongs to `video-access-management`, not `content-management`; Zoom recordings attached to lessons belong to `live-class-management` with its own tracking.

## 8. Acceptance criteria

- [ ] Given a valid PDF/image/notes upload by an authorized Teacher/Content Manager, then it succeeds and is server-side validated (MIME/size/ownership) before acceptance.
- [ ] Given an unauthorized uploader attempts an upload, then it is rejected with no partial write.
- [ ] Given a material with an expiry date in the past, when a student fetches it, then a distinct "access expired" state is returned, not a generic 404/500.
- [ ] Given a student from tenant A requests a material ID belonging to tenant B, then the request is rejected 403/404.
- [ ] Given a student requests another student's protected document (same tenant), then the request is rejected.
- [ ] Drag-and-drop lesson/material ordering has a working keyboard alternative, verified in an accessibility review.
- [ ] Cross-tenant negative test on material upload/list/delete.

## 9. Audit requirements

**Mandatory for deletion only.** `.claude/rules/security.md`'s mandatory list explicitly names
"material/course content deletions." Creation/edit is not on that list. Deletion entry must
capture actor, tenant, target material ID, timestamp, and before/after.

## 10. MVP or later-phase classification

**MVP** for upload of PDF/image/notes, organization by lesson/module/session, and visibility
enforcement (FR-CNT-1/2). Expiry, view/download limits, static watermarking, versioning/bulk
upload/folder structure/drag-and-drop (FR-CNT-3/4/5) are **Phase 2**. YouTube/Vimeo attachment,
dynamic watermarking, document analytics (FR-CNT-6) are **Phase 3**.

**Phase-boundary contradiction to resolve**: `source-requirements.md` module 7 lists "Attach Zoom
recordings" as a required feature of Learning Materials Management with no phase distinction,
implying MVP. But `functional-requirements.md` places `live-class-management` (which owns Zoom
recording management, FR-LCM-3) entirely in **Phase 2**. "Attach a Zoom recording to a lesson"
cannot actually be delivered at MVP — recommend treating MVP scope for content-management as
PDF/image/notes only, with Zoom-recording attachment gated by `live-class-management`'s own
Phase-2 timeline.

## UI-state and portal notes

- **Portal placement**: Teacher `Materials Manager`; Tenant Admin `Materials Oversight`; Student `Lesson/Material View`, `Video Player`.
- File upload inputs need clear accessible labels stating accepted formats/size limit.
- Uploaded/protected content must never be reachable via a direct, predictable URL — the UI must always route material/video fetches through the signed-URL/token-issuing backend flow.

## Open decisions

- No concrete visibility taxonomy is defined anywhere (what values "material visibility" can take).
- Bulk-upload partial-failure behavior is unspecified.
- The exact seam between `content-management`'s expiry/limits and `video-access-management`'s full playback-security stack for "a video attached as a lesson material" is not spelled out.
