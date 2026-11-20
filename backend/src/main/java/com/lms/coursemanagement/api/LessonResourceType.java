package com.lms.coursemanagement.api;

/**
 * {@code course_lesson.resource_type} (V85, KLASS-T3) - what kind of resource
 * an admin-UI "Session" (a {@code course_lesson}) delivers. Constant names
 * must match V85's {@code resource_type} CHECK constraint exactly.
 *
 * <p>Choosing a type here is metadata only. It does not provision anything
 * with a provider and does not mean the provider is integrated. See
 * {@link LessonResourceTypeCatalog} for each type's support level and for
 * which existing resource (material, video asset, class session) carries
 * the actual content.
 */
public enum LessonResourceType {

	VIMEO_VIDEO,

	/** The Klass "Vidrora Video" slot: our own secure video ({@code video_asset}) in the web player or AOC Player. */
	SECURE_VIDEO,

	DOCUMENT,

	IMAGE,

	ZOOM_LINK,

	ZOOM_MEETING,

	ZOOM_WEBINAR,

	ZOOM_RECORDING,

	VIDEO_LINK,

	GOOGLE_DRIVE_LINK,

	MS_TEAMS_LINK,

	GOOGLE_MEET

}
