package com.lms.contentmanagement.api;

import java.util.Optional;
import java.util.UUID;

/** Tenant-scoped read contract for teacher profile images. */
public interface TeacherProfileMediaApi {

	Optional<ProfileImage> findLiveImage(UUID mediaFileId);

	/**
	 * Removes a teacher photo file that no teacher uses any more (must run in the
	 * caller's transaction). A library file, or one attached to a lesson, is kept.
	 */
	void discardProfileImage(UUID mediaFileId);

	record ProfileImage(UUID id, String thumbnailUrl) {
	}
}
