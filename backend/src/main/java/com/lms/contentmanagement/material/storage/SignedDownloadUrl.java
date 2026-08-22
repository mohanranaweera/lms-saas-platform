package com.lms.contentmanagement.material.storage;

import java.time.Instant;

public record SignedDownloadUrl(String url, Instant expiresAt) {

}
