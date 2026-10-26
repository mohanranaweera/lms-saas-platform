package com.lms.enrollmentmanagement.service;

import com.lms.enrollmentmanagement.api.UnpaidStudentsEnrollmentApi;
import com.lms.enrollmentmanagement.api.UnpaidStudentsEnrollmentApi.ParticipationFact;
import com.lms.enrollmentmanagement.domain.CourseParticipation;
import com.lms.enrollmentmanagement.repository.CourseParticipationRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tenant-scoped course-participation facts for the Unpaid Students Report. */
@Service
public class UnpaidStudentsEnrollmentService implements UnpaidStudentsEnrollmentApi {

	private final CourseParticipationRepository participationRepository;

	public UnpaidStudentsEnrollmentService(CourseParticipationRepository participationRepository) {
		this.participationRepository = participationRepository;
	}

	@Override
	@Transactional(readOnly = true)
	public List<ParticipationFact> findCurrentCourseParticipants(UUID courseId, int maxFacts) {
		return participationRepository
			.findAll((root, query, cb) -> cb.equal(root.get("courseId"), courseId))
			.stream()
			.sorted(java.util.Comparator.comparing(CourseParticipation::getStudentId)
				.thenComparing(CourseParticipation::getEnrolledAt)
				.thenComparing(CourseParticipation::getId))
			.limit(Math.max(1L, (long) maxFacts + 1))
			.map(row -> new ParticipationFact(row.getStudentId(), row.getEnrolledAt()))
			.toList();
	}

}
