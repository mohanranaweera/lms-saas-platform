package com.lms.contentmanagement.library.web.dto;

import java.util.UUID;

/** {@code POST /api/v1/media-folders}. {@code name} is validated by the service (authoritative rule). */
public record MediaFolderCreateRequest(String name, UUID parentId) {

}
