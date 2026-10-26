package com.lms.enrollmentmanagement.api;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Tenant-scoped current course-participation facts for Registration Report. */
public interface RegistrationReportEnrollmentApi {

	Map<UUID, Set<UUID>> findCurrentCourseIdsByStudentIds(Collection<UUID> studentIds, int maxFacts);

}
