package com.lms.contentmanagement.library.web.dto;

/** {@code PATCH /api/v1/media-folders/{id}}; {@code expectedVersion} optional (409 MEDIA_FOLDER_STALE on mismatch). */
public record MediaFolderRenameRequest(String name, Long expectedVersion) {

}
