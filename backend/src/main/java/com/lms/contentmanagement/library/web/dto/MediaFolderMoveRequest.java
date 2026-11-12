package com.lms.contentmanagement.library.web.dto;

import java.util.UUID;

/** {@code POST /api/v1/media-folders/{id}/move}; {@code parentId} {@code null} = root. */
public record MediaFolderMoveRequest(UUID parentId, Long expectedVersion) {

}
