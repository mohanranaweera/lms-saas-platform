package com.lms.videoaccessmanagement.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * An uploaded video file (Wave 5, V51, PAR-20-03) - the storage/processing
 * record {@code content-management}'s {@code VIDEO}/{@code RECORDING}
 * material types point at via their opaque {@code videoAssetId}. Owned
 * exclusively by {@code video-access-management}; other domains never
 * import this class, only {@code
 * com.lms.videoaccessmanagement.api.VideoAccessApi}.
 *
 * <p>See {@link VideoAssetStatus}'s javadoc for why {@link
 * VideoAssetStatus#PENDING}/{@link VideoAssetStatus#FAILED} are never
 * actually persisted by this wave's fully-synchronous upload path.
 */
@Entity
@Table(name = "video_asset")
public class VideoAsset extends Auditable implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "storage_object_key", nullable = false, updatable = false)
	private String storageObjectKey;

	@Column(name = "original_filename", nullable = false, updatable = false)
	private String originalFilename;

	@Column(name = "mime_type", nullable = false, updatable = false)
	private String mimeType;

	@Column(name = "size_bytes", nullable = false, updatable = false)
	private Long sizeBytes;

	@Column(name = "duration_seconds")
	private Integer durationSeconds;

	@Column(name = "status", nullable = false)
	private VideoAssetStatus status;

	@Column(name = "uploaded_by", nullable = false, updatable = false)
	private UUID uploadedBy;

	protected VideoAsset() {
	}

	/**
	 * Constructs a {@code READY} asset directly - see class javadoc and
	 * {@code VideoAssetService#upload}'s own javadoc for why this wave never
	 * constructs a transient {@code PENDING} row: the storage call has
	 * already succeeded by the time this constructor runs.
	 */
	public VideoAsset(UUID tenantId, String storageObjectKey, String originalFilename, String mimeType,
			Long sizeBytes, UUID uploadedBy) {
		this.tenantId = tenantId;
		this.storageObjectKey = storageObjectKey;
		this.originalFilename = originalFilename;
		this.mimeType = mimeType;
		this.sizeBytes = sizeBytes;
		this.uploadedBy = uploadedBy;
		this.status = VideoAssetStatus.READY;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public String getStorageObjectKey() {
		return storageObjectKey;
	}

	public String getOriginalFilename() {
		return originalFilename;
	}

	public String getMimeType() {
		return mimeType;
	}

	public Long getSizeBytes() {
		return sizeBytes;
	}

	public Integer getDurationSeconds() {
		return durationSeconds;
	}

	public VideoAssetStatus getStatus() {
		return status;
	}

	public UUID getUploadedBy() {
		return uploadedBy;
	}

}
