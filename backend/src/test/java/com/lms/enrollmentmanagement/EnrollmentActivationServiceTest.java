package com.lms.enrollmentmanagement;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lms.common.tenant.TenantContext;
import com.lms.enrollmentmanagement.domain.Enrollment;
import com.lms.enrollmentmanagement.repository.EnrollmentRepository;
import com.lms.enrollmentmanagement.service.EnrollmentActivationService;
import com.lms.paymentmanagement.api.PaymentStatusApi;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
 * directly.
 */
@ExtendWith(MockitoExtension.class)
class EnrollmentActivationServiceTest {

	private static final UUID TENANT_ID = UUID.randomUUID();

	private static final UUID PAYMENT_ID = UUID.randomUUID();

	private static final UUID STUDENT_ID = UUID.randomUUID();

	private static final UUID COURSE_ID = UUID.randomUUID();

	@Mock
	private EnrollmentRepository enrollmentRepository;

	@Mock
	private PaymentStatusApi paymentStatusApi;

	@Mock
	private TenantContext tenantContext;

	private EnrollmentActivationService service;

	@BeforeEach
	void setUp() {
		service = new EnrollmentActivationService(enrollmentRepository, paymentStatusApi, tenantContext);
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

}
