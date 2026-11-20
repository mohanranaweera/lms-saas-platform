package com.lms.contentmanagement.library.web.dto;

import java.time.Instant;

/** {@code GET /api/v1/media-files/{id}/open-url}: a short-lived (5-minute) signed inline URL. */
public record MediaFileOpenUrlResponse(String url, Instant expiresAt) {

}
