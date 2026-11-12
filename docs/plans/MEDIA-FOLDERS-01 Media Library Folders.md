# MEDIA-FOLDERS-01 — Media Library folders (Uploads tab)

Status: approved for implementation (owner request, 2026-11-12). Backend first, then frontend
(separate commits). References: `docs/references/klass-media-library-change/*.png`.

## 1. Scope

- Folders organise **Uploads documents/images** (`media_file`, `usage_area = COURSE_LIBRARY`).
- **Deliberate limit (chosen for our LMS):** internal videos (`video_asset`, provider INTERNAL)
  stay where they are. The "Internal videos" section is shown only at the Uploads root, never
  inside a folder. Provider tabs (Vidrora/DynTube, YouTube, Bunny, Zoom, Archived) are untouched.
- Course/session attachments (`material.media_file_id`) and student downloads through lessons are
  untouched: moving a file changes only `media_file.folder_id`.

## 2. Data model — `V122__media_folders.sql` (additive)

- `media_folder(id, tenant_id → tenant, parent_id NULL, name varchar(100), owner_user_id,
  created_by, created_at, updated_at, version)`.
  - `UNIQUE (tenant_id, id)`; `FK (tenant_id, parent_id) → media_folder(tenant_id, id)`;
    `FK (tenant_id, owner_user_id|created_by) → tenant_user(tenant_id, id)`;
    `CHECK (parent_id IS NULL OR parent_id <> id)`; `CHECK (btrim(name) <> '' AND char_length(name) <= 100)`.
  - Sibling policy: `UNIQUE INDEX (tenant_id, COALESCE(parent_id, '00000000-0000-0000-0000-000000000000'), lower(name))`
    — case-insensitive, per tenant and parent, including root.
  - Indexes `(tenant_id, parent_id)`, `(tenant_id, owner_user_id)`.
- `media_folder_grant(id, tenant_id, folder_id, grantee_user_id, access_level VIEW|UPLOAD,
  granted_by, granted_at)`; `FK (tenant_id, folder_id) → media_folder ON DELETE CASCADE`;
  `FK (tenant_id, grantee_user_id|granted_by) → tenant_user`; `UNIQUE (tenant_id, folder_id, grantee_user_id)`;
  index `(tenant_id, grantee_user_id)`.
- `media_file.folder_id UUID NULL`, `FK (tenant_id, folder_id) → media_folder(tenant_id, id)`,
  partial index `(tenant_id, folder_id) WHERE deleted_at IS NULL`.
- Existing rows keep `folder_id = NULL` (Unfiled) — visibility unchanged.
- Folder delete is a hard delete of an empty folder; soft-deleted files that still point at it get
  `folder_id = NULL` in the same transaction (live files block the delete).

## 3. Permissions (Staff matrix keys already exist — made ENFORCED)

| Key | Label | Spec |
|---|---|---|
| `MEDIA_MANAGE_FOLDERS` | Manage Media Folders | `p(MATERIALS, CREATE_EDIT)`, static default **Tenant Admin only**, configurable for Operator/Manager/seeded roles/Teacher, **Teacher default off** |
| `MEDIA_SHARE_FOLDERS` | Share Media Folders | same |

New `CapabilitySpec.omtTeacherOffFor(String[] roles, primary, implied...)` = `omtTeacherOff` + static
allowlist, so Content Manager (which holds MATERIALS CREATE_EDIT) gets nothing new by default.
`MEDIA_VIEW`, `MEDIA_ADD`, `MEDIA_DELETE` keep their meaning and defaults.

Operation mapping: create / rename / move folder / delete → Manage; add / change / revoke grants →
Share; transfer ownership → Manage **and** Share, owner-or-tenant-wide only.

## 4. Access model (no existing inheritance → explicit per-folder grants)

Caller kinds (all require library VIEW — `MediaFileAccessGuard.requireLibraryAccess(VIEW)`):
- **Tenant-wide folder authority** = non-Teacher/TA staff holding `MEDIA_MANAGE_FOLDERS` or
  `MEDIA_SHARE_FOLDERS` (Tenant Admin by default). Sees every folder.
- Everyone else: per folder `OWNER` (owner_user_id = me) / grant `UPLOAD` / grant `VIEW` / none.
  **No inheritance**: access to a parent says nothing about its children.

Per folder F:
- `canView` = tenant-wide or OWNER or any grant.
- `canUpload` = library CREATE_EDIT (Add Media) and (tenant-wide or OWNER or UPLOAD).
- `canManage` = `MEDIA_MANAGE_FOLDERS` and (tenant-wide or OWNER).
- `canShare` = `MEDIA_SHARE_FOLDERS` and (tenant-wide or OWNER).
- `canTransfer` = `canManage` and `canShare`.
- Grants never give management rights over the folder or over other users' files.

File visibility: `folder_id NULL` → today's rule (every library viewer). `folder_id = F` → only callers
with `canView(F)`. Applied to list, search, counts, summary, uploader options, open-url (signed URL
issuance), rename, delete, move, and attach-to-lesson by id. Inaccessible = 404, never 403, never counted.
Existing lesson materials and student downloads keep working (independent course access).

