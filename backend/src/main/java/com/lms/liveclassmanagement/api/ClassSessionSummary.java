package com.lms.liveclassmanagement.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Minimal cross-module read projection of a {@code class_session} row -
 * never the {@code ClassSession} JPA entity (per {@code
 * .claude/rules/architecture.md}). {@code status} is the lifecycle name
 * ({@code SCHEDULED}/{@code LIVE}/{@code COMPLETED}/{@code CANCELLED}) as a
 * plain string so consumers are not coupled to this module's domain enum.
 */
public record ClassSessionSummary(UUID id, UUID courseId, UUID teacherId, UUID lessonId, String title,
		Instant scheduledStart, Instant scheduledEnd, String status) {

	public static final String STATUS_SCHEDULED = "SCHEDULED";

	public static final String STATUS_LIVE = "LIVE";

	public static final String STATUS_COMPLETED = "COMPLETED";

	public static final String STATUS_CANCELLED = "CANCELLED";

}
