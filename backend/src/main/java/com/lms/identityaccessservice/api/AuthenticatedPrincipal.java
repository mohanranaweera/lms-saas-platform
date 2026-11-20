package com.lms.identityaccessservice.api;

import java.util.UUID;

/**
 * The authenticated actor for the current request, populated by {@code
 * JwtAuthenticationFilter} only after signature/expiry validation, tenant
 * -claim cross-check, session-validity check, and a live re-read of the
 * user's current role/status all pass. {@code tenantId} is {@code null} for
 * a Platform Admin principal (no tenant exists for that actor); {@code role}
 * is the live value re-read from {@code tenant_user}/{@code
 * platform_admin_user} at request time, never trusted from the JWT claim.
 *
 * <p>ADR-033 support impersonation: {@code actorUserId} is the Tenant Admin who
 * started a "Log as Student" support session; {@code userId}/{@code role} are then
 * the student's and {@code sessionId} is the {@code impersonation_session} id (not a
 * {@code device_session}). {@code actorUserId} is {@code null} for every ordinary
 * session - the two identities are never collapsed into one.
 */
public record AuthenticatedPrincipal(UUID userId, UUID tenantId, String role, UUID sessionId, UUID actorUserId) {

	public AuthenticatedPrincipal(UUID userId, UUID tenantId, String role, UUID sessionId) {
		this(userId, tenantId, role, sessionId, null);
	}

	/** True for an ADR-033 support (impersonation) session. */
	public boolean isImpersonation() {
		return actorUserId != null;
	}

}
