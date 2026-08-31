package com.lms.enrollmentmanagement.api;

import java.util.UUID;

/**
 * One row of {@link EnrollmentReconciliationApi}'s diagnostic output - see
 * that interface's javadoc for the full rationale. Exactly one of {@code
 * activatingPaymentId}/{@code activatingSlipId} is populated per row,
 * mirroring {@code enrollment}'s own "exactly one activation source" shape
 * ({@code ck_enrollment_exactly_one_activation_source}, V19) - this record
 * never represents a row where both or neither evidence id is orphaned,
 * because the check that produces it inspects the two evidence columns
 * independently.
 *
 * @param tenantId the tenant that owns the flagged {@code enrollment} row -
 * platform ops triage, never used to re-derive or bypass tenant isolation
 * elsewhere.
 * @param enrollmentId the flagged {@code enrollment} row's id.
 * @param activatingPaymentId non-null only when this row's evidence is a
 * payment whose current status is not {@code CONFIRMED} (or is missing
 * entirely).
 * @param activatingSlipId non-null only when this row's evidence is a manual
 * payment slip whose current status is not {@code APPROVED} (or is missing
 * entirely).
 * @param reason a human-readable explanation for whoever is triaging this -
 * see {@code EnrollmentReconciliationApi}'s javadoc for what a human should
 * do with a non-empty result.
 */
public record OrphanedEnrollmentEvidence(UUID tenantId, UUID enrollmentId, UUID activatingPaymentId,
		UUID activatingSlipId, String reason) {

}
