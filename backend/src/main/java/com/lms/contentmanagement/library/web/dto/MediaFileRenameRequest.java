package com.lms.contentmanagement.library.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code PATCH /api/v1/media-files/{id}}: the new display title. */
public record MediaFileRenameRequest(@NotBlank @Size(max = 255) String title) {

}
