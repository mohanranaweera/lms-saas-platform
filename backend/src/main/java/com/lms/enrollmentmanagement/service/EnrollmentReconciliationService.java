package com.lms.enrollmentmanagement.service;

import com.lms.common.tenant.TenantContextHolder;
import com.lms.enrollmentmanagement.api.EnrollmentReconciliationApi;
import com.lms.enrollmentmanagement.api.OrphanedEnrollmentEvidence;
import com.lms.enrollmentmanagement.domain.Enrollment;
import com.lms.enrollmentmanagement.repository.EnrollmentRepository;
import com.lms.paymentmanagement.api.PaymentStatusApi;
import com.lms.paymentmanagement.api.SlipStatusApi;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements {@link EnrollmentReconciliationApi} - see that interface's
 * javadoc for the full rationale and why this lives here, not in {@code
 * payment-management}. Batch-reads this module's OWN {@code enrollment} rows
 * (via {@link EnrollmentRepository#findAllWithActivationEvidenceAcrossTenants()})
 * and cross-checks each one's evidence id against its actual terminal status
 * one row at a time via the existing, already-approved, single-id {@link
 * PaymentStatusApi}/{@link SlipStatusApi} - never a raw SQL statement
 * reaching into {@code payment-management}'s tables directly.
 *
 * <p>{@link PaymentStatusApi#isConfirmedForCurrentTenant(java.util.UUID)}/
 * {@link SlipStatusApi#isApprovedForCurrentTenant(java.util.UUID)} both
 * resolve tenant identity from {@link com.lms.common.tenant.TenantContext} -
 * since this diagnostic is deliberately cross-tenant (see interface javadoc),
 * each row's check runs with {@link TenantContextHolder} explicitly set to
 * THAT row's own {@code tenantId} for the duration of the call, then cleared
 * in a {@code finally} block - mirroring {@code PaymentConfirmationService
 * #confirmByGatewayReference}'s established set-in-try/clear-in-finally
 * pattern for a system-driven call path with no ambient request-scoped
 * tenant context of its own.
 */
@Service
@Transactional(readOnly = true)
public class EnrollmentReconciliationService implements EnrollmentReconciliationApi {

	private final EnrollmentRepository enrollmentRepository;

	private final PaymentStatusApi paymentStatusApi;

	private final SlipStatusApi slipStatusApi;

	public EnrollmentReconciliationService(EnrollmentRepository enrollmentRepository,
			PaymentStatusApi paymentStatusApi, SlipStatusApi slipStatusApi) {
		this.enrollmentRepository = enrollmentRepository;
		this.paymentStatusApi = paymentStatusApi;
		this.slipStatusApi = slipStatusApi;
	}

	@Override
	public List<OrphanedEnrollmentEvidence> findEnrollmentsWithUnconfirmedActivationEvidenceAcrossTenants() {
		List<OrphanedEnrollmentEvidence> results = new ArrayList<>();
		for (Enrollment enrollment : enrollmentRepository.findAllWithActivationEvidenceAcrossTenants()) {
			OrphanedEnrollmentEvidence flagged = checkEvidence(enrollment);
			if (flagged != null) {
				results.add(flagged);
			}
		}
		return results;
	}

	private OrphanedEnrollmentEvidence checkEvidence(Enrollment enrollment) {
		try {
			TenantContextHolder.set(enrollment.getTenantId());
			if (enrollment.getActivatingPaymentId() != null
					&& !paymentStatusApi.isConfirmedForCurrentTenant(enrollment.getActivatingPaymentId())) {
				return new OrphanedEnrollmentEvidence(enrollment.getTenantId(), enrollment.getId(),
						enrollment.getActivatingPaymentId(), null,
						"activating_payment_id " + enrollment.getActivatingPaymentId()
								+ " does not correspond to a CONFIRMED payment for tenant " + enrollment.getTenantId());
			}
			if (enrollment.getActivatingSlipId() != null
					&& !slipStatusApi.isApprovedForCurrentTenant(enrollment.getActivatingSlipId())) {
				return new OrphanedEnrollmentEvidence(enrollment.getTenantId(), enrollment.getId(), null,
						enrollment.getActivatingSlipId(),
						"activating_slip_id " + enrollment.getActivatingSlipId()
								+ " does not correspond to an APPROVED payment_slip for tenant "
								+ enrollment.getTenantId());
			}
			return null;
		}
		finally {
			TenantContextHolder.clear();
		}
	}

}
