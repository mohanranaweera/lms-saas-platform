# Attendance

**Domain:** `attendance-management` (Module 10) · **Portal(s):** Teacher, Tenant Admin, Student

## 1. Business purpose

Track class/session attendance (manual and, later, Zoom-synced) to support attendance reporting
and absence-based interventions — a core feature across the reference product's pricing plans.

Source: `docs/requirements/source-requirements.md` Module 10.

## 2. Actors

- **Teacher / Teacher Assistant** — mark attendance for assigned courses/sessions (both "Yes" per the matrix — this specific line is not part of the Teacher Assistant PROVISIONAL split)
- **Attendance Operator** staff — `V/C/E`
- **Tenant Admin / Institute Owner** — `V/C/E`
- **Student** — views own attendance history only
- **Read-only Auditor** — `V`

## 3. Preconditions

A scheduled class/session exists within a course the marking actor is authorized for; tenant is
active.

## 4. Normal flow

1. Teacher/Attendance Operator opens `Mark Attendance` for a session.
2. Marks each enrolled student present/absent/late (manual marking, MVP).
3. Attendance record persisted, tenant-scoped and course/session-scoped.
4. Student/Course/Teacher attendance reports are generated from these records.
5. (Phase 2) Zoom attendance sync ingests participant data and produces attendance records consumable via `api`, not a direct table join with `live-class-management`.
6. (Phase 2) Absent-student alerts are dispatched asynchronously via `notification-management`, not inline with the sync job.

## 5. Alternative flows

- Marking attendance for a session outside the marker's assigned course: rejected (backend-filtered scope).
- Zoom sync produces ambiguous/partial participant data (name mismatch against enrolled students): reconciliation rule unspecified (Open Decision).
- Attendance-based access restriction (Phase 2): owning-domain boundary for actually *enforcing* the restriction is unclear (Open Decision).
- Cross-tenant: an Attendance Operator of tenant A attempts to read/mark tenant B's attendance — rejected 403/404.

## 6. Authorization rules

Per `docs/requirements/user-roles-and-permissions.md` §2, row "Attendance": Institute Owner =
`V/C/E`; Attendance Operator = `V/C/E`; Read-only Auditor = `V`; others = `—`.

## 7. Tenant rules

Attendance rows are tenant-scoped **and** course/session-scoped. Reports must be tenant- and
role-filtered (a Teacher's report is backend-limited to their own courses).

## 8. Acceptance criteria

- [ ] Given a Teacher marks attendance for their own assigned session, then the record is persisted tenant-scoped and course/session-scoped.
- [ ] Given a Teacher attempts to mark attendance for a session outside their assignments, then the action is rejected 403.
- [ ] Given a student views `My Attendance`, then only their own tenant-scoped attendance history is returned.
- [ ] Given a Zoom-synced attendance ingestion (Phase 2), then the resulting records are consumed through `live-class-management`'s published events/`api`, never via a direct cross-domain table join.
- [ ] Given an absent-student alert fires (Phase 2), then it is dispatched asynchronously and does not block or share a transaction with the sync job.
- [ ] Cross-tenant negative test on attendance marking/read and Zoom-sync ingestion.

## 9. Audit requirements

**None specified.** Attendance marking is not on `.claude/rules/security.md`'s mandatory-audit
list. Stating explicitly: no audit-log requirement is specified for attendance actions in
reviewed material.

## 10. MVP or later-phase classification

**MVP** for class/session attendance, manual marking, student/course/teacher reports (FR-ATT-1/2;
`source-requirements.md` §5 MVP list "Basic attendance"). Zoom sync, absent alerts, late/early
tracking, attendance-based access restrictions are **Phase 2** (FR-ATT-3/4); QR/smart-card
attendance is **Phase 3** (FR-ATT-5).

## UI-state and portal notes

- **Portal placement**: Student `Attendance > My Attendance`; Teacher `Attendance > Mark Attendance`, `Attendance Reports`; Tenant Admin `Attendance > Attendance Reports`.
- Empty state: "no attendance records yet" distinct from "no sessions match the selected date filter."
- Teacher's Attendance Reports is a mobile-first consumer-style surface; Tenant Admin's is admin-heavy with responsive data-table fallback.

## Open decisions

- How "attendance-based access restrictions" (Phase 2) interacts with `enrollment-management`'s access/expiry model is not defined anywhere — is it a distinct gate or does it feed the expiry rules engine? This is a cross-domain ownership ambiguity, analogous to the Module C/D/F gaps.
- No reconciliation rule is specified for Zoom-sync participant-name mismatches against enrolled students.
