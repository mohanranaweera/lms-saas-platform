package com.lms.contentmanagement.api;

import java.util.UUID;

/**
 * KLASS-T4b: a student-visible material of a lesson, as listed by the lesson
 * launch. Metadata only - never a storage key, external URL or note content;
 * the resource itself is opened through the lesson-guarded material download /
 * playback endpoints.
 *
 * @param materialType {@code MaterialType} name (e.g. {@code PDF}, {@code VIDEO}, {@code LINK}).
 */
public record LessonMaterialSummary(UUID materialId, String title, String materialType) {

	public boolean isVideo() {
		return "VIDEO".equals(materialType) || "RECORDING".equals(materialType);
	}

}
