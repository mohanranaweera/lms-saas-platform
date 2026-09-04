package com.lms.enrollmentmanagement.service;

import com.lms.enrollmentmanagement.api.EnrollmentAccessApi;
import com.lms.enrollmentmanagement.api.EnrollmentAccessState;
import com.lms.enrollmentmanagement.domain.Enrollment;
import com.lms.enrollmentmanagement.repository.EnrollmentRepository;
import com.lms.enrollmentmanagement.repository.ReactivationRequestRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements {@link EnrollmentAccessApi} - the only class other domains are
 * permitted to depend on for enrollment access-currency reads (per {@code
 * .claude/rules/architecture.md}'s "a module may depend only on another
 * module's {@code api} package" rule). Delegates the live computation/lazy
 * expiry-event write to {@link EnrollmentExpiryService}.
 */
@Service
public class EnrollmentAccessApiImpl implements EnrollmentAccessApi {

	private final EnrollmentExpiryService enrollmentExpiryService;

	private final EnrollmentRepository enrollmentRepository;

	private final ReactivationRequestRepository reactivationRequestRepository;

	public EnrollmentAccessApiImpl(EnrollmentExpiryService enrollmentExpiryService,
			EnrollmentRepository enrollmentRepository, ReactivationRequestRepository reactivationRequestRepository) {
		this.enrollmentExpiryService = enrollmentExpiryService;
		this.enrollmentRepository = enrollmentRepository;
		this.reactivationRequestRepository = reactivationRequestRepository;
	}

	@Override
	@Transactional
	public EnrollmentAccessState resolveAccessState(UUID studentId, UUID courseId) {
		return enrollmentExpiryService.resolveAccessState(studentId, courseId);
	}

	@Override
	@Transactional(readOnly = true)
	public boolean hasApprovedUnfulfilledReactivationRequest(UUID studentId, UUID courseId) {
		return enrollmentRepository.findCurrentByStudentIdAndCourseId(studentId, courseId)
			.map(Enrollment::getId)
			.map(enrollmentId -> reactivationRequestRepository.findApprovedUnfulfilledByEnrollmentId(enrollmentId)
				.isPresent())
			.orElse(false);
	}

	@Override
	@Transactional(readOnly = true)
	public List<UUID> listCurrentlyEnrolledStudentIds(UUID courseId) {
		return enrollmentRepository.findAllCurrentByCourseId(courseId).stream().map(Enrollment::getStudentId).toList();
	}

}
