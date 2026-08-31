package com.lms.enrollmentmanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lms.common.tenant.TenantContext;
import com.lms.coursemanagement.api.CourseAccessWindow;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.enrollmentmanagement.api.EnrollmentAccessApi;
import com.lms.enrollmentmanagement.domain.Enrollment;
import com.lms.enrollmentmanagement.repository.EnrollmentRepository;
import com.lms.paymentmanagement.api.PaymentStatusApi;
import com.lms.paymentmanagement.api.SlipStatusApi;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Mockito-only unit coverage for {@link EnrollmentActivationService} - the
 * plan's own named load-bearing defense-in-depth control. Before this test,
 * {@code activateFromConfirmedPayment} was only exercised on the happy path
 * (via {@code PaymentAndLedgerIntegrationTest}), where {@link
 * PaymentStatusApi#isConfirmedForCurrentTenant(UUID)} always returns {@code
 * true} by construction - so the independent re-verification branch could
 * only ever fail to fail. These tests force each of the three real branches
 * ({@code false} re-verification, idempotent no-op, race-loss recovery)
 * directly. MVP-011 added {@link SlipStatusApi} as a fourth constructor
 * dependency and {@code activateFromApprovedSlip} as a parallel activation
 * path, mirroring {@code activateFromConfirmedPayment}'s exact same three
 * branches - covered below by the {@code ...FromApprovedSlip} tests.
 *
 * <p>Lives in {@code com.lms.enrollmentmanagement.service} (not the
 * top-level {@code com.lms.enrollmentmanagement} package it originally did)
 * so it can still reference {@link ReactivationTransactionService} by type
 * after that class became package-private (MVP-012 review, finding M1).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EnrollmentActivationServiceTest {

	private static final UUID TENANT_ID = UUID.randomUUID();

	private static final UUID PAYMENT_ID = UUID.randomUUID();

	private static final UUID SLIP_ID = UUID.randomUUID();

	private static final UUID STUDENT_ID = UUID.randomUUID();

	private static final UUID COURSE_ID = UUID.randomUUID();

	@Mock
	private EnrollmentRepository enrollmentRepository;

	@Mock
	private PaymentStatusApi paymentStatusApi;

	@Mock
	private SlipStatusApi slipStatusApi;

	@Mock
	private CourseLookupApi courseLookupApi;

	@Mock
	private TenantContext tenantContext;

	@Mock
	private ReactivationTransactionService reactivationTransactionService;

	@Mock
	private EnrollmentAccessApi enrollmentAccessApi;

	private EnrollmentActivationService service;

	@BeforeEach
	void setUp() {
		service = new EnrollmentActivationService(enrollmentRepository, paymentStatusApi, slipStatusApi,
				courseLookupApi, tenantContext, reactivationTransactionService, enrollmentAccessApi);
		when(courseLookupApi.getAccessDurationDays(COURSE_ID)).thenReturn(Optional.empty());
	}

	@Test
	void refusesToActivateWhenIndependentReVerificationFindsThePaymentNotConfirmed() {
		when(paymentStatusApi.isConfirmedForCurrentTenant(PAYMENT_ID)).thenReturn(false);

		assertThatThrownBy(() -> service.activateFromConfirmedPayment(PAYMENT_ID, STUDENT_ID, COURSE_ID))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("Refusing to activate enrollment");

		verify(enrollmentRepository, never()).existsByStudentIdAndCourseId(any(), any());
		verify(enrollmentRepository, never()).save(any());
	}

	@Test
	void aRepeatedActivationForTheSameStudentAndCourseIsANoOpThatNeverWritesASecondRow() {
		when(paymentStatusApi.isConfirmedForCurrentTenant(PAYMENT_ID)).thenReturn(true);
		when(enrollmentRepository.existsByStudentIdAndCourseId(STUDENT_ID, COURSE_ID)).thenReturn(true);

		assertThatCode(() -> service.activateFromConfirmedPayment(PAYMENT_ID, STUDENT_ID, COURSE_ID))
			.doesNotThrowAnyException();

		verify(enrollmentRepository, never()).save(any());
	}

	@Test
	void losingARaceAgainstAConcurrentDuplicateActivationAttemptIsTreatedAsSuccessNotAnError() {
		when(paymentStatusApi.isConfirmedForCurrentTenant(PAYMENT_ID)).thenReturn(true);
		when(enrollmentRepository.existsByStudentIdAndCourseId(STUDENT_ID, COURSE_ID)).thenReturn(false);
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
		when(enrollmentRepository.save(any(Enrollment.class)))
			.thenThrow(new DataIntegrityViolationException("uq_enrollment_tenant_student_course violated"));

		assertThatCode(() -> service.activateFromConfirmedPayment(PAYMENT_ID, STUDENT_ID, COURSE_ID))
			.doesNotThrowAnyException();

		verify(enrollmentRepository, times(1)).save(any(Enrollment.class));
	}

	@Test
	void aGenuinelyFirstActivationSavesExactlyOneRow() {
		when(paymentStatusApi.isConfirmedForCurrentTenant(PAYMENT_ID)).thenReturn(true);
		when(enrollmentRepository.existsByStudentIdAndCourseId(STUDENT_ID, COURSE_ID)).thenReturn(false);
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
		when(enrollmentRepository.save(any(Enrollment.class))).thenAnswer(invocation -> invocation.getArgument(0));

		service.activateFromConfirmedPayment(PAYMENT_ID, STUDENT_ID, COURSE_ID);

		verify(enrollmentRepository, times(1)).save(any(Enrollment.class));
	}

	/**
	 * Plan §18's explicitly-named unit requirement: {@code
	 * access_expires_at} computation from {@code course.access_duration_days}
	 * for a real, non-null duration (every other test in this class stubs
	 * {@code courseLookupApi} to {@code Optional.empty()} via
	 * {@code @BeforeEach}, exercising only the {@code NULL} -&gt; lifetime-access
	 * branch of {@link EnrollmentActivationService#computeAccessExpiresAt}).
	 */
	@Test
	void aGenuinelyFirstActivationWithATimeLimitedCourseComputesAccessExpiresAtFromTheConfiguredDuration() {
		when(paymentStatusApi.isConfirmedForCurrentTenant(PAYMENT_ID)).thenReturn(true);
		when(enrollmentRepository.existsByStudentIdAndCourseId(STUDENT_ID, COURSE_ID)).thenReturn(false);
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
		when(courseLookupApi.getAccessDurationDays(COURSE_ID)).thenReturn(Optional.of(new CourseAccessWindow(30)));
		when(enrollmentRepository.save(any(Enrollment.class))).thenAnswer(invocation -> invocation.getArgument(0));

		Instant before = Instant.now();
		service.activateFromConfirmedPayment(PAYMENT_ID, STUDENT_ID, COURSE_ID);
		Instant after = Instant.now();

		ArgumentCaptor<Enrollment> captor = ArgumentCaptor.forClass(Enrollment.class);
		verify(enrollmentRepository, times(1)).save(captor.capture());
		Instant accessExpiresAt = captor.getValue().getAccessExpiresAt();
		assertThat(accessExpiresAt).isNotNull();
		assertThat(accessExpiresAt).isBetween(before.plus(30, ChronoUnit.DAYS), after.plus(30, ChronoUnit.DAYS));
	}

	// ------------------------------------------------------------------
	// activateFromApprovedSlip (MVP-011) - mirrors activateFromConfirmedPayment's
	// exact three branches above, against SlipStatusApi instead of
	// PaymentStatusApi.
	// ------------------------------------------------------------------

	@Test
	void refusesToActivateWhenIndependentReVerificationFindsTheSlipNotApproved() {
		when(slipStatusApi.isApprovedForCurrentTenant(SLIP_ID)).thenReturn(false);

		assertThatThrownBy(() -> service.activateFromApprovedSlip(SLIP_ID, STUDENT_ID, COURSE_ID))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("Refusing to activate enrollment");

		verify(enrollmentRepository, never()).existsByStudentIdAndCourseId(any(), any());
		verify(enrollmentRepository, never()).save(any());
	}

	@Test
	void aRepeatedSlipActivationForTheSameStudentAndCourseIsANoOpThatNeverWritesASecondRow() {
		when(slipStatusApi.isApprovedForCurrentTenant(SLIP_ID)).thenReturn(true);
		when(enrollmentRepository.existsByStudentIdAndCourseId(STUDENT_ID, COURSE_ID)).thenReturn(true);

		assertThatCode(() -> service.activateFromApprovedSlip(SLIP_ID, STUDENT_ID, COURSE_ID))
			.doesNotThrowAnyException();

		verify(enrollmentRepository, never()).save(any());
	}

	@Test
	void losingARaceAgainstAConcurrentDuplicateSlipActivationAttemptIsTreatedAsSuccessNotAnError() {
		when(slipStatusApi.isApprovedForCurrentTenant(SLIP_ID)).thenReturn(true);
		when(enrollmentRepository.existsByStudentIdAndCourseId(STUDENT_ID, COURSE_ID)).thenReturn(false);
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
		when(enrollmentRepository.save(any(Enrollment.class)))
			.thenThrow(new DataIntegrityViolationException("uq_enrollment_tenant_student_course violated"));

		assertThatCode(() -> service.activateFromApprovedSlip(SLIP_ID, STUDENT_ID, COURSE_ID))
			.doesNotThrowAnyException();

		verify(enrollmentRepository, times(1)).save(any(Enrollment.class));
	}

	@Test
	void aGenuinelyFirstSlipActivationSavesExactlyOneRowWithTheActivatingSlipIdSet() {
		when(slipStatusApi.isApprovedForCurrentTenant(SLIP_ID)).thenReturn(true);
		when(enrollmentRepository.existsByStudentIdAndCourseId(STUDENT_ID, COURSE_ID)).thenReturn(false);
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
		when(enrollmentRepository.save(any(Enrollment.class))).thenAnswer(invocation -> invocation.getArgument(0));

		service.activateFromApprovedSlip(SLIP_ID, STUDENT_ID, COURSE_ID);

		ArgumentCaptor<Enrollment> captor = ArgumentCaptor.forClass(Enrollment.class);
		verify(enrollmentRepository, times(1)).save(captor.capture());
		assertThat(captor.getValue().getActivatingSlipId()).isEqualTo(SLIP_ID);
		assertThat(captor.getValue().getActivatingPaymentId()).isNull();
	}

	// ------------------------------------------------------------------
	// reactivateFromConfirmedPayment / reactivateFromApprovedSlip (bug fix,
	// MVP-012 review) - EnrollmentActivationService's own responsibility here
	// is now narrow: (1) independently re-verify payment/slip status BEFORE
	// touching the ReactivationTransactionService collaborator at all, (2)
	// compute accessExpiresAt, (3) delegate the actual mutation, passing
	// orderId through unchanged. The mutation/refusal logic itself now lives
	// in ReactivationTransactionServiceTest.
	// ------------------------------------------------------------------

	@Test
	void reactivateFromConfirmedPaymentRefusesWhenIndependentReVerificationFindsThePaymentNotConfirmedAndNeverDelegates() {
		UUID orderId = UUID.randomUUID();
		when(paymentStatusApi.isConfirmedForCurrentTenant(PAYMENT_ID)).thenReturn(false);

		assertThatThrownBy(() -> service.reactivateFromConfirmedPayment(PAYMENT_ID, orderId, STUDENT_ID, COURSE_ID))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("Refusing to reactivate enrollment");

		verify(reactivationTransactionService, never()).reactivateFromConfirmedPayment(any(), any(), any(), any(),
				any());
	}

	@Test
	void reactivateFromConfirmedPaymentDelegatesToTheReactivationTransactionServiceWithTheOrderIdAndComputedAccessExpiresAt() {
		UUID orderId = UUID.randomUUID();
		when(paymentStatusApi.isConfirmedForCurrentTenant(PAYMENT_ID)).thenReturn(true);

		service.reactivateFromConfirmedPayment(PAYMENT_ID, orderId, STUDENT_ID, COURSE_ID);

		verify(reactivationTransactionService, times(1)).reactivateFromConfirmedPayment(PAYMENT_ID, orderId,
				STUDENT_ID, COURSE_ID, null);
	}

	/**
	 * A refusal raised BY the delegate (e.g. no matching approved
	 * reactivation request) must propagate straight back out of this method
	 * unchanged - it is the CALLER's (PaymentConfirmationService's)
	 * responsibility to catch it, never this method's.
	 */
	@Test
	void reactivateFromConfirmedPaymentLetsARefusalFromTheDelegatePropagateUnchanged() {
		UUID orderId = UUID.randomUUID();
		when(paymentStatusApi.isConfirmedForCurrentTenant(PAYMENT_ID)).thenReturn(true);
		doThrow(new IllegalStateException("no APPROVED reactivation request linked to order " + orderId))
			.when(reactivationTransactionService)
			.reactivateFromConfirmedPayment(any(), any(), any(), any(), any());

		assertThatThrownBy(() -> service.reactivateFromConfirmedPayment(PAYMENT_ID, orderId, STUDENT_ID, COURSE_ID))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("no APPROVED reactivation request");
	}

	@Test
	void reactivateFromApprovedSlipRefusesWhenIndependentReVerificationFindsTheSlipNotApprovedAndNeverDelegates() {
		UUID orderId = UUID.randomUUID();
		when(slipStatusApi.isApprovedForCurrentTenant(SLIP_ID)).thenReturn(false);

		assertThatThrownBy(() -> service.reactivateFromApprovedSlip(SLIP_ID, orderId, STUDENT_ID, COURSE_ID))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("Refusing to reactivate enrollment");

		verify(reactivationTransactionService, never()).reactivateFromApprovedSlip(any(), any(), any(), any(), any());
	}

	@Test
	void reactivateFromApprovedSlipDelegatesToTheReactivationTransactionServiceWithTheOrderIdAndComputedAccessExpiresAt() {
		UUID orderId = UUID.randomUUID();
		when(slipStatusApi.isApprovedForCurrentTenant(SLIP_ID)).thenReturn(true);

		service.reactivateFromApprovedSlip(SLIP_ID, orderId, STUDENT_ID, COURSE_ID);

		verify(reactivationTransactionService, times(1)).reactivateFromApprovedSlip(SLIP_ID, orderId, STUDENT_ID,
				COURSE_ID, null);
	}

	// ------------------------------------------------------------------
	// activateOrReactivateFromConfirmedPayment / activateOrReactivateFromApprovedSlip
	// (MVP-012 review, finding M2) - the consolidated branch decision moved
	// here from PaymentConfirmationService/SlipReviewService.
	// ------------------------------------------------------------------

	@Test
	void activateOrReactivateFromConfirmedPaymentActivatesWhenNeverEnrolled() {
		UUID orderId = UUID.randomUUID();
		when(enrollmentAccessApi.resolveAccessState(STUDENT_ID, COURSE_ID))
			.thenReturn(com.lms.enrollmentmanagement.api.EnrollmentAccessState.neverEnrolled());
		when(paymentStatusApi.isConfirmedForCurrentTenant(PAYMENT_ID)).thenReturn(true);
		when(enrollmentRepository.existsByStudentIdAndCourseId(STUDENT_ID, COURSE_ID)).thenReturn(false);
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
		when(enrollmentRepository.save(any(Enrollment.class))).thenAnswer(invocation -> invocation.getArgument(0));

		service.activateOrReactivateFromConfirmedPayment(PAYMENT_ID, orderId, STUDENT_ID, COURSE_ID);

		verify(enrollmentRepository, times(1)).save(any(Enrollment.class));
		verify(reactivationTransactionService, never()).reactivateFromConfirmedPayment(any(), any(), any(), any(),
				any());
	}

	@Test
	void activateOrReactivateFromConfirmedPaymentReactivatesWhenExpired() {
		UUID orderId = UUID.randomUUID();
		when(enrollmentAccessApi.resolveAccessState(STUDENT_ID, COURSE_ID))
			.thenReturn(com.lms.enrollmentmanagement.api.EnrollmentAccessState.expired(UUID.randomUUID(), null,
					false));
		when(paymentStatusApi.isConfirmedForCurrentTenant(PAYMENT_ID)).thenReturn(true);

		service.activateOrReactivateFromConfirmedPayment(PAYMENT_ID, orderId, STUDENT_ID, COURSE_ID);

		verify(reactivationTransactionService, times(1)).reactivateFromConfirmedPayment(PAYMENT_ID, orderId,
				STUDENT_ID, COURSE_ID, null);
		verify(enrollmentRepository, never()).save(any());
	}

	@Test
	void activateOrReactivateFromApprovedSlipActivatesWhenNeverEnrolled() {
		UUID orderId = UUID.randomUUID();
		when(enrollmentAccessApi.resolveAccessState(STUDENT_ID, COURSE_ID))
			.thenReturn(com.lms.enrollmentmanagement.api.EnrollmentAccessState.neverEnrolled());
		when(slipStatusApi.isApprovedForCurrentTenant(SLIP_ID)).thenReturn(true);
		when(enrollmentRepository.existsByStudentIdAndCourseId(STUDENT_ID, COURSE_ID)).thenReturn(false);
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
		when(enrollmentRepository.save(any(Enrollment.class))).thenAnswer(invocation -> invocation.getArgument(0));

		service.activateOrReactivateFromApprovedSlip(SLIP_ID, orderId, STUDENT_ID, COURSE_ID);

		verify(enrollmentRepository, times(1)).save(any(Enrollment.class));
		verify(reactivationTransactionService, never()).reactivateFromApprovedSlip(any(), any(), any(), any(), any());
	}

	@Test
	void activateOrReactivateFromApprovedSlipReactivatesWhenExpired() {
		UUID orderId = UUID.randomUUID();
		when(enrollmentAccessApi.resolveAccessState(STUDENT_ID, COURSE_ID))
			.thenReturn(com.lms.enrollmentmanagement.api.EnrollmentAccessState.expired(UUID.randomUUID(), null,
					false));
		when(slipStatusApi.isApprovedForCurrentTenant(SLIP_ID)).thenReturn(true);

		service.activateOrReactivateFromApprovedSlip(SLIP_ID, orderId, STUDENT_ID, COURSE_ID);

		verify(reactivationTransactionService, times(1)).reactivateFromApprovedSlip(SLIP_ID, orderId, STUDENT_ID,
				COURSE_ID, null);
		verify(enrollmentRepository, never()).save(any());
	}

}
