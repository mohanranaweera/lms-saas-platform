package com.lms.paymentmanagement.api;

import java.util.UUID;

/**
 * Wave 3 (Student actions - staff "enroll student in course"), the contract
 * {@code payment-management}'s own {@code
 * com.lms.paymentmanagement.order.service.StudentEnrollmentService} depends
 * on (moved here from {@code user-management}'s {@code StudentService} in the
 * Wave 3 fix-pass, architecture review "3-module dependency cycle" finding -
 * see {@code StudentEnrollmentService}'s javadoc for the full rationale).
 * Change-controlled per {@code .claude/rules/payments.md} §7,
 * approved per {@code docs/adr/ADR-016-staff-granted-enrollment-and-revocation.md}:
 * creates a real {@code Order} + {@code
 * Payment(CONFIRMED)} with a mandatory reason - a new, explicit *kind* of
 * manual evidence (same shape as an approved slip), never a bypass of the
 * payment/ledger trail. Funnels through {@code
 * EnrollmentActivationApi#fromApprovedManualEvidence}, the 5th approved
 * enrollment-write call site.
 */
public interface ManualEnrollmentApi {

	/**
	 * @param studentId the opaque cross-domain student id (= {@code
	 * tenant_user.id}) - resolved by the caller from its own resource id
	 * BEFORE calling this method, never a raw {@code StudentProfile} id.
	 * @param reason mandatory human-readable justification - never blank.
	 * @throws com.lms.common.error.NotFoundException if {@code courseId} does
	 * not resolve within the caller's own tenant.
	 * @throws com.lms.common.error.ConflictException if the student is
	 * already actively enrolled in the course, or the course is not
	 * published/priced.
	 */
	ManualEnrollmentResult grantEnrollment(UUID studentId, UUID courseId, String reason);

}
