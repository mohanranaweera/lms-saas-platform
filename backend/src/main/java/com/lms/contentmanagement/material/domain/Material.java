package com.lms.contentmanagement.material.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A single uploaded learning material (PDF/image/notes at MVP), attached to
 * exactly one course-management {@code course_lesson}, referenced by an
 * OPAQUE {@code lessonId} only - no JPA association, no import of {@code
 * coursemanagement.course.domain} classes, per {@code
 * .claude/rules/architecture.md}'s cross-module boundary rule (mirrors how
 * {@code CourseLesson.moduleId} itself already works). The composite FK
 * enforcing tenant match against {@code course_lesson} lives at the
 * DATABASE level only (V16) - see {@code docs/plans/MVP-009 Lessons and
 * Learning Materials.md} §7 for the full reconciliation of this design
 * decision.
 *
 * <p>No binary content lives here - {@code storageObjectKey} is an opaque
 * pointer into external object storage, per {@code
 * .claude/rules/architecture.md}'s "never self-hosted binary media storage
 * through the app tier" rule.
 */
@Entity
@Table(name = "material")
public class Material extends Auditable implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "lesson_id", nullable = false, updatable = false)
	private UUID lessonId;

	@Column(name = "title", nullable = false)
	private String title;

	@Column(name = "original_filename", nullable = false, updatable = false)
	private String originalFilename;

	@Column(name = "storage_object_key", nullable = false, updatable = false)
	private String storageObjectKey;

	@Column(name = "mime_type", nullable = false, updatable = false)
	private String mimeType;

	@Column(name = "size_bytes", nullable = false, updatable = false)
	private Long sizeBytes;

	@Column(name = "sequence", nullable = false)
	private Integer sequence;

	@Column(name = "visibility", nullable = false)
	private MaterialVisibility visibility;

	@Column(name = "expiry_at")
	private Instant expiryAt;

	@Column(name = "uploaded_by", nullable = false, updatable = false)
	private UUID uploadedBy;

	protected Material() {
	}

	public Material(UUID tenantId, UUID lessonId, String title, String originalFilename, String storageObjectKey,
			String mimeType, Long sizeBytes, Integer sequence, UUID uploadedBy) {
		this.tenantId = tenantId;
		this.lessonId = lessonId;
		this.title = title;
		this.originalFilename = originalFilename;
		this.storageObjectKey = storageObjectKey;
		this.mimeType = mimeType;
		this.sizeBytes = sizeBytes;
		this.sequence = sequence;
		this.visibility = MaterialVisibility.VISIBLE;
		this.uploadedBy = uploadedBy;
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

	public Instant getExpiryAt() {
		return expiryAt;
	}

	public UUID getUploadedBy() {
		return uploadedBy;
	}

}
