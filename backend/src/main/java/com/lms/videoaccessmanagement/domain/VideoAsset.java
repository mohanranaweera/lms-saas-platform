package com.lms.videoaccessmanagement.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * The provider-neutral MediaAsset (VIDEO Phase 2, VID-ADR-002 - V51 evolved
 * by V70): the record {@code content-management}'s {@code VIDEO}/{@code
 * RECORDING} material types point at via their opaque {@code videoAssetId}.
 * Owned exclusively by {@code video-access-management}; other domains never
 * import this class, only {@code
 * com.lms.videoaccessmanagement.api.VideoAccessApi}.
 *
 * <p>{@link MediaAssetProvider#INTERNAL} rows are V51-era MinIO uploads
 * ({@code storageObjectKey} set, created directly {@code READY}); every other
 * provider hosts the media itself and is reached through the tenant's own
 * provider connection ({@code providerConnectionId}), starting at {@code
 * CREATED}. The provider's own video id is never the LMS key. Status changes
 * go through {@link #transitionTo} so illegal jumps fail before reaching the
 * database.
 */
@Entity
@Table(name = "video_asset")
public class VideoAsset extends Auditable implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Enumerated(EnumType.STRING)
	@Column(name = "provider", nullable = false, updatable = false)
	private MediaAssetProvider provider;

	@Column(name = "provider_connection_id", updatable = false)
	private UUID providerConnectionId;

	@Column(name = "provider_asset_id")
	private String providerAssetId;

	/** VIDEO Phase 5d (V76): the YouTube integration (Media Library tab) the video belongs to. */
	@Column(name = "integration_id", updatable = false)
	private UUID integrationId;

	@Column(name = "title")
	private String title;

	@Column(name = "description")
	private String description;

	@Column(name = "thumbnail_url")
	private String thumbnailUrl;

	@Enumerated(EnumType.STRING)
	@Column(name = "privacy_status")
	private MediaPrivacyStatus privacyStatus;

	@Column(name = "provider_failure_reason")
	private String providerFailureReason;

	@Column(name = "last_synced_at")
	private Instant lastSyncedAt;

	@Column(name = "next_sync_at")
	private Instant nextSyncAt;

	@Column(name = "sync_failure_count", nullable = false)
	private int syncFailureCount;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	@Column(name = "deleted_by")
	private UUID deletedBy;

	@Column(name = "storage_object_key", updatable = false)
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
	 * Constructs an {@link MediaAssetProvider#INTERNAL} asset directly in
	 * {@code READY} - {@code VideoAssetService#upload} only calls this after
	 * the synchronous object-storage write has already succeeded.
	 */
	public VideoAsset(UUID tenantId, String storageObjectKey, String originalFilename, String mimeType,
			Long sizeBytes, UUID uploadedBy) {
		this.tenantId = tenantId;
		this.provider = MediaAssetProvider.INTERNAL;
		this.storageObjectKey = storageObjectKey;
		this.originalFilename = originalFilename;
		this.mimeType = mimeType;
		this.sizeBytes = sizeBytes;
		this.uploadedBy = uploadedBy;
		this.status = VideoAssetStatus.READY;
	}

	/**
	 * A new externally hosted asset in {@code CREATED}: metadata is known, no
	 * bytes have reached the provider yet. The provider connection must belong
	 * to {@code tenantId} - the composite FK backstops that.
	 */
	public static VideoAsset createForProvider(UUID tenantId, MediaAssetProvider provider, UUID integrationId,
			UUID providerConnectionId, String title, String description, MediaPrivacyStatus privacyStatus,
			String originalFilename, String mimeType, long sizeBytes, UUID uploadedBy) {
		if (provider == MediaAssetProvider.INTERNAL) {
			throw new IllegalArgumentException("INTERNAL assets are created by the object-storage upload path");
		}
		VideoAsset asset = new VideoAsset();
		asset.tenantId = tenantId;
		asset.provider = provider;
		asset.providerConnectionId = providerConnectionId;
		asset.integrationId = integrationId;
		asset.title = title;
		asset.description = description;
		asset.privacyStatus = privacyStatus;
		asset.originalFilename = originalFilename;
		asset.mimeType = mimeType;
		asset.sizeBytes = sizeBytes;
		asset.uploadedBy = uploadedBy;
		asset.status = VideoAssetStatus.CREATED;
		return asset;
	}

	/**
	 * Moves this asset to {@code target}, enforcing {@link
	 * VideoAssetStatus#canTransitionTo}. {@code DELETED} must go through
	 * {@link #markDeleted} so the deletion actor is always recorded.
	 *
	 * @throws IllegalStateException on an illegal transition
	 */
	public void transitionTo(VideoAssetStatus target) {
		if (target == VideoAssetStatus.DELETED) {
			throw new IllegalStateException("Use markDeleted to delete a video asset");
		}
		requireTransition(target);
		this.status = target;
		if (target != VideoAssetStatus.FAILED) {
			this.providerFailureReason = null;
		}
	}

	/** {@link #transitionTo} {@code FAILED}, keeping the provider's reason for display. */
	public void markFailed(String failureReason) {
		transitionTo(VideoAssetStatus.FAILED);
		this.providerFailureReason = failureReason;
	}

	/** Soft delete - the row and its status history are kept. */
	public void markDeleted(UUID actorId, Instant at) {
		requireTransition(VideoAssetStatus.DELETED);
		this.status = VideoAssetStatus.DELETED;
		this.deletedBy = actorId;
		this.deletedAt = at;
	}

	/** Records the provider's own id once the upload has been accepted by the provider. */
	public void assignProviderAssetId(String providerAssetId) {
		if (provider == MediaAssetProvider.INTERNAL) {
			throw new IllegalStateException("INTERNAL assets have no provider asset id");
		}
		if (this.providerAssetId != null && !this.providerAssetId.equals(providerAssetId)) {
			throw new IllegalStateException("Provider asset id is already assigned");
		}
		this.providerAssetId = providerAssetId;
	}

	/**
	 * VIDEO Phase 5: the editable metadata. {@code privacyStatus} applies only
	 * to provider-hosted assets (an INTERNAL asset has none).
	 */
	public void updateMetadata(String title, String description, MediaPrivacyStatus privacyStatus) {
		if (isDeleted()) {
			throw new IllegalStateException("A deleted video asset cannot be edited");
		}
		this.title = title;
		this.description = description;
		if (provider != MediaAssetProvider.INTERNAL) {
			this.privacyStatus = privacyStatus;
		}
	}

	/** VIDEO Phase 4: when the status sync should next ask the provider (null = never). */
	public void scheduleSync(Instant nextSyncAt) {
		this.nextSyncAt = nextSyncAt;
	}

	/** A status check that found the video still processing: count it and back off. */
	public void recordPendingSyncCheck(Instant checkedAt, Instant nextSyncAt) {
		this.lastSyncedAt = checkedAt;
		this.syncFailureCount++;
		this.nextSyncAt = nextSyncAt;
	}

	/** A final status from the provider: stop syncing and keep its metadata. */
	public void recordFinalSync(Instant checkedAt, Integer durationSeconds, String thumbnailUrl) {
		this.lastSyncedAt = checkedAt;
		this.nextSyncAt = null;
		if (durationSeconds != null) {
			this.durationSeconds = durationSeconds;
		}
		if (thumbnailUrl != null) {
			this.thumbnailUrl = thumbnailUrl;
		}
	}

	private void requireTransition(VideoAssetStatus target) {
		if (!status.canTransitionTo(target)) {
			throw new IllegalStateException("Illegal video asset transition " + status + " -> " + target);
		}
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

	public MediaAssetProvider getProvider() {
		return provider;
	}

	public UUID getProviderConnectionId() {
		return providerConnectionId;
	}

	public UUID getIntegrationId() {
		return integrationId;
	}

	public String getProviderAssetId() {
		return providerAssetId;
	}

	public String getTitle() {
		return title;
	}

	public String getDescription() {
		return description;
	}

	public String getThumbnailUrl() {
		return thumbnailUrl;
	}

	public MediaPrivacyStatus getPrivacyStatus() {
		return privacyStatus;
	}

	public String getProviderFailureReason() {
		return providerFailureReason;
	}

	public Instant getLastSyncedAt() {
		return lastSyncedAt;
	}

	public Instant getNextSyncAt() {
		return nextSyncAt;
	}

	public int getSyncFailureCount() {
		return syncFailureCount;
	}

	public Instant getDeletedAt() {
		return deletedAt;
	}

	public UUID getDeletedBy() {
		return deletedBy;
	}

	public boolean isDeleted() {
		return deletedAt != null;
	}

}
