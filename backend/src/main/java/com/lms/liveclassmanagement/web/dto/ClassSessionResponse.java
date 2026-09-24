package com.lms.liveclassmanagement.web.dto;

import com.lms.liveclassmanagement.service.ClassSessionView;
import java.time.Instant;
import java.util.UUID;

/**
 * Never carries {@code providerReference} - see {@link ClassSessionView}'s
 * javadoc for why the opaque meeting-provider id never reaches any client.
 */
public record ClassSessionResponse(UUID id, UUID courseId, UUID teacherId, UUID lessonId, String title,
		String description, Instant scheduledStart, Instant scheduledEnd, String status, String meetingProvider,
		String providerStatus, String providerFailureReason, Instant createdAt, Instant updatedAt) {

	public static ClassSessionResponse from(ClassSessionView view) {
		return new ClassSessionResponse(view.id(), view.courseId(), view.teacherId(), view.lessonId(), view.title(),
				view.description(), view.scheduledStart(), view.scheduledEnd(), view.status().name(),
				view.meetingProvider().name(), view.providerStatus().name(), view.providerFailureReason(),
				view.createdAt(), view.updatedAt());
	}

}
