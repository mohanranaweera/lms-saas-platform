package com.lms.enrollmentmanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.common.error.ConflictException;
import com.lms.common.tenant.TenantContext;
import com.lms.enrollmentmanagement.api.EnrollmentAccessState;
import com.lms.enrollmentmanagement.domain.Enrollment;
import com.lms.enrollmentmanagement.domain.ReactivationRequest;
import com.lms.enrollmentmanagement.domain.ReactivationRequestStatus;
import com.lms.enrollmentmanagement.repository.EnrollmentRepository;
import com.lms.enrollmentmanagement.repository.ReactivationRequestRepository;
import com.lms.enrollmentmanagement.support.ReactivationAccessGuard;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.PermissionCheckService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Mockito-only unit coverage for {@link ReactivationRequestService#submit},
 * targeting the "at most one live request" pre-check widened by the bug fix
 * described in that method's javadoc (MVP-012 review, Bug-2 part (a)): a new
 * submission must be rejected not only while an existing request is {@code
 * SUBMITTED}, but also while one is {@code APPROVED} and still unfulfilled
 * ({@code newOrderId IS NULL}) - and must NOT be rejected once an earlier
 * {@code APPROVED} request has already been linked to an order (fulfilled).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReactivationRequestServiceTest {

	private static final UUID TENANT_ID = UUID.randomUUID();

	private static final UUID ENROLLMENT_ID = UUID.randomUUID();

	private static final UUID STUDENT_ID = UUID.randomUUID();

	private static final UUID COURSE_ID = UUID.randomUUID();

	@Mock
	private ReactivationRequestRepository reactivationRequestRepository;

	@Mock
	private EnrollmentRepository enrollmentRepository;

	@Mock
	private EnrollmentExpiryService enrollmentExpiryService;

	@Mock
	private ReactivationAccessGuard accessGuard;

	@Mock
	private PermissionCheckService permissionCheckService;

	@Mock
	private AuditLogApi auditLogApi;

	@Mock
	private TenantContext tenantContext;

	private ReactivationRequestService service;

	@BeforeEach
	void setUp() {
		service = new ReactivationRequestService(reactivationRequestRepository, enrollmentRepository,
				enrollmentExpiryService, accessGuard, permissionCheckService, auditLogApi, tenantContext);
		Enrollment enrollment = Enrollment.fromConfirmedPayment(TENANT_ID, STUDENT_ID, COURSE_ID,
				UUID.randomUUID(), null);
		when(enrollmentRepository.findById(ENROLLMENT_ID)).thenReturn(Optional.of(enrollment));
		when(enrollmentExpiryService.resolveAccessState(STUDENT_ID, COURSE_ID))
			.thenReturn(EnrollmentAccessState.expired(ENROLLMENT_ID, null, true));
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
		when(reactivationRequestRepository.save(any(ReactivationRequest.class)))
			.thenAnswer(inv -> inv.getArgument(0));
		AuthenticatedPrincipalHolder.set(new AuthenticatedPrincipal(STUDENT_ID, TENANT_ID, "STUDENT", UUID.randomUUID()));
	}

	@AfterEach
	void clearPrincipal() {
		AuthenticatedPrincipalHolder.clear();
	}

	@Test
	void submitIsRejectedWhileASubmittedRequestAlreadyExists() {
		when(reactivationRequestRepository.findLiveByEnrollmentId(ENROLLMENT_ID))
			.thenReturn(Optional.of(new ReactivationRequest(TENANT_ID, ENROLLMENT_ID, STUDENT_ID)));

		assertThatThrownBy(() -> service.submit(ENROLLMENT_ID)).isInstanceOf(ConflictException.class);
	}

	/**
	 * The bug-fix case: before the fix, only a {@code SUBMITTED} request
	 * blocked a new submission - an {@code APPROVED}-but-unfulfilled request
	 * did not, letting two live reactivation attempts exist for the same
	 * enrollment at once.
	 */
	@Test
	void submitIsRejectedWhileAnApprovedUnfulfilledRequestAlreadyExists() {
		ReactivationRequest approvedUnfulfilled = new ReactivationRequest(TENANT_ID, ENROLLMENT_ID, STUDENT_ID);
		approvedUnfulfilled.approve(UUID.randomUUID(), java.time.Instant.now());
		when(reactivationRequestRepository.findLiveByEnrollmentId(ENROLLMENT_ID))
			.thenReturn(Optional.of(approvedUnfulfilled));

		assertThatThrownBy(() -> service.submit(ENROLLMENT_ID)).isInstanceOf(ConflictException.class);
	}

	/**
	 * An {@code APPROVED} request that has ALREADY been linked to an order
	 * (fulfilled) does NOT block a new submission - see {@link
	 * ReactivationRequestRepository#findLiveByEnrollmentId} for why this is
	 * the correct rule, not an oversight: {@code findLiveByEnrollmentId}
	 * itself never matches a fulfilled request, so the repository call
	 * simply reports "no live request" here.
	 */
	@Test
	void submitSucceedsWhenTheOnlyExistingRequestIsAnApprovedAndAlreadyFulfilledOne() {
		when(reactivationRequestRepository.findLiveByEnrollmentId(ENROLLMENT_ID)).thenReturn(Optional.empty());

		assertThatCode(() -> service.submit(ENROLLMENT_ID)).doesNotThrowAnyException();
	}

	@Test
	void submitSucceedsWhenNoRequestExistsAtAllForThisEnrollment() {
		when(reactivationRequestRepository.findLiveByEnrollmentId(ENROLLMENT_ID)).thenReturn(Optional.empty());

		var view = service.submit(ENROLLMENT_ID);

		assertThat(view.status()).isEqualTo(ReactivationRequestStatus.SUBMITTED);
	}

	/**
	 * MVP-012 review finding H6: {@code submit}'s race-recovery {@code catch
	 * (DataIntegrityViolationException ex)} block - the genuine-concurrency
	 * backstop behind {@code uq_reactivation_request_tenant_enrollment_open}
	 * (V22) for two callers that both pass the unlocked {@code
	 * findLiveByEnrollmentId} pre-check at (effectively) the same time - had
	 * zero coverage. Mirrors {@code
	 * ReactivationTransactionServiceTest}'s own sibling race-loss tests:
	 * stubs the save itself to throw the exact exception the unique index
	 * would raise, and asserts it is translated into the same {@link
	 * ConflictException} the friendly pre-check above already throws.
	 */
	@Test
	void submitTranslatesADataIntegrityViolationFromALostRaceIntoAConflictException() {
		when(reactivationRequestRepository.findLiveByEnrollmentId(ENROLLMENT_ID)).thenReturn(Optional.empty());
		when(reactivationRequestRepository.save(any(ReactivationRequest.class))).thenThrow(
				new org.springframework.dao.DataIntegrityViolationException(
						"uq_reactivation_request_tenant_enrollment_open violated"));

		assertThatThrownBy(() -> service.submit(ENROLLMENT_ID)).isInstanceOf(ConflictException.class)
			.hasMessageContaining("A reactivation request is already pending for this enrollment");
	}

}
