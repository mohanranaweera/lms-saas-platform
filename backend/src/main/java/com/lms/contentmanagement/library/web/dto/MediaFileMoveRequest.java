package com.lms.contentmanagement.library.web.dto;

import java.util.UUID;

/** {@code POST /api/v1/media-files/{id}/move} - {@code folderId} {@code null} = Unfiled (MEDIA-FOLDERS-01). */
public record MediaFileMoveRequest(UUID folderId) {

}
