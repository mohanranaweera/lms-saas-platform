# YouTube and Vimeo Integrations

**Domain:** `content-management`, consuming `integration-management`'s YouTube/Vimeo API interfaces · **Portal(s):** Teacher, Student

## 1. Business purpose

Allow attaching externally-hosted YouTube/Vimeo videos as course material without the platform
hosting/storing that content.

Source: `docs/requirements/source-requirements.md` Module 7/16.

## 2. Actors

- **Teacher / Content Manager** — attaches links
- **`content-management`** backend
- **`integration-management`** — only if credentialed API access is used for private/unlisted metadata; otherwise attach-only via public URL

## 3. Preconditions

`content-management`'s basic upload/organization (MVP, PDF/image/notes) as the extended pattern.
Per `docs/architecture/system-context.md` §3, YouTube/Vimeo are explicitly **attach-only** — "the
platform does not host this content" — a meaningfully lighter integration than Zoom/SMS/WhatsApp,
with no credentials/hosting owned by the platform.

## 4. Normal flow

1. Teacher/Content Manager opens Materials Manager, pastes a YouTube/Vimeo URL for a lesson.
2. `content-management` stores the external reference (not the binary).
3. Material is organized by lesson/module/session, visibility set.
4. Student views via Lesson/Material View; playback embeds the external player.

## 5. Alternative flows

- URL invalid/inaccessible/private without proper access: validation error at attach time.
- Cross-tenant: a material-attachment record belonging to Tenant A must not be visible/editable by Tenant B by ID guessing.

## 6. Authorization rules

Gated by the Materials permission row: Content Manager `V/C/E/D`, Institute Owner `V/C/E/D`,
Course Coordinator `V`, others largely `—`.

## 7. Tenant rules

Attached YouTube/Vimeo links/materials are tenant-owned via the course/material they're attached
to; visibility enforcement at fetch time same as other materials.

## 8. Acceptance criteria

- [ ] Attach flow validates the URL is a resolvable YouTube/Vimeo resource before saving.
- [ ] Attached-video references are tenant-scoped and organized identically to platform-hosted materials (visibility, ordering).
- [ ] Documentation/UI copy does not imply the same access-control guarantees (signed URL, single-use token, watermark) apply to externally-embedded content unless/until that gap is explicitly resolved.
- [ ] Cross-tenant test on the attachment record itself (who can attach/see the reference) — separate from the inherent limitation on the external URL's own public accessibility.

## 9. Audit requirements

Material deletions **are** explicitly listed as a mandatory audit action ("material/course
content deletions") — removing a YouTube/Vimeo-linked material triggers the same audit
requirement as any other material deletion. Creation/attach itself is not on the mandatory list.

## 10. MVP or later-phase classification

**Phase 3.** FR-CNT-1 ("Phase 3 (YouTube/Vimeo)"); FR-INT-3; `module-catalog.md` line 72;
`source-requirements.md` line 662.

## Unresolved scope question — important

No document states whether YouTube/Vimeo-attached content is exempt from the "Secure video
playback" requirements in `docs/architecture/video-content-security.md`, or whether
unlisted/access-controlled attachment is expected. Once a video is public on YouTube/Vimeo, the
platform has no enforceable access control over the underlying URL — an inherent limitation, not
a bug, but in tension with the "protected content never reachable via predictable URL" rule,
which cannot apply to a public external URL. This needs explicit resolution before implementation,
not silent assumption either way.

## UI-state and portal notes

- **Portal placement**: Teacher `Materials Manager` (attach action, not a standalone list).
- No documented UX flow exists for YouTube/Vimeo attach failure or "integration not enabled for this tenant" messaging.
- No accessibility requirement is documented specifically for third-party embedded video players (captions, keyboard-operable controls).

## Open decisions

- Whether YouTube/Vimeo-attached content is subject to the same view-limit/expiry/watermark/device-restriction controls as platform-hosted secure video, or exempt.
- UX for attach failure / integration-not-enabled-for-tenant messaging.
- Embedded third-party player accessibility requirements.
