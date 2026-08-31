package com.lms.enrollmentmanagement.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lms.common.tenant.TenantContext;
import com.lms.enrollmentmanagement.domain.Enrollment;
import com.lms.enrollmentmanagement.domain.ReactivationRequest;
import com.lms.enrollmentmanagement.domain.ReactivationRequestStatus;
import com.lms.enrollmentmanagement.repository.EnrollmentRepository;
import com.lms.enrollmentmanagement.repository.ReactivationRequestRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Mockito-only unit coverage for {@link ReactivationLinkingApiImpl} (bug fix,
 * MVP-012 review, Bug-2 part (b)): proves the LOCKED repository finder
 * ({@code findApprovedUnfulfilledByEnrollmentIdForUpdate}) is the one
 * actually used at link-write time, never the unlocked {@code
 * findApprovedUnfulfilledByEnrollmentId} - and that losing the race (the
 * locked finder returns empty, as it would once a concurrent winner has
 * already consumed the request) throws {@link IllegalStateException}, which
 * {@code OrderService#createOrder} maps to a clean {@code 409} (see {@code
 * OrderServiceTest}).
 */
@ExtendWith(MockitoExtension.class)
class ReactivationLinkingApiImplTest {

	private static final UUID TENANT_ID = UUID.randomUUID();

	private static final UUID STUDENT_ID = UUID.randomUUID();

	private static final UUID COURSE_ID = UUID.randomUUID();

	private static final UUID NEW_ORDER_ID = UUID.randomUUID();

	@Mock
	private EnrollmentRepository enrollmentRepository;

	@Mock
	private ReactivationRequestRepository reactivationRequestRepository;

	@Mock
	private TenantContext tenantContext;

	private ReactivationLinkingApiImpl service;

	@BeforeEach
	void setUp() {
		service = new ReactivationLinkingApiImpl(enrollmentRepository, reactivationRequestRepository, tenantContext);
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
	}

	@Test
	void linkUsesTheLockedFinderNeverTheUnlockedOne() {
		Enrollment enrollment = Enrollment.fromConfirmedPayment(TENANT_ID, STUDENT_ID, COURSE_ID, UUID.randomUUID(),
				null);
		ReactivationRequest request = new ReactivationRequest(TENANT_ID, UUID.randomUUID(), STUDENT_ID);
		when(enrollmentRepository.findCurrentByStudentIdAndCourseId(STUDENT_ID, COURSE_ID))
			.thenReturn(Optional.of(enrollment));
		when(reactivationRequestRepository.findApprovedUnfulfilledByEnrollmentIdForUpdate(enrollment.getId(),
				TENANT_ID, ReactivationRequestStatus.APPROVED)).thenReturn(Optional.of(request));
		when(reactivationRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
		// approve() is required before linkNewOrder() will accept a link.
		request.approve(UUID.randomUUID(), java.time.Instant.now());

		assertThatCode(() -> service.linkApprovedRequestToNewOrder(STUDENT_ID, COURSE_ID, NEW_ORDER_ID))
			.doesNotThrowAnyException();

		verify(reactivationRequestRepository, never()).findApprovedUnfulfilledByEnrollmentId(any());
	}

	/**
	 * The concurrency-LOSS outcome, exercised here at the Mockito level only:
	 * proves that when the locked finder comes back empty (as it correctly
	 * would for the SECOND of two concurrent callers, AFTER a real {@code
	 * PESSIMISTIC_WRITE} lock has let the first winner's transaction commit
	 * first and consume the only approved-unfulfilled request), this method
	 * throws {@link IllegalStateException}, mapped to a clean {@code 409} by
	 * {@code OrderService}. This test does NOT itself exercise real DB-level
	 * locking - it is a pure Mockito test that stubs the locked finder
	 * directly to {@code Optional.empty()}, simulating the outcome without
	 * ever opening a second connection/transaction. The genuine, real-Postgres
	 * proof that the {@code PESSIMISTIC_WRITE} lock actually serializes two
	 * genuinely overlapping callers (MVP-012 review finding L6, closed by
	 * finding H8) lives in {@code ReactivationOrderConcurrencyIntegrationTest}.
	 */
	@Test
	void linkThrowsWhenTheLockedFinderFindsNoLongerUnfulfilledRequest() {
		Enrollment enrollment = Enrollment.fromConfirmedPayment(TENANT_ID, STUDENT_ID, COURSE_ID, UUID.randomUUID(),
				null);
		when(enrollmentRepository.findCurrentByStudentIdAndCourseId(STUDENT_ID, COURSE_ID))
			.thenReturn(Optional.of(enrollment));
		when(reactivationRequestRepository.findApprovedUnfulfilledByEnrollmentIdForUpdate(enrollment.getId(),
				TENANT_ID, ReactivationRequestStatus.APPROVED)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.linkApprovedRequestToNewOrder(STUDENT_ID, COURSE_ID, NEW_ORDER_ID))
			.isInstanceOf(IllegalStateException.class);

		verify(reactivationRequestRepository, never()).save(any());
	}

}
