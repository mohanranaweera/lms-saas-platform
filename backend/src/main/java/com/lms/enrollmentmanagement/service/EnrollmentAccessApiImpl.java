package com.lms.enrollmentmanagement.service;

import com.lms.enrollmentmanagement.api.EnrollmentAccessApi;
import com.lms.enrollmentmanagement.api.EnrollmentAccessState;
import com.lms.enrollmentmanagement.domain.Enrollment;
import com.lms.enrollmentmanagement.repository.EnrollmentRepository;
import com.lms.enrollmentmanagement.repository.ReactivationRequestRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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

	private final AccessWindowCalculator accessWindowCalculator;

	public EnrollmentAccessApiImpl(EnrollmentExpiryService enrollmentExpiryService,
			EnrollmentRepository enrollmentRepository, ReactivationRequestRepository reactivationRequestRepository,
			AccessWindowCalculator accessWindowCalculator) {
		this.enrollmentExpiryService = enrollmentExpiryService;
		this.enrollmentRepository = enrollmentRepository;
		this.reactivationRequestRepository = reactivationRequestRepository;
		this.accessWindowCalculator = accessWindowCalculator;
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
		return currentlyActive(enrollmentRepository.findAllLineageCurrentByCourseId(courseId)).stream()
			.map(Enrollment::getStudentId)
			.toList();
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, Long> countCurrentlyEnrolledStudents(java.util.Collection<UUID> courseIds) {
		if (courseIds == null || courseIds.isEmpty()) return Map.of();
		Map<UUID, java.util.Set<UUID>> students = new java.util.HashMap<>();
		for (UUID courseId : courseIds) {
			students.put(courseId, new java.util.HashSet<>(listCurrentlyEnrolledStudentIds(courseId)));
		}
		return students.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> (long) e.getValue().size()));
	}

	@Override
	@Transactional(readOnly = true)
	public Set<UUID> listCurrentlyEnrolledCourseIds(UUID studentId) {
		return currentlyActive(enrollmentRepository.findAllCurrentByStudentId(studentId)).stream()
			.map(Enrollment::getCourseId)
			.collect(Collectors.toSet());
	}

	/**
	 * Wave 10: "currently enrolled" uses the same extension + grace window as
	 * {@code resolveAccessState} ({@link AccessWindowCalculator}), not a
	 * separate SQL expiry predicate.
	 */
	private List<Enrollment> currentlyActive(List<Enrollment> lineageCurrent) {
		Instant now = Instant.now();
		Map<UUID, AccessWindowCalculator.AccessWindow> windows = accessWindowCalculator.windowsFor(lineageCurrent);
		return lineageCurrent.stream()
			.filter(enrollment -> accessWindowCalculator.isCurrentlyActive(enrollment, windows.get(enrollment.getId()),
					now))
			.toList();
	}

}
