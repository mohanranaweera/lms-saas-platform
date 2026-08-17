package com.lms.contentmanagement.material.storage;

import java.io.InputStream;
import java.util.UUID;

public record StoreObjectCommand(UUID tenantId, InputStream content, String detectedMimeType, long sizeBytes,
		String fileName) {

}
