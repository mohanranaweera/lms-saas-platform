package com.lms.localizationmanagement.domain;

import com.lms.common.persistence.BaseEntity;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** {@code translation_release_entry} (V124): one override of a release snapshot. Append-only. */
@Entity
@Immutable
@Table(name = "translation_release_entry")
public class TranslationReleaseEntry extends BaseEntity implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "release_id", nullable = false, updatable = false)
	private UUID releaseId;

	@Column(name = "message_key", nullable = false, updatable = false, length = 200)
	private String messageKey;

	@Column(name = "value", nullable = false, updatable = false)
	private String value;

	@Column(name = "source_hash", nullable = false, updatable = false, length = 64)
	private String sourceHash;

	protected TranslationReleaseEntry() {
	}

	public TranslationReleaseEntry(UUID tenantId, UUID releaseId, String messageKey, String value,
			String sourceHash) {
		this.tenantId = tenantId;
		this.releaseId = releaseId;
		this.messageKey = messageKey;
		this.value = value;
		this.sourceHash = sourceHash;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getReleaseId() {
		return releaseId;
	}

	public String getMessageKey() {
		return messageKey;
	}

	public String getValue() {
		return value;
	}

	public String getSourceHash() {
		return sourceHash;
	}

}
