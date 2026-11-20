package com.lms.localizationmanagement.domain;

import com.lms.common.persistence.BaseEntity;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * {@code translation_release} (V124): one immutable published version of a tenant's overrides
 * for one locale. Append-only (DB trigger + {@code AppendOnlyTenantAwareRepository}).
 */
@Entity
@Immutable
@Table(name = "translation_release")
public class TranslationRelease extends BaseEntity implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "locale", nullable = false, updatable = false, length = 16)
	private String locale;

	@Column(name = "version_no", nullable = false, updatable = false)
	private int versionNo;

	@Column(name = "summary", updatable = false, length = 500)
	private String summary;

	@Column(name = "entry_count", nullable = false, updatable = false)
	private int entryCount;

	@Column(name = "rollback_of_release_id", updatable = false)
	private UUID rollbackOfReleaseId;

	@Column(name = "published_by", nullable = false, updatable = false)
	private UUID publishedBy;

	@Column(name = "published_at", nullable = false, updatable = false)
	private Instant publishedAt;

	protected TranslationRelease() {
	}

	public TranslationRelease(UUID tenantId, String locale, int versionNo, String summary, int entryCount,
			UUID rollbackOfReleaseId, UUID publishedBy, Instant publishedAt) {
		this.tenantId = tenantId;
		this.locale = locale;
		this.versionNo = versionNo;
		this.summary = summary;
		this.entryCount = entryCount;
		this.rollbackOfReleaseId = rollbackOfReleaseId;
		this.publishedBy = publishedBy;
		this.publishedAt = publishedAt;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public String getLocale() {
		return locale;
	}

	public int getVersionNo() {
		return versionNo;
	}

	public String getSummary() {
		return summary;
	}

	public int getEntryCount() {
		return entryCount;
	}

	public UUID getRollbackOfReleaseId() {
		return rollbackOfReleaseId;
	}

	public UUID getPublishedBy() {
		return publishedBy;
	}

	public Instant getPublishedAt() {
		return publishedAt;
	}

}
