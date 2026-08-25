package com.lms.integrationmanagement.api;

import java.io.InputStream;
import java.util.UUID;

public record StoreObjectCommand(UUID tenantId, InputStream content, String detectedMimeType, long sizeBytes,
		String fileName) {

}
