package com.lms.liveclassmanagement.api;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Narrow, read-only cross-module surface over {@code class_session} (Wave 8)
 * - consumed by {@code attendance-management}, which uses a class session as
 * its teaching-session context but may never import this module's {@code
 * domain}/{@code repository} packages (per {@code
 * .claude/rules/architecture.md}).
 *
 * <p>Every method is tenant-scoped by construction (backed by the
 * tenant-aware {@code ClassSessionRepository}): a session id that belongs to
 * another tenant is indistinguishable from a nonexistent one. No method
 * performs any authorization - callers must run their own access check on the
 * returned summary.
 */
public interface ClassSessionLookupApi {

	/** {@link Optional#empty()} if no such session exists in the caller's own tenant. */
	Optional<ClassSessionSummary> findSession(UUID sessionId);

	/**
	 * Batched variant for report enrichment - ids not found in the caller's
	 * tenant are simply absent from the returned map (never an error).
	 */
	Map<UUID, ClassSessionSummary> getSessionSummaries(Collection<UUID> sessionIds);

	/**
	 * MEDIA-INT-01: how many not-yet-ended SCHEDULED/LIVE sessions in the
	 * current tenant are hosted on this provider connection.
	 */
	long countUpcomingSessionsHostedBy(UUID providerConnectionId);

	/**
	 * KLASS-T4b: every session of the caller's tenant attached to {@code
	 * lessonId} ({@code class_session.lesson_id}), by scheduled start. No
	 * authorization - the lesson launch has already evaluated lesson access.
	 */
	java.util.List<ClassSessionSummary> findByLessonId(UUID lessonId);

}
