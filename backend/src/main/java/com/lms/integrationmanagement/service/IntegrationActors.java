package com.lms.integrationmanagement.service;

import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;

/** Who is calling the integrations endpoints (MEDIA-INT-01). */
final class IntegrationActors {

	/** Teacher self-service is ownership-gated, not a DomainArea grant (plan §5). */
	private static final Set<String> TEACHER_ROLES = Set.of("TEACHER", "TEACHER_ASSISTANT");

	private IntegrationActors() {
	}

	static UUID currentUserId() {
		return AuthenticatedPrincipalHolder.get().userId();
	}

	/** @throws AccessDeniedException (403) for any non-teacher caller */
	static UUID requireTeacher() {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		if (principal == null || !TEACHER_ROLES.contains(principal.role())) {
			throw new AccessDeniedException("Only teachers can manage their own integrations");
		}
		return principal.userId();
	}

}
