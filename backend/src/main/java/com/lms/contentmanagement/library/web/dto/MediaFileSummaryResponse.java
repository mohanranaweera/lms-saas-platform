package com.lms.contentmanagement.library.web.dto;

/** Live library counts ({@code GET /api/v1/media-files/summary}). */
public record MediaFileSummaryResponse(long documents, long images, long other, long total) {

}
