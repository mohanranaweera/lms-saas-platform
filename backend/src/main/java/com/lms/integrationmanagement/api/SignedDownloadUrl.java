package com.lms.integrationmanagement.api;

import java.time.Instant;

public record SignedDownloadUrl(String url, Instant expiresAt) {

}
