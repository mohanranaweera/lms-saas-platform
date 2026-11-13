package com.lms.localizationmanagement.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;

/** {@code translation_draft} (V124): one working override row per (tenant, locale, key). */
@Entity
@Table(name = "translation_draft")
public class TranslationDraft extends Auditable implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "locale", nullable = false, updatable = false, length = 16)
	private String locale;

	@Column(name = "message_key", nullable = false, updatable = false, length = 200)
	private String messageKey;

	@Enumerated(EnumType.STRING)
	@Column(name = "action", nullable = false, length = 5)
	private DraftAction action;

	@Column(name = "value")
	private String value;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected TranslationDraft() {
	}

	public TranslationDraft(UUID tenantId, String locale, String messageKey) {
		this.tenantId = tenantId;
		this.locale = locale;
		this.messageKey = messageKey;
	}

	public void set(String value) {
		this.action = DraftAction.SET;
		this.value = value;
	}

	public void reset() {
		this.action = DraftAction.RESET;
		this.value = null;
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

	public String getMessageKey() {
		return messageKey;
	}

	public DraftAction getAction() {
		return action;
	}

	public String getValue() {
		return value;
	}

	public long getVersion() {
		return version;
	}

}
