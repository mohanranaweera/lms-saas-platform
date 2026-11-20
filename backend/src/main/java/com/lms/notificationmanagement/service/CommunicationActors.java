package com.lms.notificationmanagement.service;

import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import java.util.UUID;

/**
 * The authenticated caller of a communications endpoint (Wave 11), read from
 * the already-validated principal - never from request input.
 */
final class CommunicationActors {

	static final String TEACHER = "TEACHER";

	private CommunicationActors() {
	}

	static UUID currentUserId() {
		return AuthenticatedPrincipalHolder.get().userId();
	}

	static String currentRole() {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		return (principal == null) ? null : principal.role();
	}

	/** Teachers are ownership-scoped (decision D2), not a {@code COMMUNICATIONS} grant. */
	static boolean isTeacher() {
		return TEACHER.equals(currentRole());
	}

}
