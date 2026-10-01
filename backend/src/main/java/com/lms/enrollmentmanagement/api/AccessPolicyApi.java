package com.lms.enrollmentmanagement.api;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The centralized access policy (Wave 10, master instruction §29,
 * PAR-XC-04): the one place that decides whether a student may open course
 * content now. It combines course entitlement and the billing window
 * ({@code enrollment.access_expires_at}), manual extensions, the grace period
 * (course setting beats tenant setting) and the course-level device limit.
 * Every per-domain guard (materials, video, live classes, course detail,
 * exams) calls this instead of comparing {@code resolveAccessState} itself.
 *
 * <p>Tenant identity comes from {@code TenantContext} only; no method takes a
 * tenant id. The device dimension is evaluated for the CURRENT request
 * session, and only when {@code studentId} is the caller.
 */
public interface AccessPolicyApi {

	CourseAccessDecision evaluateStudentCourseAccess(UUID studentId, UUID courseId);

	/**
	 * The guard entry point for the calling student. NOT_ENROLLED/EXPIRED throw
	 * {@code deniedAsNotFound} (each domain keeps its own anti-enumeration
	 * 404); DEVICE_NOT_AUTHORIZED throws {@code DeviceNotAuthorizedException}
	 * (403) - reached only once enrollment is proven, so it leaks nothing.
	 */
	CourseAccessDecision requireStudentCourseAccess(UUID courseId,
			Supplier<? extends RuntimeException> deniedAsNotFound);

	/** Material/video availability window check (null bounds = open-ended). */
	ContentAvailability evaluateContentWindow(Instant availableFrom, Instant availableUntil);

}
