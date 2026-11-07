# ADR-033 — Student support impersonation ("Log as Student")

Status: Accepted 2026-11-07 (approved by the task owner's explicit STU-MGMT-02 requirement).
Touches the change-controlled authentication architecture additively; ADR-007 is otherwise unchanged.

## Context

Klass offers "Login As Student" on every student row. `docs/architecture/authentication-authorization.md`
§10 requires any impersonation to be a distinct, backend-issued, audited session that preserves the
admin's and the student's identities separately. `.claude/rules/security.md` requires start and end
audit entries.

## Decision

1. **Permission.** New `DomainArea.STUDENT_IMPERSONATION`; only `TENANT_ADMIN` holds `CREATE_EDIT`.
   Teachers, staff sub-roles, students and Platform Admin do not.
2. **Session row.** `impersonation_session` (tenant-owned): actor user + actor `device_session`,
   subject user + student profile, reason, `started_at`, absolute `expires_at` (default 30 min,
   `app.security.impersonation.ttl`), `ended_at`, `end_reason` (EXITED, REPLACED, EXPIRED, ACTOR_REVOKED),
   SHA-256 of a 256-bit handoff secret. Rows are never deleted.
3. **Credentials.** The handoff secret travels only in an HttpOnly, SameSite=Strict cookie scoped to
   `/api/v1/auth/impersonation`. Access tokens are short-lived JWTs (sub = student, role STUDENT,
   `sid` = impersonation session id, `imp=true`, `act` = admin id), held in memory only, never in
   URLs or web storage, never valid past the session's `expires_at`, never refreshable via
   `/auth/refresh`.
4. **Validation.** The JWT filter accepts an `imp` token only while the session is active and
   unexpired, the admin account is ACTIVE and the admin's own device session is usable (admin logout
   ends support access). Nested impersonation and non-student/inactive/pending/removed/cross-tenant
   targets are refused. One active session per admin: a new start ends the previous one (REPLACED).
5. **Read-only.** Every non-GET request on an impersonation token except
   `/api/v1/auth/impersonation/end` is refused with `403 IMPERSONATION_READ_ONLY`, so support sessions
   cannot change passwords, pay, enroll, submit exams, record attendance/watch time or obtain playback
   tokens, and create no device sessions (no last-login or device-slot side effects).
6. **Audit.** `student.impersonation_started` (actor, subject, reason, expiry) and
   `student.impersonation_ended` (end reason) in `audit_log`, actor = the admin.
7. **Frontend.** The support session lives in the tab that opened it (a non-credential sessionStorage
   marker selects the impersonation token endpoint on reload); a persistent banner names the student
   and offers Exit; exit clears the query cache.

## Consequences

Support staff see exactly what the student sees, with the student's own access rules, but can change
nothing. A future "act on behalf" capability (writes) would need a new ADR.
