package com.lms.enrollmentmanagement.api;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The single server-side lesson access evaluator (KLASS-T4b, D04/D05/D13,
 * ADR-028). Used by the content tree, the live-class schedule, lesson launch
 * and every protected leaf resource (material download, video playback, AOC
 * Player exchange/heartbeat, class-session join/recording).
 *
 * <p>Enrollment ({@code course_participation}, or a current legacy {@code
 * enrollment}, or participation in a parent course that links this course)
 * only grants navigation; protected content additionally needs the lesson's
 * payment policy to be satisfied: {@code FREE} - nothing; {@code
 * COVERED_BY_CLASS_FEES} - a FREE-priced course, legacy course-wide paid access
 * (the enrollment window, extensions and grace), a MONTHLY entitlement for the
 * lesson's explicit billing month, or a MODULE entitlement for its module;
 * {@code PAID} - its own LESSON entitlement only. An entitlement counts while
 * not revoked and {@code valid_until} is null or in the future. Lessons of a
 * linked SOURCE course are always evaluated with the source course's rules and
 * entitlements, never the parent's.
 *
 * <p>Tenant identity comes only from {@code TenantContext}; the student is the
 * authenticated caller, except for the explicit-device player variant whose
 * student id comes from a server-held row. Batch evaluation uses a constant
 * number of queries per course.
 */
public interface LessonAccessApi {

	/** Decisions for the calling student, keyed by lesson id (unknown ids map to UNAVAILABLE). */
	Map<UUID, LessonAccessDecision> evaluate(UUID courseId, Collection<UUID> lessonIds);

	/**
	 * Guard entry point for the calling student. {@code UNAVAILABLE} and {@code
	 * NOT_ENROLLED} throw {@code notVisible} (each domain keeps its own
	 * anti-enumeration 404); {@code DEVICE_NOT_AUTHORIZED} throws {@code
	 * DeviceNotAuthorizedException} (403); every other denial throws {@link
	 * LessonAccessDeniedException} (403 {@code {code, access}}).
	 */
	LessonAccessDecision requireLessonAccess(UUID courseId, UUID lessonId,
			Supplier<? extends RuntimeException> notVisible);

	/**
	 * The same rule for the AOC Player exchange/heartbeat: no signed-in student,
	 * an explicit device taken from a server-held row (a missing or revoked
	 * device always denies). Deliberately not transactional (callers catch the
	 * denial inside their own transaction to revoke the session).
	 */
	LessonAccessDecision requireLessonAccessOnDevice(UUID studentId, UUID courseId, UUID lessonId, UUID deviceId,
			Supplier<? extends RuntimeException> notVisible);

	/**
	 * Course-level navigation check for the calling student: participation in
	 * the course, a current legacy enrollment, or participation in a parent
	 * course linking it (D13). Grants metadata only.
	 */
	boolean isCallerParticipant(UUID courseId);

}
