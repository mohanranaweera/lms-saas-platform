package com.lms.liveclassmanagement.service;

import com.lms.liveclassmanagement.api.ClassSessionLookupApi;
import com.lms.liveclassmanagement.api.ClassSessionSummary;
import com.lms.liveclassmanagement.domain.ClassSession;
import com.lms.liveclassmanagement.repository.ClassSessionRepository;
import java.util.Collection;
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

	private static ClassSessionSummary toSummary(ClassSession session) {
		return new ClassSessionSummary(session.getId(), session.getCourseId(), session.getTeacherId(),
				session.getLessonId(), session.getTitle(), session.getScheduledStart(), session.getScheduledEnd(),
				session.getStatus().name());
	}

}
