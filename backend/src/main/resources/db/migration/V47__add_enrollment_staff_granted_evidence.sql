-- payment-management / enrollment-management (Wave 3 - Student actions:
-- staff-initiated enroll/revoke): adds a nullable staff_grant_reason
-- evidence column to payment, and nullable revoked_at/revoked_by/
-- revoke_reason evidence columns to enrollment.
--
-- Deviation from the wave-03 plan doc, noted per the task's own "follow the
-- actual code" instruction: the plan describes this as "extend payment's
-- method/evidence enum with STAFF_GRANTED". The actual, already-shared
-- `payment` schema (V19) has no `method`/evidence-type enum column at all
-- (its only distinguishing-evidence mechanism today is `gateway_reference`
-- - see OrderService#activateFreeCheckout's "FREE-" + paymentId precedent
-- for ADR-015's FREE-course path). There is therefore no enum to widen.
-- Mirroring that exact precedent instead: a staff-granted enrollment's
-- Payment row is distinguished by a synthesized `gateway_reference` of
-- "STAFF_GRANTED-" + paymentId (service-layer, StudentEnrollmentService),
-- and this migration adds the one new column the plan's evidence-trail
-- intent actually requires beyond that - a mandatory, human-readable
-- reason, alongside CONFIRMED/gateway_reference. No enum/CHECK-constraint
-- widening is needed or added here.
--
-- V19 (payment/enrollment's origin) and V22 (enrollment's lineage columns)
-- are NOT edited - already applied/shared. Purely additive ALTER TABLE.
--
-- staff_grant_reason is nullable at the column level (a real gateway/slip
-- -confirmed payment never sets it) - the "mandatory when staff-granted" a
-- rule is enforced at the service layer (StudentEnrollmentService), the
-- same way payment_refund.reason's NOT NULL + ck_..._not_blank pattern
-- could not be reused here without also constraining every other payment
-- row that has no such reason at all.
--
-- revoked_at/revoked_by/revoke_reason on enrollment are the new revoke()
-- call site's own evidence trail - columns only. revoke() (see
-- EnrollmentActivationService) still only ever additionally touches
-- superseded_at (via the existing supersede() mutation) - never the
-- immutable activation columns (activating_payment_id/activating_slip_id/
-- activated_at), per Enrollment's own class javadoc. revoked_by uses the
-- same composite-FK pattern V18/V22 already established elsewhere, so a
-- reviewer from a different tenant can never be recorded against this
-- tenant's enrollment.

ALTER TABLE payment
    ADD COLUMN staff_grant_reason TEXT NULL;

ALTER TABLE enrollment
    ADD COLUMN revoked_at     TIMESTAMPTZ NULL,
    ADD COLUMN revoked_by     UUID NULL,
    ADD COLUMN revoke_reason  TEXT NULL;

ALTER TABLE enrollment
    ADD CONSTRAINT fk_enrollment_revoked_by FOREIGN KEY (tenant_id, revoked_by)
        REFERENCES tenant_user (tenant_id, id);
