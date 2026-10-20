package com.lms.contentmanagement.library.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One institute-wide library document/image (V88 {@code media_file}, Klass
 * gaps round 2 G1, R2-D1..R2-D3). Stored ONCE in external object storage
 * ({@link #objectKey}) and attachable to any number of lessons as a
 * library-backed {@code material} row without copying bytes.
 *
 * <p>Soft delete only ({@link #markDeleted}): the row stays so audit history
 * keeps resolving; a deleted file is hidden from every library read and can
 * no longer be attached. {@code objectKey}, {@code sha256}, {@code
 * contentType} and {@code sizeBytes} are immutable after insert -
 * {@code contentType} is the server-sniffed type and is what every signed
 * "open" URL forces as the response content type.
 */
@Entity
@Table(name = "media_file")
public class MediaFile extends Auditable implements TenantOwned {

	public static final int MAX_TITLE_LENGTH = 255;

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Enumerated(EnumType.STRING)
	@Column(name = "kind", nullable = false, updatable = false, length = 20)
	private MediaFileKind kind;

	@Column(name = "title", nullable = false, length = MAX_TITLE_LENGTH)
	private String title;

	@Column(name = "original_filename", nullable = false, updatable = false)
	private String originalFilename;

	@Column(name = "content_type", nullable = false, updatable = false, length = 100)
	private String contentType;

	@Column(name = "size_bytes", nullable = false, updatable = false)
	private Long sizeBytes;

	@Column(name = "object_key", nullable = false, updatable = false, length = 1024)
	private String objectKey;

	/** CHAR(64) in V88 - lowercase hex SHA-256 of the stored bytes. */
	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "sha256", nullable = false, updatable = false, length = 64)
	private String sha256;

	@Column(name = "uploaded_by", nullable = false, updatable = false)
	private UUID uploadedBy;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	@Column(name = "deleted_by")
	private UUID deletedBy;

	protected MediaFile() {
	}

	public MediaFile(UUID tenantId, MediaFileKind kind, String title, String originalFilename, String contentType,
			long sizeBytes, String objectKey, String sha256, UUID uploadedBy) {
		this.tenantId = tenantId;
		this.kind = kind;
		this.title = title;
		this.originalFilename = originalFilename;
		this.contentType = contentType;
		this.sizeBytes = sizeBytes;
		this.objectKey = objectKey;
		this.sha256 = sha256;
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

	public MediaFileKind getKind() {
		return kind;
	}

	public String getTitle() {
		return title;
	}

	public void rename(String newTitle) {
		this.title = newTitle;
	}

	public String getOriginalFilename() {
		return originalFilename;
	}

	public String getContentType() {
		return contentType;
	}

	public Long getSizeBytes() {
		return sizeBytes;
	}

	public String getObjectKey() {
		return objectKey;
	}

	public String getSha256() {
		return sha256;
	}

	public UUID getUploadedBy() {
		return uploadedBy;
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

	public void markDeleted(UUID actorId, Instant at) {
		this.deletedBy = actorId;
		this.deletedAt = at;
	}

}
