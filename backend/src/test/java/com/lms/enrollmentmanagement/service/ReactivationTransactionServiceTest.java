package com.lms.enrollmentmanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lms.common.tenant.TenantContext;
import com.lms.enrollmentmanagement.domain.Enrollment;
import com.lms.enrollmentmanagement.domain.ReactivationRequest;
import com.lms.enrollmentmanagement.repository.EnrollmentRepository;
import com.lms.enrollmentmanagement.repository.ReactivationRequestRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Mockito-only unit coverage for {@link ReactivationTransactionService} (bug
 * fix, MVP-012 review). This class owns the actual {@code enrollment}
 * mutation for both reactivation paths, deliberately factored out of {@link
 * EnrollmentActivationService} into its own {@code REQUIRES_NEW}-scoped bean
 * - see its own javadoc for the full transactional-boundary rationale.
 * {@link EnrollmentActivationServiceTest} covers the delegation itself
 * (payment/slip re-verification happens BEFORE this collaborator is ever
 * touched); this class covers what happens once it IS touched: idempotent
 * no-op, refusal when no current enrollment resolves, refusal when no {@code
 * APPROVED} reactivation request matches the confirming order (the Bug-2
 * defense-in-depth cross-check), the happy-path supersede+insert, and
 * race-loss recovery.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReactivationTransactionServiceTest {

	private static final UUID TENANT_ID = UUID.randomUUID();

	private static final UUID PAYMENT_ID = UUID.randomUUID();

	private static final UUID SLIP_ID = UUID.randomUUID();

	private static final UUID ORDER_ID = UUID.randomUUID();

	private static final UUID STUDENT_ID = UUID.randomUUID();

	private static final UUID COURSE_ID = UUID.randomUUID();

	@Mock
	private EnrollmentRepository enrollmentRepository;

	@Mock
	private ReactivationRequestRepository reactivationRequestRepository;

	@Mock
	private TenantContext tenantContext;

	private ReactivationTransactionService service;

	@BeforeEach
	void setUp() {
		service = new ReactivationTransactionService(enrollmentRepository, reactivationRequestRepository,
				tenantContext);
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
	}

	// ------------------------------------------------------------------
	// reactivateFromConfirmedPayment
	// ------------------------------------------------------------------

	@Test
	void reactivateFromConfirmedPaymentIsAnIdempotentNoOpWhenAlreadyReactivatedViaThisExactPayment() {
		when(enrollmentRepository.existsByActivatingPaymentId(PAYMENT_ID)).thenReturn(true);

		assertThatCode(() -> service.reactivateFromConfirmedPayment(PAYMENT_ID, ORDER_ID, STUDENT_ID, COURSE_ID,
				null)).doesNotThrowAnyException();

		verify(enrollmentRepository, never()).findCurrentByStudentIdAndCourseId(any(), any());
		verify(enrollmentRepository, never()).save(any());
	}

	@Test
	void reactivateFromConfirmedPaymentRefusesWhenNoCurrentEnrollmentRowExists() {
		when(enrollmentRepository.existsByActivatingPaymentId(PAYMENT_ID)).thenReturn(false);
		when(enrollmentRepository.findCurrentByStudentIdAndCourseId(STUDENT_ID, COURSE_ID))
			.thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.reactivateFromConfirmedPayment(PAYMENT_ID, ORDER_ID, STUDENT_ID, COURSE_ID,
				null)).isInstanceOf(IllegalStateException.class).hasMessageContaining("no current enrollment");

		verify(reactivationRequestRepository, never()).findApprovedByEnrollmentIdAndNewOrderId(any(), any());
		verify(enrollmentRepository, never()).save(any());
	}

	/**
	 * The Bug-2 defense-in-depth cross-check: no APPROVED request whose {@code
	 * newOrderId} matches THIS order - covers both "no request resolves at
	 * all" and "a request resolves for this enrollment but is linked to a
	 * DIFFERENT order" identically, since at the mocked-repository boundary
	 * both scenarios are indistinguishable (the stub simply returns {@code
	 * Optional.empty()} either way - a real repository query is the only
	 * thing that can actually tell them apart).
	 *
	 * <p>MVP-012 review finding M5: a second test previously existed here
	 * ({@code ...WhenTheOnlyApprovedRequestForThisEnrollmentIsLinkedToADifferentOrder})
	 * claiming to cover the "linked to a different order" case specifically,
	 * but its setup was byte-identical to this one - it never actually
	 * constructed a request linked to a different order, so it added no real
	 * coverage. Removed as a true duplicate; the genuine "linked to a
	 * different order" scenario, exercised against {@code
	 * ReactivationRequestRepository#findApprovedByEnrollmentIdAndNewOrderId}'s
	 * REAL query semantics (not a mock), is covered by {@code
	 * PaymentConfirmationReactivationRefusalIntegrationTest#aConfirmationWhoseOnlyApprovedRequestIsLinkedToADifferentOrderStillConfirmsThePayment}.
	 */
	@Test
	void reactivateFromConfirmedPaymentRefusesWhenNoApprovedRequestMatchesTheConfirmingOrder() {
		Enrollment priorEnrollment = activeEnrollmentFixture();
		when(enrollmentRepository.existsByActivatingPaymentId(PAYMENT_ID)).thenReturn(false);
		when(enrollmentRepository.findCurrentByStudentIdAndCourseId(STUDENT_ID, COURSE_ID))
			.thenReturn(Optional.of(priorEnrollment));
		when(reactivationRequestRepository.findApprovedByEnrollmentIdAndNewOrderId(priorEnrollment.getId(), ORDER_ID))
			.thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.reactivateFromConfirmedPayment(PAYMENT_ID, ORDER_ID, STUDENT_ID, COURSE_ID,
				null)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("no APPROVED reactivation request linked to order " + ORDER_ID);

		verify(enrollmentRepository, never()).save(any());
	}

	@Test
	void reactivateFromConfirmedPaymentSupersedesThePriorRowAndInsertsANewOneOnTheHappyPath() {
		Enrollment priorEnrollment = activeEnrollmentFixture();
		Instant accessExpiresAt = Instant.now().plusSeconds(3600);
		when(enrollmentRepository.existsByActivatingPaymentId(PAYMENT_ID)).thenReturn(false);
		when(enrollmentRepository.findCurrentByStudentIdAndCourseId(STUDENT_ID, COURSE_ID))
			.thenReturn(Optional.of(priorEnrollment));
		when(reactivationRequestRepository.findApprovedByEnrollmentIdAndNewOrderId(priorEnrollment.getId(), ORDER_ID))
			.thenReturn(Optional.of(new ReactivationRequest(TENANT_ID, priorEnrollment.getId(), STUDENT_ID)));
		// Both the prior-row supersede write AND the new-row insert now go
		// through saveAndFlush (H2 fix, MVP-012 review) - see
		// ReactivationTransactionService's saveAndFlush comments for why both
		// flushes are load-bearing.
		when(enrollmentRepository.saveAndFlush(any(Enrollment.class))).thenAnswer(inv -> inv.getArgument(0));

		service.reactivateFromConfirmedPayment(PAYMENT_ID, ORDER_ID, STUDENT_ID, COURSE_ID, accessExpiresAt);

		assertThat(priorEnrollment.getSupersededAt()).isNotNull();
		verify(enrollmentRepository, times(1)).saveAndFlush(priorEnrollment);
		verify(enrollmentRepository, times(2)).saveAndFlush(any(Enrollment.class));
		verify(enrollmentRepository, never()).save(any());
	}

	@Test
	void reactivateFromConfirmedPaymentTreatsALostRaceAsSuccessNotAnError() {
		Enrollment priorEnrollment = activeEnrollmentFixture();
		when(enrollmentRepository.existsByActivatingPaymentId(PAYMENT_ID)).thenReturn(false);
		when(enrollmentRepository.findCurrentByStudentIdAndCourseId(STUDENT_ID, COURSE_ID))
			.thenReturn(Optional.of(priorEnrollment));
		when(reactivationRequestRepository.findApprovedByEnrollmentIdAndNewOrderId(priorEnrollment.getId(), ORDER_ID))
			.thenReturn(Optional.of(new ReactivationRequest(TENANT_ID, priorEnrollment.getId(), STUDENT_ID)));
		// The supersede saveAndFlush (the FIRST call) succeeds; the new row's
		// insert saveAndFlush (the SECOND call) is the one that loses the race
		// (a concurrent attempt already inserted the current row first) - H2
		// fix (MVP-012 review) makes this genuinely catchable here rather than
		// surfacing later, uncaught, at commit-time auto-flush.
		when(enrollmentRepository.saveAndFlush(any(Enrollment.class))).thenAnswer(inv -> inv.getArgument(0))
			.thenThrow(new DataIntegrityViolationException("uq_enrollment_tenant_student_course_current violated"));

		assertThatCode(() -> service.reactivateFromConfirmedPayment(PAYMENT_ID, ORDER_ID, STUDENT_ID, COURSE_ID,
				null)).doesNotThrowAnyException();
	}

	// ------------------------------------------------------------------
	// reactivateFromApprovedSlip - mirrors the confirmed-payment tests above
	// against the slip evidence type; a representative subset (idempotency +
	// happy path) rather than the full duplicated matrix, since the
	// underlying logic is identical.
	// ------------------------------------------------------------------

	@Test
	void reactivateFromApprovedSlipIsAnIdempotentNoOpWhenAlreadyReactivatedViaThisExactSlip() {
		when(enrollmentRepository.existsByActivatingSlipId(SLIP_ID)).thenReturn(true);

		assertThatCode(() -> service.reactivateFromApprovedSlip(SLIP_ID, ORDER_ID, STUDENT_ID, COURSE_ID, null))
			.doesNotThrowAnyException();

		verify(enrollmentRepository, never()).findCurrentByStudentIdAndCourseId(any(), any());
		verify(enrollmentRepository, never()).save(any());
	}

	@Test
	void reactivateFromApprovedSlipSupersedesThePriorRowAndInsertsANewOneOnTheHappyPath() {
		Enrollment priorEnrollment = activeEnrollmentFixture();
		when(enrollmentRepository.existsByActivatingSlipId(SLIP_ID)).thenReturn(false);
		when(enrollmentRepository.findCurrentByStudentIdAndCourseId(STUDENT_ID, COURSE_ID))
			.thenReturn(Optional.of(priorEnrollment));
		when(reactivationRequestRepository.findApprovedByEnrollmentIdAndNewOrderId(priorEnrollment.getId(), ORDER_ID))
			.thenReturn(Optional.of(new ReactivationRequest(TENANT_ID, priorEnrollment.getId(), STUDENT_ID)));
		when(enrollmentRepository.saveAndFlush(any(Enrollment.class))).thenAnswer(inv -> inv.getArgument(0));

		service.reactivateFromApprovedSlip(SLIP_ID, ORDER_ID, STUDENT_ID, COURSE_ID, null);

		assertThat(priorEnrollment.getSupersededAt()).isNotNull();
		verify(enrollmentRepository, times(1)).saveAndFlush(priorEnrollment);
		verify(enrollmentRepository, times(2)).saveAndFlush(any(Enrollment.class));
		verify(enrollmentRepository, never()).save(any());
	}

	private static Enrollment activeEnrollmentFixture() {
		Enrollment enrollment = Enrollment.fromConfirmedPayment(TENANT_ID, STUDENT_ID, COURSE_ID, UUID.randomUUID(),
				null);
		// A freshly-constructed (never-persisted) entity has a null id -
		// reactivatedFromEnrollmentId must not be null (Enrollment's own
		// constructor invariant), so this fixture needs a real id, mirroring
		// SlipReviewServiceTest's identical ReflectionTestUtils pattern.
		ReflectionTestUtils.setField(enrollment, "id", UUID.randomUUID());
		return enrollment;
	}

}
