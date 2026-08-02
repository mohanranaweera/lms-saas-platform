# Device Authentication

**Domain:** `identity-access-service` (Module 17) · **Portal(s):** Student, Tenant Admin

## 1. Business purpose

Prevent account-sharing by limiting concurrent devices per student login.

Source: `docs/requirements/source-requirements.md` Module 17.

## 2. Actors

- **Student** — device registered at login
- **Tenant Admin / authorized staff** — reset, tenant/course-level overrides (`user-roles-and-permissions.md` §2 "Devices" row: Institute Owner `V/C/E`, Student Support `V (request only)`)
- **`identity-access-service`** backend

## 3. Preconditions

`identity-access-service` login must exist (MVP); ADR-007 decisions implemented (JWT access +
opaque refresh token, server-issued device identifier, `device_session` table); tenant/course/
plan-level config store for override precedence.

## 4. Normal flow

1. Student logs in.
2. Server generates/persists a device identifier atomically with the login-activity/audit record.
3. Device limit resolved by precedence — student-level exception > course-level override > tenant-level override > plan-level default.
4. If under limit, `device_session` row created, login succeeds.
5. Device history retrievable, tenant-filtered, for admin review.

## 5. Alternative flows

- Login beyond resolved limit: rejected server-side 401/403 with a machine-readable reason (e.g. `DEVICE_LIMIT_EXCEEDED`) — the frontend never independently counts devices or decides this client-side; no client-side "try again" bypass exists.
- Admin reset: requires device-reset permission for that specific tenant/student; persisted cooldown (`reset_at`) blocks immediate slot reuse; exactly one audit row written.
- Suspicious-login signal (impossible travel, rapid device churn, many IPs): flagged + notified by default at launch, **not hard-blocked** until a Phase 2 refinement — independent of, and does not relax, the device-limit hard-block.
- Cross-tenant: Tenant A admin resets Tenant B's student device — rejected.

## 6. Authorization rules

Device registration itself is automatic/server-driven at login (no user action to authorize).
Device reset: Institute Owner `V/C/E`; Student Support `V (request only)`; Read-only Auditor `V`
only (never succeeds on a mutating endpoint). No other staff sub-role has device-reset access.

## 7. Tenant rules

`device_session` is tenant-owned, `tenant_id`-scoped. Device-limit override precedence
(student > course > tenant > plan) is itself tenant/course-scoped config.

## 8. Acceptance criteria

- [ ] Login beyond the configured limit is rejected server-side (401/403), never merely UI-flagged.
- [ ] Override precedence test: student > course > tenant > plan, with multiple levels configured simultaneously, resolves to the most specific.
- [ ] Device reset requires tenant/student-scoped permission; unauthorized actor is rejected server-side even if a UI control is reachable.
- [ ] Cooldown after reset is enforced server-side via a persisted timestamp, not simulated client-side.
- [ ] Exactly one audit log row per reset action.
- [ ] Suspicious-login detection produces an audit/security log entry at minimum.
- [ ] Cross-tenant negative test on device/session read and reset endpoints.
- [ ] Device status badges (Active/Reset-Pending-Cooldown/Blocked) pair color with text/icon.

## 9. Audit requirements

**Mandatory.** Device resets must write an audit entry (actor id, tenant id, target student id,
action=device reset, timestamp) in the **same transaction** as the reset itself. Device
registration + login-activity/audit record must also be written atomically.

## 10. MVP or later-phase classification

**Phase 2.** FR-IAS-3 through FR-IAS-7; `module-catalog.md` line 28; `source-requirements.md`
line 647. **Mechanism-level decisions are Accepted in ADR-007 (2026-08-02)** — not still open.

## UI-state and portal notes

- **Portal placement**: Student `Devices > My Devices`; Tenant Admin `Devices > Device Policy`, `Device Reset & History`.
- Device Reset table row action is icon-only and requires a specific `aria-label` per student (e.g. "Reset device for student Jane Doe").
- Student-side "request removal" flows into a support ticket, not a self-service reset.

## Open decisions

- Device status chip vocabulary (Active/Reset-Pending/Blocked) is not enumerated in `docs/ui-ux/component-library-spec.md` §2.10 — needs addition.
- Exact empty-state copy for "My Devices" with no registered devices.
