package com.lms.enrollmentmanagement.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Tenant-scoped, read-only enrollment population for the Unpaid Students Report.
 * The population is current free course participation, not paid access or an
 * entitlement check. Tenant identity comes only from the resolved request
 * context; callers never provide it.
 */
public interface UnpaidStudentsEnrollmentApi {

	List<ParticipationFact> findCurrentCourseParticipants(UUID courseId, int maxFacts);

	record ParticipationFact(UUID studentId, Instant enrolledAt) {

	}

}
