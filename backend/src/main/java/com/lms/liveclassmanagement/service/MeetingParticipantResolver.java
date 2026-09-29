package com.lms.liveclassmanagement.service;

import com.lms.common.error.ConflictException;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.TenantUserSummary;
import com.lms.identityaccessservice.api.UserProvisioningApi;
import com.lms.integrationmanagement.api.MeetingParticipant;
import com.lms.liveclassmanagement.support.LiveClassAccessGuard;
import com.lms.usermanagement.api.StudentContact;
import com.lms.usermanagement.api.StudentLookupApi;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Describes the current caller to the meeting provider (MEDIA-INT-01) -
 * built only from the caller's authenticated identity and platform profile,
 * never from request input. Must be called only after {@code
 * LiveClassAccessGuard#requireEntitlement} has passed, so a Teacher-role
 * caller here is the course-owning teacher (the Zoom host).
 */
@Component
public class MeetingParticipantResolver {

	private final LiveClassAccessGuard accessGuard;

	private final StudentLookupApi studentLookupApi;

	private final UserProvisioningApi userProvisioningApi;

	public MeetingParticipantResolver(LiveClassAccessGuard accessGuard, StudentLookupApi studentLookupApi,
			UserProvisioningApi userProvisioningApi) {
		this.accessGuard = accessGuard;
		this.studentLookupApi = studentLookupApi;
		this.userProvisioningApi = userProvisioningApi;
	}

	/**
	 * @param realProvider whether the session is hosted on a real Zoom
	 * connection - only then are name/email needed (Zoom registration)
	 */
	public MeetingParticipant resolve(boolean realProvider) {
		UUID userId = AuthenticatedPrincipalHolder.get().userId();
		boolean host = accessGuard.isCurrentPrincipalTeacher();
		if (!realProvider || host) {
			return new MeetingParticipant(userId, host, null, null, null);
		}
		// A student's profile gives the best display name; every tenant user
		// has an account email, which is the fallback for both.
		StudentContact contact = accessGuard.isCurrentPrincipalStudent()
				? studentLookupApi.findContact(userId).orElse(null) : null;
		String accountEmail = userProvisioningApi.findTenantUserSummaries(List.of(userId))
			.stream()
			.findFirst()
			.map(TenantUserSummary::email)
			.orElse(null);
		String email = requireEmail((contact != null && contact.email() != null) ? contact.email() : accountEmail);
		if (contact != null && contact.name() != null && !contact.name().isBlank()) {
			String[] names = splitName(contact.name());
			return new MeetingParticipant(userId, false, names[0], names[1], email);
		}
		return new MeetingParticipant(userId, false, email.substring(0, email.indexOf('@')), "", email);
	}

	private static String requireEmail(String email) {
		if (email == null || !email.contains("@")) {
			throw new ConflictException("Your account needs an email address to join Zoom classes.");
		}
		return email;
	}

	/** "Jane Mary Perera" -> ["Jane Mary", "Perera"]; a single word goes in the first name. */
	static String[] splitName(String fullName) {
		String name = (fullName == null) ? "" : fullName.trim();
		int lastSpace = name.lastIndexOf(' ');
		if (lastSpace < 0) {
			return new String[] { name.isEmpty() ? "Student" : name, "" };
		}
		return new String[] { name.substring(0, lastSpace).trim(), name.substring(lastSpace + 1) };
	}

}
