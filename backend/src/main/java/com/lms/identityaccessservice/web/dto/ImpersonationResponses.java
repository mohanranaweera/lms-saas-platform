package com.lms.identityaccessservice.web.dto;

import java.time.Instant;
import java.util.UUID;

/** ADR-033 support-session responses (spec section 5). */
public final class ImpersonationResponses {

	private ImpersonationResponses() {
	}

	/** {@code student{id,indexNumber,fullName}} - id is the student profile id. */
	public record ImpersonatedStudent(UUID id, String indexNumber, String fullName) {
	}

	/** {@code POST /auth/impersonation/token}. */
	public record ImpersonationTokenResponse(String accessToken, Instant expiresAt, ImpersonatedStudent student,
			String actorName) {
	}

	/** {@code GET /auth/impersonation/current}. */
	public record ImpersonationCurrentResponse(UUID sessionId, Instant expiresAt, ImpersonatedStudent student,
			String actorName) {
	}

}
