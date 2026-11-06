package com.lms.liveclassmanagement.service;

import com.lms.liveclassmanagement.api.ClassSessionLookupApi;
import com.lms.liveclassmanagement.api.ClassSessionSummary;
import com.lms.liveclassmanagement.domain.ClassSession;
import com.lms.liveclassmanagement.repository.ClassSessionRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ClassSessionLookupApi} implementation - a pure tenant-scoped read
 * over {@link ClassSessionRepository} (whose inherited {@code findById}/{@code
 * findAllById} are structurally tenant-filtered by {@code
 * TenantAwareRepositoryImpl}). No authorization here, by design - see the
 * interface javadoc.
 */
@Service
public class ClassSessionLookupService implements ClassSessionLookupApi {

	private final ClassSessionRepository classSessionRepository;

	public ClassSessionLookupService(ClassSessionRepository classSessionRepository) {
		this.classSessionRepository = classSessionRepository;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<ClassSessionSummary> findSession(UUID sessionId) {
		return classSessionRepository.findById(sessionId).map(ClassSessionLookupService::toSummary);
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, ClassSessionSummary> getSessionSummaries(Collection<UUID> sessionIds) {
		if (sessionIds == null || sessionIds.isEmpty()) {
			return Map.of();
		}
		return classSessionRepository.findAllById(Set.copyOf(sessionIds))
			.stream()
			.map(ClassSessionLookupService::toSummary)
			.collect(Collectors.toMap(ClassSessionSummary::id, Function.identity()));
	}

	@Override
	@Transactional(readOnly = true)
	public List<ClassSessionSummary> findByLessonId(UUID lessonId) {
		return classSessionRepository
			.findAll((root, query, cb) -> cb.equal(root.get("lessonId"), lessonId),
					org.springframework.data.domain.Sort.by("scheduledStart", "id"))
			.stream()
			.map(ClassSessionLookupService::toSummary)
			.toList();
	}

	@Override
	@Transactional(readOnly = true)
	public List<ClassSessionSummary> findOverlapping(Collection<UUID> courseIds, Instant from, Instant to) {
		if (courseIds == null || courseIds.isEmpty()) {
			return List.of();
		}
		Set<UUID> ids = Set.copyOf(courseIds);
		return classSessionRepository
			.findAll((root, query, cb) -> cb.and(root.get("courseId").in(ids),
					cb.lessThan(root.get("scheduledStart"), to), cb.greaterThan(root.get("scheduledEnd"), from)),
					org.springframework.data.domain.Sort.by("scheduledStart", "id"))
			.stream()
			.map(ClassSessionLookupService::toSummary)
			.toList();
	}

	private static ClassSessionSummary toSummary(ClassSession session) {
		return new ClassSessionSummary(session.getId(), session.getCourseId(), session.getTeacherId(),
				session.getLessonId(), session.getTitle(), session.getScheduledStart(), session.getScheduledEnd(),
				session.getStatus().name());
	}

	@Override
	@Transactional(readOnly = true)
	public long countUpcomingSessionsHostedBy(UUID providerConnectionId) {
		return classSessionRepository.countUpcomingHostedBy(providerConnectionId, Instant.now());
	}

}
