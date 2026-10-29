package com.lms.tenantmanagement.web.dto;

import com.lms.tenantmanagement.api.TenantAssetType;
import java.time.Instant;
import java.util.UUID;

public record TenantAssetResponse(UUID id, TenantAssetType assetType, String contentType, long sizeBytes,
		Instant createdAt, String previewUrl, Instant previewExpiresAt) {
}
