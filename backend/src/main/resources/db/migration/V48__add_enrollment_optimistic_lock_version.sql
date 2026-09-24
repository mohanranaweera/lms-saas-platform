-- enrollment-management (Wave 3 fix-pass, architecture/security review finding
-- "no concurrency guard on revoke"): adds an optimistic-lock `version` column
-- to `enrollment` only - NOT to `common.persistence.BaseEntity` (which would
-- apply to every entity across every domain in this modular monolith and is
-- a far broader blast radius than this specific gap requires).
--
-- Without this column, two near-simultaneous `POST /enrollments/{id}/revoke`
-- calls for the same row could both pass `EnrollmentActivationService#revoke`'s
-- `supersededAt == null` read-check before either transaction commits, letting
-- the later-committing transaction's revoke evidence (revoked_by/revoke_reason)
-- silently win with no signal to the caller that a race occurred. Hibernate's
-- `@Version` support turns that race into a clean, detectable
-- `ObjectOptimisticLockingFailureException` on the losing transaction's
-- `UPDATE ... WHERE id = ? AND version = ?` (zero rows affected), which
-- `EnrollmentActivationService#revoke` maps to a clean `409 Conflict`.
--
-- V19 (enrollment's origin) and V22/V47 (enrollment's later additive
-- migrations) are NOT edited - already applied/shared. Purely additive
-- ALTER TABLE. NOT NULL DEFAULT 0 backfills every existing row accurately -
-- a version column always starts at 0 for a row that has never been
-- concurrently contended.

ALTER TABLE enrollment
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
