# Secure Video

**Domain:** `video-access-management` (Module 8) · **Portal(s):** Student

## 1. Business purpose

Protect course video content from unauthorized access/redistribution via signed, short-lived,
single-use tokens plus deterrence controls (watermark, device/session checks) — described in
source requirements as a core differentiator.

Source: `docs/requirements/source-requirements.md` Module 8.

## 2. Actors

- **Student** — playback
- **`video-access-management`** — issues/validates tokens (sole issuer)
- **`integration-management`** — external video storage adapter, provider not yet selected
- **`enrollment-management`** — access-validity check

## 3. Preconditions

`enrollment-management` activation must exist — a token is never issued without current,
non-expired, non-revoked enrollment. `identity-access-service` device/session mechanism (ADR-007)
for the device-policy check. ADR-008 decisions implemented. External video/object storage
provider selection remains an **open procurement decision**.

## 4. Normal flow

1. Backend verifies, in order: authentication → tenant match → enrollment/access (non-expired, non-revoked) → device policy → concurrent-session policy.
2. Short-lived JWT playback token issued (2-5 min, per ADR-008).
3. Every playback request re-validates server-side (signature, expiry, single-use via Redis `SET NX`).
4. Client renders video with a dynamic, client-side watermark overlay (student name/id, moving position).

## 5. Alternative flows

- Token expired or already consumed: rejected as a replay.
- Enrollment/access revoked after token issuance but before use: rejected even though the token is signature-valid and unexpired — token validity must never be issued on the basis of checks having passed "in the past."
- Cross-tenant or cross-student video ID requested: 403/404, never silently empty.
- IP/device anomaly during active playback: server-side session revocation + audit/security log entry.
- No product/UI copy may claim to "prevent" screen recording — explicit non-claim, deterrence only.

## 6. Authorization rules

Playback token issuance requires the full precondition chain above, in order. Only
`video-access-management`/`content-management` issue/validate tokens. No staff sub-role bypasses
these checks — whether Tenant Admin gets an explicit "preview without enrollment" capability is
unspecified (Open Decision).

## 7. Tenant rules

Playback tokens scoped to single user/session/video/tenant; every fetch of protected video
content requires a server-side tenant + enrollment + role check. No cross-tenant read path
permitted, even for unguessable IDs.

## 8. Acceptance criteria

- [ ] Playback URL is never stable/predictable; expiry enforced server-side, never only by a frontend countdown.
- [ ] Expired/already-consumed token rejected.
- [ ] Cross-tenant/cross-session replay test: a token issued for Tenant A/session A is rejected against Tenant B or a different session.
- [ ] Concurrent-session test with real Redis via Testcontainers.
- [ ] Upload-validation tests for any video ingestion path: oversized/MIME-mismatched/unauthorized-uploader rejections, no partial write on failure.
- [ ] Protected-content ID-guessing tests, cross-tenant and cross-student.
- [ ] No UI/marketing copy claims "prevention" of screen recording (documentation review item, not just code).

## 9. Audit requirements

**Mandatory.** IP/device anomaly during playback → server-side session revocation + audit/security
log entry (actor/student id, tenant id, video/course id, signal type, timestamp).

## 10. MVP or later-phase classification

**MIXED — correction to the "later-phase" framing.** `functional-requirements.md` explicitly
marks FR-VAM-1 ("Secure video playback via signed, short-lived, single-session-scoped
URLs/tokens") as **MVP baseline** and FR-VAM-2 ("Access token validation on every playback
request") as **MVP**. Only the richer control set is later-phase: FR-VAM-3 (view
limits/watch-time/session expiry/watermark/device restriction — see
[17-session-view-limits.md](./17-session-view-limits.md)) and FR-VAM-4
(abuse detection/concurrent-session blocking/audit logs) are **Phase 2**; FR-VAM-5 (external
secure video storage integration, dynamic watermark) is **Phase 3**.

## Change control flag

ADR-008 (video-content-protection mechanism) is **Accepted (2026-08-02)** — implementation may
proceed against its decisions (JWT playback tokens, Redis-backed single-use enforcement,
watermark-only deterrence for MVP, provider selection left open). Presenting "secure video" as a
single later-phase feature would still misstate that the MVP-scoped baseline mechanism (FR-VAM-1/2)
belongs at MVP, not Phase 2 — see classification above.

## UI-state and portal notes

- **Portal placement**: Student `Courses > Video Player`.
- No `user-journeys.md` entry covers the student-facing "video access denied / token invalid" UX flow explicitly.
- No accessibility guidance exists for watermark overlay contrast/positioning.

## Open decisions

- Whether Tenant Admin gets an explicit preview capability without a full enrollment check.
- Exact UX copy for token-invalid/access-denied states.
- Watermark overlay contrast/accessibility treatment.
