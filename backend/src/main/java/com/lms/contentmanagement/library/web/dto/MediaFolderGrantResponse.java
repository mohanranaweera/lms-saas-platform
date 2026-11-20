package com.lms.contentmanagement.library.web.dto;

import com.lms.contentmanagement.library.domain.MediaFolderAccessLevel;
import java.time.Instant;
import java.util.UUID;

/** One grant of a folder (shown to callers who may share it). */
public record MediaFolderGrantResponse(UUID userId, String name, MediaFolderAccessLevel accessLevel,
		Instant grantedAt) {

}
