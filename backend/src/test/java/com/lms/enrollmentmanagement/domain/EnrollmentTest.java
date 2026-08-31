package com.lms.enrollmentmanagement.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link Enrollment}'s four activation/reactivation
 * factories (MVP-012 review finding H5) - previously untested anywhere
 * (every other test only ever went through the factories incidentally, as
 * fixture setup for a service/repository test). Covers: (1) each factory sets
 * the fields its own javadoc promises, including - for the reactivation pair
 * - {@code reactivatedFromEnrollmentId}; (2) the reactivation factories'
 * {@code null reactivatedFromEnrollmentId} guard; (3) the shared private
 * constructor's {@code ck_enrollment_exactly_one_activation_source}-equivalent
 * invariant (exactly one of {@code activatingPaymentId}/{@code
 * activatingSlipId}) holds identically whether or not {@code
 * reactivatedFromEnrollmentId} is set - i.e. the SAME check every one of the
 * four public factories funnels through, exercised directly via reflection
 * since the constructor itself is private and none of the four public
 * factories can, by construction, ever pass it two-set/none-set arguments.
 */
class EnrollmentTest {

	private static final UUID TENANT_ID = UUID.randomUUID();

	private static final UUID STUDENT_ID = UUID.randomUUID();

	private static final UUID COURSE_ID = UUID.randomUUID();

	private static final UUID PAYMENT_ID = UUID.randomUUID();

	private static final UUID SLIP_ID = UUID.randomUUID();

	private static final UUID PRIOR_ENROLLMENT_ID = UUID.randomUUID();

	// ------------------------------------------------------------------
	// fromConfirmedPayment / fromApprovedSlip (first-time activation).
	// ------------------------------------------------------------------

	@Test
	void fromConfirmedPaymentSetsExpectedFieldsAndNoLineageLink() {
		Instant accessExpiresAt = Instant.now().plusSeconds(3600);

		Enrollment enrollment = Enrollment.fromConfirmedPayment(TENANT_ID, STUDENT_ID, COURSE_ID, PAYMENT_ID,
				accessExpiresAt);

		assertThat(enrollment.getTenantId()).isEqualTo(TENANT_ID);
		assertThat(enrollment.getStudentId()).isEqualTo(STUDENT_ID);
		assertThat(enrollment.getCourseId()).isEqualTo(COURSE_ID);
		assertThat(enrollment.getActivatingPaymentId()).isEqualTo(PAYMENT_ID);
		assertThat(enrollment.getActivatingSlipId()).isNull();
		assertThat(enrollment.getStatus()).isEqualTo(EnrollmentStatus.ACTIVE);
		assertThat(enrollment.getActivatedAt()).isNotNull();
		assertThat(enrollment.getAccessExpiresAt()).isEqualTo(accessExpiresAt);
		assertThat(enrollment.getSupersededAt()).isNull();
		assertThat(enrollment.getReactivatedFromEnrollmentId()).isNull();
	}

	@Test
	void fromApprovedSlipSetsExpectedFieldsAndNoLineageLink() {
		Enrollment enrollment = Enrollment.fromApprovedSlip(TENANT_ID, STUDENT_ID, COURSE_ID, SLIP_ID, null);

		assertThat(enrollment.getActivatingSlipId()).isEqualTo(SLIP_ID);
		assertThat(enrollment.getActivatingPaymentId()).isNull();
		assertThat(enrollment.getStatus()).isEqualTo(EnrollmentStatus.ACTIVE);
		assertThat(enrollment.getAccessExpiresAt()).isNull();
		assertThat(enrollment.getSupersededAt()).isNull();
		assertThat(enrollment.getReactivatedFromEnrollmentId()).isNull();
	}

	// ------------------------------------------------------------------
	// reactivatedFromConfirmedPayment / reactivatedFromApprovedSlip.
	// ------------------------------------------------------------------

