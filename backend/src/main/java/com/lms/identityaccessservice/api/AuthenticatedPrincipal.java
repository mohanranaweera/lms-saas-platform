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
 */
public record AuthenticatedPrincipal(UUID userId, UUID tenantId, String role, UUID sessionId) {

}
