package com.lms.enrollmentmanagement.service;

import com.lms.common.tenant.TenantContext;
import com.lms.enrollmentmanagement.api.EnrollmentAccessState;
import com.lms.enrollmentmanagement.domain.Enrollment;
import com.lms.enrollmentmanagement.domain.EnrollmentExpiryEvent;
import com.lms.enrollmentmanagement.domain.EnrollmentExpiryEventType;
import com.lms.enrollmentmanagement.repository.EnrollmentExpiryEventRepository;
import com.lms.enrollmentmanagement.repository.EnrollmentRepository;
import com.lms.enrollmentmanagement.repository.ReactivationRequestRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The live {@code resolveAccessState} computation (plan §7/§9) plus the
 * lazy, idempotent {@code enrollment_expiry_event} write (plan §4.1 step 3),
 * factored out of {@link EnrollmentAccessApiImpl} so it is unit-testable in
 * isolation (plan §18) and directly reusable by {@code
 * ReactivationRequestService#submit} (same-domain internal use, without
 * going through the {@code api} layer).
 */
@Service
public class EnrollmentExpiryService {

	private final EnrollmentRepository enrollmentRepository;

	private final EnrollmentExpiryEventRepository enrollmentExpiryEventRepository;

	private final ReactivationRequestRepository reactivationRequestRepository;

	private final TenantContext tenantContext;

	public EnrollmentExpiryService(EnrollmentRepository enrollmentRepository,
			EnrollmentExpiryEventRepository enrollmentExpiryEventRepository,
			ReactivationRequestRepository reactivationRequestRepository, TenantContext tenantContext) {
		this.enrollmentRepository = enrollmentRepository;
		this.enrollmentExpiryEventRepository = enrollmentExpiryEventRepository;
		this.reactivationRequestRepository = reactivationRequestRepository;
		this.tenantContext = tenantContext;
	}

	/**
	 * @see com.lms.enrollmentmanagement.api.EnrollmentAccessApi#resolveAccessState(UUID, UUID)
	 */
	@Transactional
	public EnrollmentAccessState resolveAccessState(UUID studentId, UUID courseId) {
		Optional<Enrollment> current = enrollmentRepository.findCurrentByStudentIdAndCourseId(studentId, courseId);
		if (current.isEmpty()) {
			return EnrollmentAccessState.neverEnrolled();
		}
		Enrollment enrollment = current.get();
		Instant now = Instant.now();
		if (enrollment.isCurrentlyActive(now)) {
			return EnrollmentAccessState.active(enrollment.getId(), enrollment.getAccessExpiresAt());
		}
		recordExpiryEventIfAbsent(enrollment);
		boolean hasOpenRequest = reactivationRequestRepository.findCurrentOpenByEnrollmentId(enrollment.getId())
			.isPresent();
		return EnrollmentAccessState.expired(enrollment.getId(), enrollment.getAccessExpiresAt(), !hasOpenRequest);
	}

	/**
	 * The first time a live check observes {@code enrollment} as expired,
	 * writes one guarded, idempotent {@code enrollment_expiry_event} row.
	 * Never a mutation of {@code enrollment} itself, and never itself an
	 * audit-log entry (plan §16 - see {@link EnrollmentExpiryEvent}'s
	 * javadoc).
	 */
	private void recordExpiryEventIfAbsent(Enrollment enrollment) {
		if (enrollmentExpiryEventRepository.existsByEnrollmentIdAndEventType(enrollment.getId(),
				EnrollmentExpiryEventType.EXPIRED)) {
			return;
		}
		try {
			EnrollmentExpiryEvent event = EnrollmentExpiryEvent.expired(tenantContext.getTenantId(),
					enrollment.getId());
			// saveAndFlush (not save) is load-bearing (H2 fix, MVP-012
			// review): a plain save() here would not be flushed inside this
			// try block, so a genuine constraint violation from a real
			// concurrent race would surface later (at commit-time
			// auto-flush), outside this try/catch, uncaught. Flushing
			// explicitly guarantees the violation - if any - is raised and
			// caught right here.
			enrollmentExpiryEventRepository.saveAndFlush(event);
		}
		catch (DataIntegrityViolationException ex) {
			// Lost a race against a concurrent read past the same
			// expiry - uq_enrollment_expiry_event_tenant_enrollment_type
			// (V22) already has the row, which is success, not failure.
		}
	}

}
