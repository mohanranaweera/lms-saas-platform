package com.lms.contentmanagement.library.service;

/** Live library file counts per kind for the caller's tenant ({@code GET /api/v1/media-files/summary}). */
public record MediaFileSummaryView(long documents, long images, long other, long total) {

}