Tree disclosure:
- Root lists folders the caller can view whose parent is NULL **or not viewable by the caller**
  (a directly shared folder appears at root without its private path).
- Breadcrumb = the contiguous chain of viewable ancestors directly above F; stops at the first hidden one.
- `parentId` in a response is `null` when the parent is not viewable.
- Child listings and picker listings return only viewable folders.

## 5. Concurrency

Every folder-tree / grant mutation, file move, and upload into a folder takes
`pg_advisory_xact_lock(hashtextextended('media-folders:' || tenantId, 0))` then re-reads and re-checks
inside the transaction: cycle check (dest is F or a descendant of F), emptiness at commit
(any child folder incl. hidden, any live file incl. hidden), destination access at upload finalize,
sibling-name uniqueness (DB index is the final guard → 409). Optional `expectedVersion` on rename /
move / delete / transfer → 409 `MEDIA_FOLDER_STALE` on mismatch.

## 6. API (`/api/v1/media-folders`, tenant from auth context only)

| Method & path | Body / params | Result / errors |
|---|---|---|
| `GET /media-folders?parentId=` | absent = root | `MediaFolderSummary[]` (sorted by lower(name)) |
| `GET /media-folders/{id}` | | `MediaFolderDetail` (= summary + `breadcrumb: [{id,name}]`), 404 if not viewable |
| `POST /media-folders` | `{name, parentId?}` | 201 summary; 409 `MEDIA_FOLDER_NAME_TAKEN`; 400 field `name` |
| `PATCH /media-folders/{id}` | `{name, expectedVersion?}` | summary |
| `POST /media-folders/{id}/move` | `{parentId: uuid\|null, expectedVersion?}` | summary; 409 `MEDIA_FOLDER_INVALID_MOVE` (self/descendant), `MEDIA_FOLDER_NAME_TAKEN` |
| `DELETE /media-folders/{id}?expectedVersion=` | | 200; 409 `MEDIA_FOLDER_NOT_EMPTY` (no counts disclosed) |
| `GET /media-folders/{id}/grants` | canShare | `[{userId, name, accessLevel, grantedAt}]` |
| `POST /media-folders/{id}/grants` | `{userIds: uuid[1..50], accessLevel?: VIEW\|UPLOAD (default VIEW)}` | grants list (upsert) |
| `DELETE /media-folders/{id}/grants/{userId}` | canShare | grants list |
| `POST /media-folders/{id}/transfer` | `{newOwnerId, formerOwnerAccess?: NONE\|VIEW\|UPLOAD (default NONE), expectedVersion?}` | summary |
| `GET /media-folders/{id}/eligible-members` | canShare or canTransfer | `[{userId, name}]` approved active teachers, excluding the current owner |

`MediaFolderSummary = {id, name, parentId, ownerUserId, ownerName, access: OWNER|TENANT|UPLOAD|VIEW,
canManage, canShare, canTransfer, canUpload, childFolderCount (viewable), fileCount, version, createdAt, updatedAt}`.

Name rule: `strip()`, 1..100 UTF-16 chars, no control chars (server is authoritative).

Transfer: folders moved = F plus every descendant whose owner equals F's current owner (mixed-owner
subfolders keep their owner). New owner's grants on those folders are removed (owner needs none);
retention VIEW/UPLOAD upserts a grant for the former owner on each transferred folder; NONE adds
nothing (independent role / other grants still apply). File uploaders, file locations and other
grants are untouched.

`/api/v1/media-files` changes:
- `GET` adds `folderId` (uuid), `unfiled` (bool), `uploadedBy` (uuid). Neither folder param = every
  file the caller can view (legacy picker). `folderId` not viewable → 404.
- `GET /media-files/uploaders?folderId=&unfiled=` → `[{userId, name}]` over viewable files in scope.
- `GET /media-files/summary` counts viewable files only.
- `POST` multipart adds `folderId`; requires `canUpload`, rechecked under the lock at insert.
- `POST /media-files/{id}/move` `{folderId: uuid|null}` → file-manage rights (as rename) + `canView(source)`
  + (`null` or `canUpload(dest)`). Same id / uploader / object key / materials.
- `MediaFileResponse` adds `folderId`.

## 7. Audit (same transaction, no signed URLs or file contents)

`media_folder.created|renamed|moved|deleted|shared|unshared|ownership_transferred`, `media_file.moved`;
metadata: names, from/to parent, grantee + from/to level, from/to owner, retention, transferred folder ids.

## 8. Frontend (phase 2)

Uploads URL state `?folder=<id>` (direct links, refresh, Back/Forward). Root: Folders, then Unfiled
(+ Internal videos). In a folder: breadcrumb, name, media count, child Folders, Files, empty state.
Folder card: click / Enter opens; kebab menu Rename, Move…, Share…, Transfer ownership…, Delete
(shown per server flags). Dialogs: New folder (counter 0/100), destination picker with breadcrumbs,
share checkbox picker + existing grants, transfer (owner, new owner, former-owner keeps), delete
typed-name confirm. File card adds Move…; move dialog states the audience change before commit.
