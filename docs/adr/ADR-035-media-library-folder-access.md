# ADR-035 — Media Library folder access (explicit per-folder grants)

Status: Accepted 2026-11-12 (owner request for Klass-style Uploads folders; plan
`docs/plans/MEDIA-FOLDERS-01 Media Library Folders.md`). Additive to ADR-034: two existing
placeholder capabilities become enforced; no other capability default changes.

## Context

Uploads (`media_file`, COURSE_LIBRARY) were one flat, institute-wide list: every library viewer
saw every file. Klass adds folders with Share (View / Can upload) and Transfer ownership. The LMS
had no resource-level inheritance model to reuse.

## Decision

1. **Folders hold documents/images only.** Internal videos stay at the Uploads root.
2. **Explicit per-folder access, no inheritance.** Relations: owner, grant `UPLOAD`, grant `VIEW`,
   or tenant-wide authority (non-Teacher/TA staff holding Manage or Share Media Folders — Tenant
   Admin by default). Access to a parent never implies access to a child, so moving or
   transferring a folder cannot broaden anyone's access.
3. **Files follow their folder.** Unfiled files keep today's visibility. A filed file is visible
   only to callers who can view its folder — in lists, search, counts, uploader options, signed-URL
   issuance, rename/delete/move and attach-by-id. Hidden = 404 and never counted. Lesson materials
   and student downloads are independent course access and are unchanged.
4. **Tree disclosure.** A viewable folder with a hidden parent appears at the caller's root; its
   breadcrumb stops at the first hidden ancestor and `parentId` is reported as null.
5. **Capabilities.** `MEDIA_MANAGE_FOLDERS` (create/rename/move/delete) and `MEDIA_SHARE_FOLDERS`
   (grant/revoke) are enforced with primary `MATERIALS/CREATE_EDIT` (+ implied `MATERIALS/VIEW`),
   static default Tenant Admin only, Teacher default off. Transfer needs both plus owner or
   tenant-wide authority. `MEDIA_VIEW` / `MEDIA_ADD` / `MEDIA_DELETE` are unchanged.
6. **Concurrency.** All folder-tree, grant, file-move and upload-into-folder writes take a
   per-tenant advisory transaction lock and re-check (cycles, emptiness incl. hidden contents,
   destination access) before commit. A unique index enforces case-insensitive sibling names.
7. **Revocation.** Revoking a grant stops new signed URLs immediately. Already-issued URLs
   follow the existing 5-minute expiry.

## Consequences

- A sibling-name conflict can reveal that a same-named folder exists at that level even when it
  is hidden (the name policy is per tenant and parent, as requested).
- An owner whose folder sits under a hidden parent sees the folder as being at root. The Move dialog
  then disables "Uploads (root)" as the current location (known limitation).
- Inheritance, video folders and folder-level student audiences need a new ADR.
