package com.lms.enrollmentmanagement.service;

import com.lms.common.tenant.TenantContext;
import com.lms.enrollmentmanagement.api.ReactivationLinkingApi;
import com.lms.enrollmentmanagement.domain.Enrollment;
import com.lms.enrollmentmanagement.domain.ReactivationRequest;
import com.lms.enrollmentmanagement.domain.ReactivationRequestStatus;
import com.lms.enrollmentmanagement.repository.EnrollmentRepository;
import com.lms.enrollmentmanagement.repository.ReactivationRequestRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements {@link ReactivationLinkingApi} - see that interface's javadoc.
 * Uses {@link ReactivationRequestRepository#findApprovedUnfulfilledByEnrollmentIdForUpdate}
 * (a {@code PESSIMISTIC_WRITE}-locked read), NOT the unlocked {@code
 * findApprovedUnfulfilledByEnrollmentId} - bug fix, MVP-012 review: the
 * unlocked finder let two concurrent {@code OrderService#createOrder} calls
 * both read {@code newOrderId == null}, both proceed, and race to write
 * {@code newOrderId}, with the second silently overwriting the first (no
 * {@code @Version} column on this row). The lock makes the second concurrent
 * caller block until the first commits, then correctly observe "already
 * fulfilled" and throw.
 */
@Service
public class ReactivationLinkingApiImpl implements ReactivationLinkingApi {

	private final EnrollmentRepository enrollmentRepository;

	private final ReactivationRequestRepository reactivationRequestRepository;

	private final TenantContext tenantContext;

	public ReactivationLinkingApiImpl(EnrollmentRepository enrollmentRepository,
			ReactivationRequestRepository reactivationRequestRepository, TenantContext tenantContext) {
		this.enrollmentRepository = enrollmentRepository;
		this.reactivationRequestRepository = reactivationRequestRepository;
		this.tenantContext = tenantContext;
	}

	@Override
	@Transactional
	public void linkApprovedRequestToNewOrder(UUID studentId, UUID courseId, UUID newOrderId) {
		Enrollment enrollment = enrollmentRepository.findCurrentByStudentIdAndCourseId(studentId, courseId)
			.orElseThrow(() -> new IllegalStateException("No current enrollment exists for student " + studentId
					+ " / course " + courseId + " - cannot link a reactivation request"));
		ReactivationRequest request = reactivationRequestRepository
			.findApprovedUnfulfilledByEnrollmentIdForUpdate(enrollment.getId(), tenantContext.getTenantId(),
					ReactivationRequestStatus.APPROVED)
			.orElseThrow(() -> new IllegalStateException(
					"No APPROVED, unfulfilled reactivation request exists for enrollment " + enrollment.getId()));
		request.linkNewOrder(newOrderId);
		reactivationRequestRepository.save(request);
	}

}
