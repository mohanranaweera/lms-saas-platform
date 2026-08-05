# Session View Limits

**Domain:** `video-access-management` (Module 8) · **Portal(s):** Student, Teacher (config)

## 1. Business purpose

Cap concurrent playback sessions and view counts per student/video/course as part of the
video-protection differentiator.

Source: `docs/requirements/source-requirements.md` Module 8.

## 2. Actors

- **Student** — playback
- **`video-access-management`** backend
- **Tenant Admin / Content Manager** — configure per-course/material limits

## 3. Preconditions

Baseline signed-URL/token issuance (MVP, see [20-secure-video.md](./20-secure-video.md)) must
already exist; `enrollment-management` active-access validation available; ADR-008 mechanism
implemented (JWT playback token, Redis-backed single-use `jti` tracking via `SET jti consumed NX
EX <ttl>`); Redis available as the ephemeral session/lock store.

## 4. Normal flow

1. Student requests playback.
2. Backend verifies, in order: authentication → tenant match → enrollment/access → device policy → concurrent-session policy.
3. Short-lived (2-5 min per ADR-008) signed playback token issued.
4. Watch-time and view count tracked against configured limits.

## 5. Alternative flows

- A second concurrent session for the same student/video beyond the configured device policy: new token request rejected server-side, tested with real Redis.
- View/download limit exceeded: distinct "denied" state, not a generic error.
- IP/device anomaly mid-session: server-side revocation of the active session + audit/security log entry.
- Token replayed after expiry or after enrollment/device invalidation: rejected even if signature/expiry were otherwise valid.

## 6. Authorization rules

Configuration of view/session limits is a course/tenant/plan-level setting — Tenant Admin sets
policy; enforcement is a backend check on every playback token request, not a user action. No
staff sub-role can bypass the limit for themselves.

## 7. Tenant rules

Concurrent-session/view-limit state is tenant- and student-scoped; active-session tracking is
Redis-backed (ephemeral) while the policy configuration is PostgreSQL-authoritative tenant-owned
data.

## 8. Acceptance criteria

- [ ] Second concurrent playback session beyond the configured policy is rejected server-side (Testcontainers/real-Redis test required).
- [ ] View-count/limit exceeded returns a distinct denied state distinguishable from "expired" and from a generic error.
- [ ] Anomaly detection (second device/IP on same session) triggers server-side revocation and an audit/security log entry.
- [ ] Token single-use enforcement race-condition test: two simultaneous validation attempts for the same `jti` — only one succeeds.
- [ ] Cross-tenant/cross-session negative test: a token issued for Tenant A/session A is rejected when replayed against Tenant B or a different session.

## 9. Audit requirements

**Mandatory.** A second concurrent session / IP-device anomaly during playback must trigger
server-side session revocation **and** an audit/security log entry (actor/student id, tenant id,
video/course id, signal type, timestamp).

## 10. MVP or later-phase classification

**Phase 2.** FR-VAM-3; `module-catalog.md` line 83.

## Change control flag

Mechanism covered by ADR-008 (video-content-protection), **Accepted (2026-08-02)** —
implementation may proceed against its decisions.

## UI-state and portal notes

- **Portal placement**: part of Student `Courses > Video Player / Lesson-Material View`; configured by Teacher in `Materials Manager`.
- No dedicated UX journey documents the exact copy/state name for "view-limit reached" vs. "concurrent session blocked" — these are two distinct triggers with no precedent in `docs/ui-ux/user-journeys.md` beyond the analogous access-expired pattern.

## Open decisions

- Exact UX copy/state name for "view-limit reached" vs. "concurrent session blocked."
- Watermark UI treatment (placement, dynamic movement) is not detailed in `docs/ui-ux/`.
- Settlement-run-adjacent: N/A here, but ADR-008 approval status blocks implementation.
