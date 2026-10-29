package com.lms.tenantmanagement.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import com.lms.tenantmanagement.api.TenantAssetType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "tenant_asset")
public class TenantAsset extends Auditable implements TenantOwned {
	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;
	@Enumerated(EnumType.STRING)
	@Column(name = "asset_type", nullable = false, updatable = false, length = 40)
	private TenantAssetType assetType;
	@Column(name = "object_key", nullable = false, updatable = false, length = 1024)
	private String objectKey;
	@Column(name = "content_type", nullable = false, updatable = false, length = 100)
	private String contentType;
	@Column(name = "size_bytes", nullable = false, updatable = false)
	private Long sizeBytes;
	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "sha256", nullable = false, updatable = false, length = 64)
	private String sha256;
	@Column(name = "uploaded_by", nullable = false, updatable = false)
	private UUID uploadedBy;
	@Column(name = "deleted_at")
	private Instant deletedAt;
	@Column(name = "deleted_by")
	private UUID deletedBy;

	protected TenantAsset() {
	}

	public TenantAsset(UUID tenantId, TenantAssetType assetType, String objectKey, String contentType,
			long sizeBytes, String sha256, UUID uploadedBy) {
		this.tenantId = tenantId;
		this.assetType = assetType;
		this.objectKey = objectKey;
		this.contentType = contentType;
		this.sizeBytes = sizeBytes;
		this.sha256 = sha256;
		this.uploadedBy = uploadedBy;
	}

	@Override
	public UUID getTenantId() { return tenantId; }
	@Override
	public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
	public TenantAssetType getAssetType() { return assetType; }
	public UUID getId() { return super.getId(); }
	public Instant getCreatedAt() { return super.getCreatedAt(); }
	public String getObjectKey() { return objectKey; }
	public String getContentType() { return contentType; }
	public Long getSizeBytes() { return sizeBytes; }
	public String getSha256() { return sha256; }
	public UUID getUploadedBy() { return uploadedBy; }
	public boolean isDeleted() { return deletedAt != null; }
	public void markDeleted(UUID actorId, Instant when) { deletedBy = actorId; deletedAt = when; }
}
