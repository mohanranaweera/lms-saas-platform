package com.lms.integrationmanagement.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import com.lms.integrationmanagement.domain.TenantMessagingAccount.Provider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A tenant's stable status-callback endpoint for one messaging provider
 * (ADR-024 V8, V69). Created the first time the tenant configures a
 * {@link Provider#META_WHATSAPP} account and kept across credential
 * replacements, so the callback URL pasted into the tenant's Meta app keeps
 * working. {@code webhookKey} only selects the tenant; authenticity of a
 * callback is the HMAC with that tenant's App Secret.
 */
@Entity
@Table(name = "tenant_messaging_webhook")
public class TenantMessagingWebhook extends Auditable implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Enumerated(EnumType.STRING)
	@Column(name = "provider", nullable = false, updatable = false, length = 20)
	private Provider provider;

	@Column(name = "webhook_key", nullable = false, updatable = false, length = 64)
	private String webhookKey;

	@Column(name = "verify_token", nullable = false, updatable = false, length = 64)
	private String verifyToken;

	protected TenantMessagingWebhook() {
	}

	public TenantMessagingWebhook(UUID tenantId, Provider provider, String webhookKey, String verifyToken) {
		this.tenantId = tenantId;
		this.provider = provider;
		this.webhookKey = webhookKey;
		this.verifyToken = verifyToken;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public Provider getProvider() {
		return provider;
	}

	public String getWebhookKey() {
		return webhookKey;
	}

	public String getVerifyToken() {
		return verifyToken;
	}

	@Override
	public String toString() {
		return "TenantMessagingWebhook[id=" + getId() + ", provider=" + provider + "]";
	}

}
