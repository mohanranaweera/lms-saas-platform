package com.lms.contentmanagement.library.web.dto;

import com.lms.contentmanagement.library.service.MediaFolderService.FormerOwnerAccess;
import java.util.UUID;

/** {@code POST /api/v1/media-folders/{id}/transfer}; {@code formerOwnerAccess} defaults to NONE. */
public record MediaFolderTransferRequest(UUID newOwnerId, FormerOwnerAccess formerOwnerAccess,
		Long expectedVersion) {

}
