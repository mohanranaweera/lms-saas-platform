package com.lms.identityaccessservice.service;

import java.time.Instant;
import java.util.UUID;

/**
 * Decoded, signature-and-expiry-verified JWT payload. {@code tenantId} is
 * {@code null} for a Platform Admin token (no {@code tenant_id} claim is
 * ever issued for that path). {@code role} is carried here only for
 * completeness of what the token asserts - callers must never treat it as
 * final; {@code JwtAuthenticationFilter} always re-reads the live role from
 * the database.
 *
 * <p>ADR-033: {@code impersonation} is the {@code imp=true} claim and {@code
 * actorUserId} the {@code act} claim of a support-session token; {@code sessionId}
 * is then an {@code impersonation_session} id.
 */
public record ParsedToken(UUID subject, UUID tenantId, String role, UUID sessionId, Instant expiresAt,
		boolean impersonation, UUID actorUserId) {

	public ParsedToken(UUID subject, UUID tenantId, String role, UUID sessionId, Instant expiresAt) {
		this(subject, tenantId, role, sessionId, expiresAt, false, null);
	}

	public boolean isPlatformAdmin() {
		return tenantId == null;
	}

}
