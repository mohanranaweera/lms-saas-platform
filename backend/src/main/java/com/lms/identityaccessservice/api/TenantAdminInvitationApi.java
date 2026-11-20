package com.lms.identityaccessservice.api;

import java.time.Instant;
import java.util.UUID;

/**
 * AUTH-PWD-01 / ADR-020: a tenant's first Tenant Admin, for tenant-management
 * (Platform Admin actions). The target tenant is always the Platform Admin's
 * path variable, never client-body input; implementations run under an
 * explicitly set {@code TenantContext} for that tenant.
 */
public interface TenantAdminInvitationApi {

	/**
	 * Creates the tenant's first Tenant Admin as an INVITED account and queues
	 * its account-setup email. Joins the caller's transaction (tenant approval).
	 * @throws com.lms.common.error.ConflictException the tenant already has a Tenant Admin, or the email is taken
	 */
	FirstAdminView inviteFirstAdmin(UUID tenantId, String email, UUID actorId);

	/**
	 * NONE: invite (with {@code email}, or the given fallback). INVITED: resend,
	 * correcting the email when a different one is given. ACTIVE/SUSPENDED: 409.
	 * @throws com.lms.common.error.TooManyRequestsException more than one send per minute
	 */
	FirstAdminView inviteOrResend(UUID tenantId, String email, String fallbackEmail, UUID actorId);

	FirstAdminView firstAdmin(UUID tenantId);

	/** @param status NONE | INVITED | ACTIVE | SUSPENDED; other fields null when NONE */
	record FirstAdminView(String status, String emailMasked, Instant invitedAt, Instant inviteExpiresAt) {

	}

}
