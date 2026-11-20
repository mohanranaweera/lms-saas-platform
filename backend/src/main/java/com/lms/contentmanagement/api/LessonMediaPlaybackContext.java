package com.lms.contentmanagement.api;

import com.lms.contentmanagement.material.domain.MaterialVisibility;
import java.time.Instant;
import java.util.UUID;

/** Safe tenant-scoped LessonMedia projection for playback authorization. */
public record LessonMediaPlaybackContext(UUID materialId, UUID lessonId, UUID courseId, UUID videoAssetId,
		MaterialVisibility visibility, Instant availableFromAt, Instant expiryAt,
		boolean courseOpenToEnrolledStudents) {

}
