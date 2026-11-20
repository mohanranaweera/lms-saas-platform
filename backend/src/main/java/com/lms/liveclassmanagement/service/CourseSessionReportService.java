package com.lms.liveclassmanagement.service;

import com.lms.liveclassmanagement.api.CourseSessionReportApi;
import com.lms.liveclassmanagement.api.CourseSessionReportApi.SessionFact;
import com.lms.liveclassmanagement.domain.ClassSession;
import com.lms.liveclassmanagement.domain.ClassSessionStatus;
import com.lms.liveclassmanagement.repository.ClassSessionRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** RPT-05 tenant-scoped class-session projection. Authorization remains report-service owned. */
@Service
public class CourseSessionReportService implements CourseSessionReportApi {

	private final ClassSessionRepository repository;

	public CourseSessionReportService(ClassSessionRepository repository) {
		this.repository = repository;
	}

	@Override
	@Transactional(readOnly = true)
	public List<SessionFact> findCourseSessions(UUID courseId, Instant fromInclusive, Instant toExclusive,
			int maxFacts) {
		return repository.findAll((root, query, cb) -> cb.and(cb.equal(root.get("courseId"), courseId),
				cb.notEqual(root.get("status"), ClassSessionStatus.CANCELLED),
				cb.greaterThanOrEqualTo(root.get("scheduledStart"), fromInclusive),
				cb.lessThan(root.get("scheduledStart"), toExclusive)))
			.stream()
			.sorted(java.util.Comparator.comparing(ClassSession::getScheduledStart).thenComparing(ClassSession::getId))
			.limit(Math.max(1L, (long) maxFacts + 1))
			.map(row -> new SessionFact(row.getId(), row.getScheduledStart(), row.getScheduledEnd(), row.getStatus().name()))
			.toList();
	}

}
