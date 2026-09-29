package com.lms.integrationmanagement.api;

import java.util.UUID;

/**
 * Who is joining a live class (MEDIA-INT-01). Built server-side by {@code
 * live-class-management} from its own entitlement decision and the user's
 * platform profile - never from client input.
 *
 * @param host {@code true} only for the course-owning teacher, who receives
 * the Zoom host start link; everyone else is registered as an attendee
 */
public record MeetingParticipant(UUID userId, boolean host, String firstName, String lastName, String email) {

	@Override
	public String toString() {
		return "MeetingParticipant[userId=" + userId + ", host=" + host + "]";
	}

}
