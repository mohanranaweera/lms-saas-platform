package com.lms.contentmanagement.library.web.dto;

import java.util.UUID;

/** A member option (uploader filter, folder share / transfer candidate): id and display name only. */
public record MediaMemberResponse(UUID userId, String name) {

}
