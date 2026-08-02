# Zoom (Live Class Integration)

**Domain:** `live-class-management` (Module 9), consuming `integration-management`'s Zoom API interface · **Portal(s):** Teacher, Student, Tenant Admin

## 1. Business purpose

Enable tenant-hosted live classes via Zoom with unique join URLs, attendance sync, and recording
management.

Source: `docs/requirements/source-requirements.md` Module 9.

## 2. Actors

- **Teacher** — schedules
- **Student** — joins
- **Tenant Admin** — manages tenant Zoom account(s), oversight
- **`integration-management`** — owns Zoom credentials/webhooks
- **`attendance-management`** — consumes attendance-sync events

## 3. Preconditions

`integration-management`'s `LiveClassProviderApi` and credential vault must exist (Phase 2);
tenant must have a connected Zoom account (own or platform-default) configured; course/session
structure (`course-management`) must exist.

## 4. Normal flow

1. Teacher schedules via `Live Classes > Schedule Live Class`.
2. `live-class-management` calls `integration-management`'s `LiveClassProviderApi` to create a meeting.
3. A unique, non-guessable/non-reusable join URL is generated per tenant/session.
4. Participant names are standardized.
5. Students see the class in `Live Classes`.
6. Post-session, the recording is imported and attached to a lesson/session.
7. Attendance sync produces records consumable by `attendance-management` via its `api`, not a direct table join.

## 5. Alternative flows

- Join URL shared outside the enrolled cohort: concrete anti-sharing enforcement mechanism is not specified (Open Decision).
- Zoom health-check failure (expired token): surfaced to Tenant Admin, must not silently degrade scheduling.
- Webhook (recording-ready/attendance) delivered twice by Zoom: idempotent handling required.
- Tenant has no Zoom account and no platform default configured: scheduling blocked with a clear message.
- Cross-tenant join-URL guessing: rejected.

## 6. Authorization rules

**Gap to flag.** No explicit permission row exists for "who may schedule a Zoom session / manage
recordings" — the closest mapped rows are Course Coordinator (`V/C/E/A` on Courses) and Teacher
(assigned courses), but neither explicitly covers live-class scheduling authority.

## 7. Tenant rules

Tenant-owned scheduling data (meeting records, join URLs) must not be guessable/reusable
cross-tenant. Tenants may bring their own Zoom account (per-tenant integration config).
Webhook-driven Zoom events must never trust tenant identity from the webhook payload — tenant
must be resolved from the platform's own record of which tenant/session the Zoom meeting ID
belongs to.

## 8. Acceptance criteria

- [ ] Join URLs are not guessable/reusable across tenants or sessions (cross-tenant test required).
- [ ] Attendance-sync records are consumed by `attendance-management` only via `api`, never via direct repository/table access.
- [ ] Recording attach flow links a Zoom recording to exactly the intended lesson/session, tenant-scoped.
- [ ] Webhook idempotency test: duplicate Zoom webhook delivery does not double-write attendance/recording records.
- [ ] Health-check failure on a tenant's Zoom account surfaces a clear operational signal, not a silent scheduling failure.
- [ ] A Teacher/student from Tenant A must not be able to join or fetch recording metadata for a Tenant B session via meeting-ID guessing.

## 9. Audit requirements

**Open Decision** — not in the explicit mandatory list in `security.md`. Whether "prevent link
sharing" enforcement failures or recording-attach actions require a dedicated audit entry is
unresolved.

## 10. MVP or later-phase classification

**Phase 2 core** (FR-LCM-1/2/3), **Phase 3 recommended** (FR-LCM-4: multiple Zoom accounts,
auto-recurring meetings, auto-import/convert recordings, reminder automation).

## UI-state and portal notes

- **Portal placement**: Teacher `Live Classes > Schedule Live Class`, `Recordings`; Student `Live Classes > Upcoming/Past`; Tenant Admin `Live Classes > Zoom Accounts`, `Live Class Oversight`.
- No documented UX for a live-class-specific Zoom outage state.
- Empty state: "no upcoming live classes" (Student) vs. "no classes scheduled yet" (Teacher) — distinct copy expected but not documented.

## Open decisions

- Concrete mechanism for preventing/detecting Zoom link sharing.
- Who may schedule a Zoom session / manage recordings — no explicit permission row.
- Whether Zoom join-link protection follows the same signed-URL/short-lived-token rule as video.
- Whether recording-attach actions require an audit entry.
