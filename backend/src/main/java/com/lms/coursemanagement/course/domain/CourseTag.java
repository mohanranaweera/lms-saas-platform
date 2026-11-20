package com.lms.coursemanagement.course.domain;

import com.lms.common.persistence.BaseEntity;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * {@code course_tag} (V85): a tenant-scoped course label (student catalog
 * chips). Unique per tenant by {@code normalized_name} (trimmed, inner
 * whitespace collapsed, lower-cased) - see {@link #normalize(String)}.
 */
@Entity
@Table(name = "course_tag")
public class CourseTag extends BaseEntity implements TenantOwned {

	public static final int MAX_NAME_LENGTH = 100;

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "name", nullable = false, length = MAX_NAME_LENGTH)
	private String name;

	@Column(name = "normalized_name", nullable = false, length = MAX_NAME_LENGTH, updatable = false)
	private String normalizedName;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected CourseTag() {
	}

	public CourseTag(UUID tenantId, String displayName) {
		this.tenantId = tenantId;
		this.name = cleanDisplayName(displayName);
		this.normalizedName = normalize(displayName);
		this.createdAt = Instant.now();
	}

	/** Trims and collapses inner whitespace; {@code null} for a blank input. */
	public static String cleanDisplayName(String raw) {
		if (raw == null) {
			return null;
		}
		String cleaned = raw.strip().replaceAll("\\s+", " ");
		return cleaned.isEmpty() ? null : cleaned;
	}

	/** The case-insensitive dedupe key: {@link #cleanDisplayName} lower-cased (ROOT locale). */
	public static String normalize(String raw) {
		String cleaned = cleanDisplayName(raw);
		return cleaned == null ? null : cleaned.toLowerCase(Locale.ROOT);
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public String getName() {
		return name;
	}

	public String getNormalizedName() {
		return normalizedName;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
