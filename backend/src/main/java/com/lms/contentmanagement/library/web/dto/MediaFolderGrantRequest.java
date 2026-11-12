package com.lms.contentmanagement.library.web.dto;

import com.lms.contentmanagement.library.domain.MediaFolderAccessLevel;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** {@code POST /api/v1/media-folders/{id}/grants} (upsert); {@code accessLevel} defaults to VIEW. */
public record MediaFolderGrantRequest(@NotNull @Size(min = 1, max = 50) List<@NotNull UUID> userIds,
		MediaFolderAccessLevel accessLevel) {

}
