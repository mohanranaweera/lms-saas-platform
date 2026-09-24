package com.lms.liveclassmanagement.service;

import com.lms.liveclassmanagement.domain.ClassSessionStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Optional {@code GET /class-sessions} query filters. {@code courseId} is
 * honored for every role, but NEVER used, by itself, to imply access for a
 * Student caller - {@code ClassSessionService#listSessions} always
 * intersects against the caller's own entitlement (enrolled courses for a
 * Student, owned courses for a Teacher) before this filter is applied, per
 * Wave 4 plan §4/§7's "never trusting a client courseId filter to imply
 * access" requirement.
 */
public record ClassSessionListFilter(UUID courseId, ClassSessionStatus status, Instant from, Instant to) {

	public boolean matches(Instant scheduledStart) {
		if (from != null && scheduledStart.isBefore(from)) {
			return false;
		}
		return to == null || !scheduledStart.isAfter(to);
	}

}
