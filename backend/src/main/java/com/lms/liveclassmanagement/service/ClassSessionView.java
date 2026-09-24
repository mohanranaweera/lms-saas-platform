package com.lms.liveclassmanagement.service;

import com.lms.liveclassmanagement.domain.ClassSession;
import com.lms.liveclassmanagement.domain.ClassSessionProviderStatus;
import com.lms.liveclassmanagement.domain.ClassSessionStatus;
import com.lms.liveclassmanagement.domain.MeetingProvider;
import java.time.Instant;
import java.util.UUID;

/**
 * Service-layer read shape for a {@code class_session} - deliberately
 * excludes {@code providerReference} (the opaque meeting-provider id is an
 * internal implementation detail, never returned to any client; join access
 * always goes through {@code ClassSessionService#join}, never a raw
 * reference lookup).
 */
public record ClassSessionView(UUID id, UUID courseId, UUID teacherId, UUID lessonId, String title,
		String description, Instant scheduledStart, Instant scheduledEnd, ClassSessionStatus status,
		MeetingProvider meetingProvider, ClassSessionProviderStatus providerStatus, String providerFailureReason,
		Instant createdAt, Instant updatedAt) {

	public static ClassSessionView from(ClassSession session) {
		return new ClassSessionView(session.getId(), session.getCourseId(), session.getTeacherId(),
				session.getLessonId(), session.getTitle(), session.getDescription(), session.getScheduledStart(),
				session.getScheduledEnd(), session.getStatus(), session.getMeetingProvider(),
				session.getProviderStatus(), session.getProviderFailureReason(), session.getCreatedAt(),
				session.getUpdatedAt());
	}

}