	@Test
	void reactivatedFromConfirmedPaymentSetsExpectedFieldsIncludingTheLineageLink() {
		Instant accessExpiresAt = Instant.now().plusSeconds(7200);

		Enrollment enrollment = Enrollment.reactivatedFromConfirmedPayment(TENANT_ID, STUDENT_ID, COURSE_ID,
				PAYMENT_ID, accessExpiresAt, PRIOR_ENROLLMENT_ID);

		assertThat(enrollment.getActivatingPaymentId()).isEqualTo(PAYMENT_ID);
		assertThat(enrollment.getActivatingSlipId()).isNull();
		assertThat(enrollment.getStatus()).isEqualTo(EnrollmentStatus.ACTIVE);
		assertThat(enrollment.getAccessExpiresAt()).isEqualTo(accessExpiresAt);
		assertThat(enrollment.getSupersededAt()).isNull();
		assertThat(enrollment.getReactivatedFromEnrollmentId()).isEqualTo(PRIOR_ENROLLMENT_ID);
	}

	@Test
	void reactivatedFromApprovedSlipSetsExpectedFieldsIncludingTheLineageLink() {
		Enrollment enrollment = Enrollment.reactivatedFromApprovedSlip(TENANT_ID, STUDENT_ID, COURSE_ID, SLIP_ID,
				null, PRIOR_ENROLLMENT_ID);

		assertThat(enrollment.getActivatingSlipId()).isEqualTo(SLIP_ID);
		assertThat(enrollment.getActivatingPaymentId()).isNull();
		assertThat(enrollment.getStatus()).isEqualTo(EnrollmentStatus.ACTIVE);
		assertThat(enrollment.getReactivatedFromEnrollmentId()).isEqualTo(PRIOR_ENROLLMENT_ID);
	}

	@Test
	void reactivatedFromConfirmedPaymentRejectsANullReactivatedFromEnrollmentId() {
		assertThatThrownBy(() -> Enrollment.reactivatedFromConfirmedPayment(TENANT_ID, STUDENT_ID, COURSE_ID,
				PAYMENT_ID, null, null)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("reactivatedFromEnrollmentId must not be null");
	}

	@Test
	void reactivatedFromApprovedSlipRejectsANullReactivatedFromEnrollmentId() {
		assertThatThrownBy(() -> Enrollment.reactivatedFromApprovedSlip(TENANT_ID, STUDENT_ID, COURSE_ID, SLIP_ID,
				null, null)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("reactivatedFromEnrollmentId must not be null");
	}

	// ------------------------------------------------------------------
	// ck_enrollment_exactly_one_activation_source-equivalent invariant,
	// exercised directly at the shared private-constructor level - proves
	// the SAME check every factory (including the reactivation pair, via a
	// non-null reactivatedFromEnrollmentId argument below) funnels through,
	// per the class's own javadoc ("the shared private constructor ...
	// enforces V19's ck_enrollment_exactly_one_activation_source invariant
	// at construction time too, not just at the DB level").
	// ------------------------------------------------------------------

	@Test
	void thePrivateConstructorRejectsBothActivationSourcesSetRegardlessOfLineageLink() {
		assertThatThrownBy(() -> invokePrivateConstructor(PAYMENT_ID, SLIP_ID, null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Exactly one of activatingPaymentId/activatingSlipId must be set");
		assertThatThrownBy(() -> invokePrivateConstructor(PAYMENT_ID, SLIP_ID, PRIOR_ENROLLMENT_ID))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Exactly one of activatingPaymentId/activatingSlipId must be set");
	}

	@Test
	void thePrivateConstructorRejectsNeitherActivationSourceSetRegardlessOfLineageLink() {
		assertThatThrownBy(() -> invokePrivateConstructor(null, null, null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Exactly one of activatingPaymentId/activatingSlipId must be set");
		assertThatThrownBy(() -> invokePrivateConstructor(null, null, PRIOR_ENROLLMENT_ID))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Exactly one of activatingPaymentId/activatingSlipId must be set");
	}

	private static Enrollment invokePrivateConstructor(UUID paymentId, UUID slipId, UUID reactivatedFromEnrollmentId)
			throws Exception {
		Constructor<Enrollment> constructor = Enrollment.class.getDeclaredConstructor(UUID.class, UUID.class,
				UUID.class, UUID.class, UUID.class, Instant.class, UUID.class);
		constructor.setAccessible(true);
		try {
			return constructor.newInstance(TENANT_ID, STUDENT_ID, COURSE_ID, paymentId, slipId, null,
					reactivatedFromEnrollmentId);
		}
		catch (InvocationTargetException ex) {
			if (ex.getCause() instanceof RuntimeException runtimeException) {
				throw runtimeException;
			}
			throw ex;
		}
	}

}
