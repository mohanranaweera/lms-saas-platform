package com.lms.enrollmentmanagement.service;

import com.lms.common.tenant.TenantContext;
import com.lms.enrollmentmanagement.api.EnrollmentActivationApi;
import com.lms.enrollmentmanagement.domain.Enrollment;
import com.lms.enrollmentmanagement.repository.EnrollmentRepository;
import com.lms.paymentmanagement.api.PaymentStatusApi;
import com.lms.paymentmanagement.api.SlipStatusApi;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements {@link EnrollmentActivationApi} - see that interface's javadoc
 * for the two structurally-approved call sites and the defense-in-depth
 * re-verification requirement.
 */
@Service
public class EnrollmentActivationService implements EnrollmentActivationApi {

	private final EnrollmentRepository enrollmentRepository;

	private final PaymentStatusApi paymentStatusApi;

	private final SlipStatusApi slipStatusApi;

	private final TenantContext tenantContext;

	public EnrollmentActivationService(EnrollmentRepository enrollmentRepository, PaymentStatusApi paymentStatusApi,
			SlipStatusApi slipStatusApi, TenantContext tenantContext) {
		this.enrollmentRepository = enrollmentRepository;
		this.paymentStatusApi = paymentStatusApi;
		this.slipStatusApi = slipStatusApi;
		this.tenantContext = tenantContext;
	}

	@Override
	@Transactional
	public void activateFromConfirmedPayment(UUID paymentId, UUID studentId, UUID courseId) {
		if (!paymentStatusApi.isConfirmedForCurrentTenant(paymentId)) {
			throw new IllegalStateException(
					"Refusing to activate enrollment: payment " + paymentId + " is not a CONFIRMED payment in the "
							+ "current tenant context - independent re-verification failed");
		}
		if (enrollmentRepository.existsByStudentIdAndCourseId(studentId, courseId)) {
			// Already activated (e.g. a retried webhook for the same
			// payment, or a genuinely repeated activation attempt) - a
			// no-op, per uq_enrollment_tenant_student_course's idempotency
			// guarantee (V19).
			return;
		}
		try {
			Enrollment enrollment = Enrollment.fromConfirmedPayment(tenantContext.getTenantId(), studentId, courseId,
					paymentId);
			enrollmentRepository.save(enrollment);
		}
		catch (DataIntegrityViolationException ex) {
			// Lost a race against a concurrent duplicate activation attempt
			// for the same (tenant, student, course) - the unique
			// constraint already has a row, which is success, not failure,
			// per the idempotent-activation requirement (plan §8/§15).
		}
	}

	@Override
	@Transactional
	public void activateFromApprovedSlip(UUID slipId, UUID studentId, UUID courseId) {
		if (!slipStatusApi.isApprovedForCurrentTenant(slipId)) {
			throw new IllegalStateException(
					"Refusing to activate enrollment: slip " + slipId + " is not an APPROVED payment slip in the "
							+ "current tenant context - independent re-verification failed");
		}
		if (enrollmentRepository.existsByStudentIdAndCourseId(studentId, courseId)) {
			// Already activated (e.g. a retried approval call, or a
			// genuinely repeated activation attempt) - a no-op, per
			// uq_enrollment_tenant_student_course's idempotency guarantee
			// (V19).
			return;
		}
		try {
			Enrollment enrollment = Enrollment.fromApprovedSlip(tenantContext.getTenantId(), studentId, courseId,
					slipId);
			enrollmentRepository.save(enrollment);
		}
		catch (DataIntegrityViolationException ex) {
			// Lost a race against a concurrent duplicate activation attempt
			// for the same (tenant, student, course) - the unique
			// constraint already has a row, which is success, not failure,
			// per the idempotent-activation requirement (plan §8/§15).
		}
	}

}
