package com.lms.identityaccessservice.api;

import java.time.Instant;
import java.util.UUID;

/**
 * ADR-033 "Log as Student": starts an audited, read-only support session for the
 * calling Tenant Admin. Called by {@code user-management} after it has resolved the
 * target student profile in the caller's tenant (and refused pending/removed
 * students). This side re-checks the {@code STUDENT_IMPERSONATION} permission, refuses
 * nested impersonation and any target account that is not an ACTIVE {@code STUDENT} of
 * the resolved tenant, ends the admin's previous open session (REPLACED), and audits
 * the start. Tenant identity comes only from the resolved request context.
 */
public interface ImpersonationApi {

	/**
	 * @param subjectIndexNumber / subjectFullName display snapshot for the support banner
	 * @throws org.springframework.security.access.AccessDeniedException no permission, or
	 * the caller is itself in a support session
	 * @throws com.lms.common.error.NotFoundException the target account is not a student of
	 * this tenant
	 * @throws com.lms.common.error.ConflictException the target account is not active
	 */
	StartedImpersonation start(UUID subjectUserId, UUID subjectStudentProfileId, String subjectIndexNumber,
			String subjectFullName, String reason);

	/**
	 * @param setCookieHeader the complete {@code Set-Cookie} header value carrying the
	 * one-time handoff secret ({@code lms_impersonation}, HttpOnly, SameSite=Strict,
	 * Path=/api/v1/auth/impersonation). The caller adds it to its response verbatim and
	 * never logs it.
	 */
	record StartedImpersonation(UUID sessionId, Instant expiresAt, String setCookieHeader) {
	}

}
