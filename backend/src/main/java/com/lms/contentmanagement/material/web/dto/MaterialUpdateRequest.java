package com.lms.contentmanagement.material.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.lms.contentmanagement.material.domain.MaterialVisibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Full-resource edit request: title, position, and visibility - upload
 * fields (file, mime type, storage key) are immutable after creation.
 *
 * <p>Wave 5 (plan §3/§4) judgment call: the new per-type fields ({@code
 * materialType}, {@code externalUrl}, {@code noteContent}, {@code
 * videoAssetId}, {@code sessionId}) are deliberately NOT added here - they
 * are create-time-only/immutable, consistent with this existing
 * upload-fields-are-immutable convention (the plan's own §4 API impact
 * section only specifies changes to {@code POST .../materials} and {@code
 * GET .../download-url}, never this {@code PATCH}). {@code maxDownloads}/
 * {@code availableFromAt}/{@code expiryAt} are likewise left out of this
 * edit surface for this wave - not because they could never legitimately
 * change, but because no wave-5-scoped requirement calls for editing them
 * post-creation; a future wave can add that narrowly if a real product need
 * emerges. {@code downloadCount} is response-only (see {@code
 * MaterialResponse}) - server-maintained exclusively via {@code
 * MaterialRepository#incrementDownloadCountIfUnderLimit}, never client-set.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MaterialUpdateRequest(@NotBlank @Size(max = 255) String title, @NotNull @Positive Integer sequence,
		@NotNull MaterialVisibility visibility) {

}
