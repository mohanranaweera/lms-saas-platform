package com.lms.contentmanagement.material.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A single learning material (Wave 5, V50/V51 extend MVP-009's V16 shape),
 * attached to a course-management {@code course_lesson} and/or a
 * live-class-management {@code class_session}, both referenced by OPAQUE ids
 * only - no JPA association, no import of {@code coursemanagement}/{@code
 * liveclassmanagement} domain classes, per {@code
 * .claude/rules/architecture.md}'s cross-module boundary rule. {@code
 * videoAssetId} is the same kind of opaque cross-domain pointer into {@code
 * video-access-management}'s {@code VideoAsset} - content-management never
 * imports that domain's entity, only its {@code id} (plan §3/§9).
 *
 * <p>{@link #materialType} discriminates between an uploaded FILE ({@link
 * MaterialType#PDF}/{@link MaterialType#IMAGE}/{@link MaterialType#DOCUMENT}/
 * {@link MaterialType#OTHER}, which carry {@code storageObjectKey}), an
 * external {@link MaterialType#LINK} (carries {@code externalUrl}), an
 * inline {@link MaterialType#NOTE} (carries {@code noteContent}), and a
 * secure {@link MaterialType#VIDEO}/{@link MaterialType#RECORDING} (carries
 * {@code videoAssetId}) - see that enum's own javadoc. No binary content
 * ever lives directly on this row - {@code storageObjectKey} is an opaque
 * pointer into external object storage, per {@code
 * .claude/rules/architecture.md}'s "never self-hosted binary media storage
 * through the app tier" rule.
 */
@Entity
@Table(name = "material")
public class Material extends Auditable implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "lesson_id", updatable = false)
	private UUID lessonId;

	@Column(name = "session_id", updatable = false)
	private UUID sessionId;

	@Column(name = "material_type", nullable = false, updatable = false)
	private MaterialType materialType;

	@Column(name = "title", nullable = false)
	private String title;

	@Column(name = "original_filename", updatable = false)
	private String originalFilename;

	@Column(name = "storage_object_key", updatable = false)
	private String storageObjectKey;

	@Column(name = "mime_type", updatable = false)
	private String mimeType;

	@Column(name = "size_bytes", updatable = false)
	private Long sizeBytes;

	@Column(name = "external_url", updatable = false)
	private String externalUrl;

	@Column(name = "note_content", updatable = false)
	private String noteContent;

	@Column(name = "video_asset_id", updatable = false)
	private UUID videoAssetId;

	@Column(name = "sequence", nullable = false)
	private Integer sequence;

	@Column(name = "visibility", nullable = false)
	private MaterialVisibility visibility;

	@Column(name = "max_downloads")
	private Integer maxDownloads;

	@Column(name = "download_count", nullable = false)
	private Integer downloadCount;

	@Column(name = "available_from_at")
	private Instant availableFromAt;

	@Column(name = "expiry_at")
	private Instant expiryAt;

	@Column(name = "uploaded_by", nullable = false, updatable = false)
	private UUID uploadedBy;

	protected Material() {
	}

	/**
	 * The single, comprehensive constructor every static factory below
	 * delegates to. Private - callers use the type-specific factory methods
	 * (or, for pre-Wave-5 uploaded-file callers, the legacy public
	 * constructor kept below for source compatibility with existing
	 * fixtures/tests) so an impossible field combination (e.g. a {@code LINK}
	 * with a {@code storageObjectKey}) can never be constructed from
	 * production code, even though the DB CHECK constraints are the real
	 * backstop.
	 */
	private Material(UUID tenantId, UUID lessonId, UUID sessionId, MaterialType materialType, String title,
			String originalFilename, String storageObjectKey, String mimeType, Long sizeBytes, String externalUrl,
			String noteContent, UUID videoAssetId, Integer sequence, Integer maxDownloads, Instant availableFromAt,
			Instant expiryAt, UUID uploadedBy) {
		this.tenantId = tenantId;
		this.lessonId = lessonId;
		this.sessionId = sessionId;
		this.materialType = materialType;
		this.title = title;
		this.originalFilename = originalFilename;
		this.storageObjectKey = storageObjectKey;
		this.mimeType = mimeType;
		this.sizeBytes = sizeBytes;
		this.externalUrl = externalUrl;
		this.noteContent = noteContent;
		this.videoAssetId = videoAssetId;
		this.sequence = sequence;
		this.visibility = MaterialVisibility.VISIBLE;
		this.maxDownloads = maxDownloads;
		this.downloadCount = 0;
		this.availableFromAt = availableFromAt;
		this.expiryAt = expiryAt;
		this.uploadedBy = uploadedBy;
	}

	/**
	 * Legacy (pre-Wave-5) uploaded-file constructor, kept for source
	 * compatibility with every pre-existing MVP-009 fixture/test that
	 * constructs a plain uploaded-file {@code Material} without needing to
	 * name a {@link MaterialType} - defaults to {@link MaterialType#OTHER}
	 * ("uploaded file of unspecified sub-type", the same honest default V50's
	 * migration backfills pre-existing rows to). Production code creating a
	 * new material should prefer {@link #uploadedFile} and name the real
	 * type explicitly.
	 */
	public Material(UUID tenantId, UUID lessonId, String title, String originalFilename, String storageObjectKey,
			String mimeType, Long sizeBytes, Integer sequence, UUID uploadedBy) {
		this(tenantId, lessonId, null, MaterialType.OTHER, title, originalFilename, storageObjectKey, mimeType,
				sizeBytes, null, null, null, sequence, null, null, null, uploadedBy);
	}

	public static Material uploadedFile(UUID tenantId, UUID lessonId, UUID sessionId, MaterialType materialType,
			String title, String originalFilename, String storageObjectKey, String mimeType, Long sizeBytes,
			Integer sequence, Integer maxDownloads, Instant availableFromAt, Instant expiryAt, UUID uploadedBy) {
		return new Material(tenantId, lessonId, sessionId, materialType, title, originalFilename, storageObjectKey,
				mimeType, sizeBytes, null, null, null, sequence, maxDownloads, availableFromAt, expiryAt, uploadedBy);
	}

	public static Material link(UUID tenantId, UUID lessonId, UUID sessionId, String title, String externalUrl,
			Integer sequence, Integer maxDownloads, Instant availableFromAt, Instant expiryAt, UUID uploadedBy) {
		return new Material(tenantId, lessonId, sessionId, MaterialType.LINK, title, null, null, null, null,
				externalUrl, null, null, sequence, maxDownloads, availableFromAt, expiryAt, uploadedBy);
	}

	public static Material note(UUID tenantId, UUID lessonId, UUID sessionId, String title, String noteContent,
			Integer sequence, Integer maxDownloads, Instant availableFromAt, Instant expiryAt, UUID uploadedBy) {
		return new Material(tenantId, lessonId, sessionId, MaterialType.NOTE, title, null, null, null, null, null,
				noteContent, null, sequence, maxDownloads, availableFromAt, expiryAt, uploadedBy);
	}

	public static Material video(UUID tenantId, UUID lessonId, UUID sessionId, MaterialType materialType,
			String title, UUID videoAssetId, Integer sequence, Integer maxDownloads, Instant availableFromAt,
			Instant expiryAt, UUID uploadedBy) {
		return new Material(tenantId, lessonId, sessionId, materialType, title, null, null, null, null, null, null,
				videoAssetId, sequence, maxDownloads, availableFromAt, expiryAt, uploadedBy);
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getLessonId() {
		return lessonId;
	}

	public UUID getSessionId() {
		return sessionId;
	}

	public MaterialType getMaterialType() {
		return materialType;
	}

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public String getOriginalFilename() {
		return originalFilename;
	}

	public String getStorageObjectKey() {
		return storageObjectKey;
	}

	public String getMimeType() {
		return mimeType;
	}

	public Long getSizeBytes() {
		return sizeBytes;
	}

	public String getExternalUrl() {
		return externalUrl;
	}

	public String getNoteContent() {
		return noteContent;
	}

	public UUID getVideoAssetId() {
		return videoAssetId;
	}

	public Integer getSequence() {
		return sequence;
	}

	public void setSequence(Integer sequence) {
		this.sequence = sequence;
	}

	public MaterialVisibility getVisibility() {
		return visibility;
	}

	public void setVisibility(MaterialVisibility visibility) {
		this.visibility = visibility;
	}

	public Integer getMaxDownloads() {
		return maxDownloads;
	}

	public Integer getDownloadCount() {
		return downloadCount;
	}

	public Instant getAvailableFromAt() {
		return availableFromAt;
	}

	public Instant getExpiryAt() {
		return expiryAt;
	}

	public UUID getUploadedBy() {
		return uploadedBy;
	}

}
