package com.lms.enrollmentmanagement.service;

import com.lms.enrollmentmanagement.api.RegistrationReportEnrollmentApi;
import com.lms.enrollmentmanagement.domain.CourseParticipation;
import com.lms.enrollmentmanagement.repository.CourseParticipationRepository;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Current, tenant-scoped course associations for Registration Report. */
@Service
public class RegistrationReportEnrollmentService implements RegistrationReportEnrollmentApi {
	private final CourseParticipationRepository repository;
	public RegistrationReportEnrollmentService(CourseParticipationRepository repository) { this.repository = repository; }
	@Override
	@Transactional(readOnly = true)
	public Map<UUID, Set<UUID>> findCurrentCourseIdsByStudentIds(Collection<UUID> studentIds, int maxFacts) {
		if (studentIds == null || studentIds.isEmpty()) return Map.of();
		Set<UUID> ids = new LinkedHashSet<>(studentIds);
		Map<UUID, Set<UUID>> result = new LinkedHashMap<>();
		repository.findAll((root, query, cb) -> root.get("studentId").in(ids)).stream()
			.sorted(java.util.Comparator.comparing(CourseParticipation::getStudentId).thenComparing(CourseParticipation::getCourseId))
			.limit(Math.max(1L, (long) maxFacts + 1))
			.forEach(row -> result.computeIfAbsent(row.getStudentId(), ignored -> new LinkedHashSet<>()).add(row.getCourseId()));
		return result;
	}
}
