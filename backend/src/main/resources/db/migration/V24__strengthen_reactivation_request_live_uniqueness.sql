-- enrollment-management (MVP-012 Enrollment and Course Access): closes a
-- database-safety gap found in this module's post-implementation review
-- against V22's `reactivation_request` schema. Purely additive/replacing
-- index statements - no table is locked beyond the index rebuild itself,
-- and neither V19 nor V22/V23 is edited (already-shared/applied; this
-- repo's migration history is append-only per root CLAUDE.md and
-- .claude/rules/tenancy.md).
--
-- Gap: `ReactivationRequestService#submit`'s real business rule is "at most
-- one reactivation request per enrollment that could still result in a
-- future order" - i.e. SUBMITTED, OR APPROVED-and-not-yet-linked-to-an-order
-- (new_order_id IS NULL). V22's `uq_reactivation_request_tenant_enrollment_open`
-- only covered the SUBMITTED case at the database level; the APPROVED-and-
-- unfulfilled case was enforced by a service-layer check-then-insert
-- (`ReactivationRequestRepository#findLiveByEnrollmentId`) alone, with no
-- schema backstop, per that repository method's own javadoc, which
-- explicitly flagged this as "out of scope" pending a follow-up migration.
--
-- Left unfixed, two concurrent `submit()` calls while a prior request for
-- the same enrollment is APPROVED-but-unfulfilled could both pass the
-- service-layer pre-check and both insert a new SUBMITTED row - producing
-- two live requests for one enrollment, which then gives
-- `ReactivationLinkingApi`/`EnrollmentActivationService`'s reactivation
-- methods more than one APPROVED+linkable candidate to choose between,
-- undermining the "exactly one reactivation request in flight per
-- enrollment" invariant this module's design otherwise guarantees by
-- construction.
--
-- Fix: replace `uq_reactivation_request_tenant_enrollment_open` with a wider
-- partial unique index whose WHERE clause matches
-- `findLiveByEnrollmentId`'s predicate exactly (SUBMITTED, OR APPROVED with
-- new_order_id IS NULL), per .claude/rules/backend.md's "prefer invariants
-- enforced by the schema/constraints over invariants enforced only by
-- service-layer discipline" guidance for high-integrity domains.
-- `ReactivationRequestService#submit` already catches
-- `DataIntegrityViolationException` from a losing race and maps it to a
-- clean 409 - no application code change is required alongside this
-- migration.
--
-- CONCURRENTLY deliberately not used - same rationale already documented in
-- V22/V23's header comments (CREATE INDEX CONCURRENTLY cannot run inside a
-- transaction block, and this project's Flyway configuration has no
-- non-transactional-migration precedent; this module's real data volume
-- remains pre-launch/low-row-count per tenant).

DROP INDEX uq_reactivation_request_tenant_enrollment_open;

-- At most one request per (tenant, enrollment) that could still result in a
-- future order: SUBMITTED, or APPROVED-and-unfulfilled. An APPROVED request
-- that is already linked to an order (new_order_id IS NOT NULL) is
-- deliberately excluded, mirroring `findLiveByEnrollmentId`'s own exclusion -
-- once fulfilled, `OrderService`'s own order-creation gate already keeps "at
-- most one order in flight for this enrollment" intact, so a student who
-- reactivates and later expires again may submit a brand-new request without
-- being blocked by their own, already-fulfilled reactivation history.
CREATE UNIQUE INDEX uq_reactivation_request_tenant_enrollment_live
    ON reactivation_request (tenant_id, enrollment_id)
    WHERE status = 'SUBMITTED' OR (status = 'APPROVED' AND new_order_id IS NULL);
