package com.lms.enrollmentmanagement.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Read shape returned by {@link EnrollmentAccessApi#resolveAccessState(UUID, UUID)}.
 *
 * <p>Deliberately NOT wrapped in {@code Optional} - the plan's own initial API sketch
 * (§9) showed {@code Optional<EnrollmentAccessState>}, but {@link
 * EnrollmentAccessStateType#NEVER_ENROLLED} is itself a normal, valid response state
 * (plan §10's own API contract table lists it as a plain response value, not a 404), not
 * an absence of data - an {@code Optional.empty()} return would conflate "this student
 * has never enrolled in this course" (a legitimate answer) with "resolution failed" (it
 * never does; every {@code (studentId, courseId)} pair resolves to exactly one of the
 * three {@link EnrollmentAccessStateType} values). Returning a plain, always-present
 * record avoids that ambiguity and matches the endpoint contract in plan §10 directly.
 *
 * @param state the resolved state - always present.
 * @param enrollmentId the id of the current/most-recent {@code enrollment} row, or
 * {@code null} if {@code state == NEVER_ENROLLED} (no row exists at all).
 * @param accessExpiresAt the current row's access window, or {@code null} for lifetime
 * access or when {@code state == NEVER_ENROLLED}.
 * @param canRequestReactivation {@code true} only when {@code state == EXPIRED} and no
 * open ({@code SUBMITTED}) reactivation request already exists for this enrollment;
 * always {@code false} for {@code ACTIVE}/{@code NEVER_ENROLLED}.
 */
public record EnrollmentAccessState(EnrollmentAccessStateType state, UUID enrollmentId, Instant accessExpiresAt,
		boolean canRequestReactivation) {

	public static EnrollmentAccessState neverEnrolled() {
		return new EnrollmentAccessState(EnrollmentAccessStateType.NEVER_ENROLLED, null, null, false);
	}

	public static EnrollmentAccessState active(UUID enrollmentId, Instant accessExpiresAt) {
		return new EnrollmentAccessState(EnrollmentAccessStateType.ACTIVE, enrollmentId, accessExpiresAt, false);
	}

	public static EnrollmentAccessState expired(UUID enrollmentId, Instant accessExpiresAt,
			boolean canRequestReactivation) {
		return new EnrollmentAccessState(EnrollmentAccessStateType.EXPIRED, enrollmentId, accessExpiresAt,
				canRequestReactivation);
	}

}
