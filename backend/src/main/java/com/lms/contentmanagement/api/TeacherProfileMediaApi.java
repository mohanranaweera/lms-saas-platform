package com.lms.contentmanagement.api;

import java.util.Optional;
import java.util.UUID;

/** Tenant-scoped read contract for teacher profile images. */
public interface TeacherProfileMediaApi {

	Optional<ProfileImage> findLiveImage(UUID mediaFileId);

	record ProfileImage(UUID id, String thumbnailUrl) {
	}
}
