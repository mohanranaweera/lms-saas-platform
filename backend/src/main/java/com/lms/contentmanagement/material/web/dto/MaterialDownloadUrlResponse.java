package com.lms.contentmanagement.material.web.dto;

import java.time.Instant;

public record MaterialDownloadUrlResponse(String url, Instant expiresAt) {

}
