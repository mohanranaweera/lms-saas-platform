package com.lms.attendancemanagement.service;

import com.lms.liveclassmanagement.api.ClassSessionSummary;
import java.time.Instant;
import java.util.Optional;

/**
 * The server-side session-lifecycle gate for class-session attendance
 * marking (wave-08-plan.md §2 / judgment call §10.3):
 *
 * <ul>
 * <li>{@code LIVE}, {@code COMPLETED} - open;</li>
 * <li>{@code SCHEDULED} - open once {@code scheduledStart} has passed (a
 * teacher who never pressed "Start" can still record a class that
 * happened); closed while the start is still in the future;</li>
 * <li>{@code CANCELLED} - closed.</li>
 * </ul>
 *
 * A pure function of the session summary and "now", so it is unit-testable
 * without a clock mock. The roster read reports the result
 * ({@code markingOpen}); the mark write enforces it (409).
 */
public final class ClassSessionMarkingPolicy {

	static final String CANCELLED_REASON = "Attendance cannot be recorded for a cancelled class session.";

	static final String NOT_STARTED_REASON = "Attendance can be recorded once the class session has started.";

	private ClassSessionMarkingPolicy() {
	}

	/** @return a client-safe reason when marking is closed, or empty when it is open. */
	public static Optional<String> closedReason(ClassSessionSummary session, Instant now) {
		return switch (session.status()) {
			case ClassSessionSummary.STATUS_LIVE, ClassSessionSummary.STATUS_COMPLETED -> Optional.empty();
			case ClassSessionSummary.STATUS_SCHEDULED ->
				session.scheduledStart().isAfter(now) ? Optional.of(NOT_STARTED_REASON) : Optional.empty();
			case ClassSessionSummary.STATUS_CANCELLED -> Optional.of(CANCELLED_REASON);
			default -> Optional.of("Attendance cannot be recorded for this class session.");
		};
	}

}
