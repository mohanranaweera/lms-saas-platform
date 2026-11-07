package com.lms.enrollmentmanagement.api;

import java.util.Set;
import java.util.UUID;

/**
 * Free course participation (ADR-028) for callers outside enrollment-management.
 * Tenant identity always comes from the resolved request context.
 */
public interface CourseParticipationApi {

	/**
	 * STU-MGMT-02: staff enrolling a student they manage into a course of this tenant
	 * (create / edit student form). Creates the same free {@code course_participation}
	 * row as a self-enrollment (source {@code STAFF_GRANT}), idempotently, audited with
	 * the staff actor. Never creates an order, payment, ledger entry or paid entitlement.
	 * The caller authorizes the staff action (STUDENTS CREATE_EDIT) and owns the student.
	 *
	 * @return {@code true} if a participation row was created now
	 * @throws com.lms.common.error.NotFoundException the course is unknown in this
	 * tenant, archived or still a draft
	 */
	boolean enrollOnBehalf(UUID studentUserId, UUID courseId);

	/** The ids of the courses the student participates in (this tenant). */
	Set<UUID> findParticipatingCourseIds(UUID studentUserId);

}
