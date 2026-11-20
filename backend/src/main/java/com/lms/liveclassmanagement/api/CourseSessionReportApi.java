package com.lms.liveclassmanagement.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** RPT-05 bounded, tenant-scoped scheduled-session facts. */
public interface CourseSessionReportApi {

	List<SessionFact> findCourseSessions(UUID courseId, Instant fromInclusive, Instant toExclusive, int maxFacts);

	record SessionFact(UUID sessionId, Instant scheduledStart, Instant scheduledEnd, String status) {

	}

}
