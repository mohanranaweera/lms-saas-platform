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
 */
public record ParsedToken(UUID subject, UUID tenantId, String role, UUID sessionId, Instant expiresAt) {

	public boolean isPlatformAdmin() {
		return tenantId == null;
	}

}
