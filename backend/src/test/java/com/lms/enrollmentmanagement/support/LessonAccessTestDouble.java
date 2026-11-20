package com.lms.enrollmentmanagement.support;

import com.lms.enrollmentmanagement.api.AccessPolicyApi;
import com.lms.enrollmentmanagement.api.CourseAccessDecision;
import com.lms.enrollmentmanagement.api.LessonAccessApi;
import com.lms.enrollmentmanagement.api.LessonAccessDecision;
import com.lms.enrollmentmanagement.api.LessonAccessStatus;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Unit-test stand-in for {@link LessonAccessApi} (KLASS-T4b) that keeps the
 * pre-KLASS guard unit tests meaningful: every lesson simply follows the
 * test's legacy course decision ({@link AccessPolicyApi}, usually {@link
 * AccessPolicyTestDouble}) - ALLOWED stays ALLOWED, a device denial stays a
 * device denial, and no current/expired enrollment maps to NOT_ENROLLED (the
 * guards' anti-enumeration 404). Nobody is a free participant. The real
 * evaluator matrix is covered by {@code LessonAccessRulesTest} and the
 * KLASS-T4b integration tests.
 */
public final class LessonAccessTestDouble implements LessonAccessApi {

	private final AccessPolicyApi accessPolicyApi;

	private LessonAccessTestDouble(AccessPolicyApi accessPolicyApi) {
		this.accessPolicyApi = accessPolicyApi;
	}

	public static LessonAccessApi backedBy(AccessPolicyApi accessPolicyApi) {
		return new LessonAccessTestDouble(accessPolicyApi);
	}

	@Override
	public Map<UUID, LessonAccessDecision> evaluate(UUID courseId, Collection<UUID> lessonIds) {
		return decide(AuthenticatedPrincipalHolder.get().userId(), courseId, lessonIds);
	}

	@Override
	public LessonAccessDecision requireLessonAccess(UUID courseId, UUID lessonId,
			Supplier<? extends RuntimeException> notVisible) {
		return accessPolicyApi.requireStudentCourseAccess(courseId, notVisible) != null
				? decision(courseId, lessonId, LessonAccessStatus.ALLOWED) : null;
	}

	@Override
	public LessonAccessDecision requireLessonAccessOnDevice(UUID studentId, UUID courseId, UUID lessonId,
			UUID deviceId, Supplier<? extends RuntimeException> notVisible) {
		accessPolicyApi.requireStudentCourseAccessOnDevice(studentId, courseId, deviceId, notVisible);
		return decision(courseId, lessonId, LessonAccessStatus.ALLOWED);
	}

	@Override
	public boolean isCallerParticipant(UUID courseId) {
		return false;
	}

	private Map<UUID, LessonAccessDecision> decide(UUID studentId, UUID courseId, Collection<UUID> lessonIds) {
		CourseAccessDecision course = accessPolicyApi.evaluateStudentCourseAccess(studentId, courseId);
		LessonAccessStatus status = switch (course.outcome()) {
			case ALLOWED -> LessonAccessStatus.ALLOWED;
			case DEVICE_NOT_AUTHORIZED -> LessonAccessStatus.DEVICE_NOT_AUTHORIZED;
			case NOT_ENROLLED, EXPIRED -> LessonAccessStatus.NOT_ENROLLED;
		};
		Map<UUID, LessonAccessDecision> result = new LinkedHashMap<>();
		lessonIds.forEach(lessonId -> result.put(lessonId, decision(courseId, lessonId, status)));
		return result;
	}

	private static LessonAccessDecision decision(UUID courseId, UUID lessonId, LessonAccessStatus status) {
		return new LessonAccessDecision(lessonId, courseId, status, LessonAccessDecision.Availability.ALWAYS,
				status == LessonAccessStatus.ALLOWED ? LessonAccessDecision.Payment.COVERED
						: LessonAccessDecision.Payment.REQUIRED,
				null);
	}

}
