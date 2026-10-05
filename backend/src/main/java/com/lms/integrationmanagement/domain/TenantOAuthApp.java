package com.lms.integrationmanagement.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * An institute's own OAuth app for one provider ({@code tenant_oauth_app},
 * V75 - VIDEO Phase 5c, ADR-018 Amendment 5 as revised 2026-10-05). Overrides
 * the platform app for that institute only. The secret is ciphertext bound
 * to the tenant and is never returned by any API.
 */
@Entity
@Table(name = "tenant_oauth_app")
public class TenantOAuthApp extends Auditable implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Enumerated(EnumType.STRING)
	@Column(name = "provider", nullable = false, updatable = false)
	private PlatformOAuthProvider provider;

	@Column(name = "client_id", nullable = false)
	private String clientId;

	@Column(name = "client_secret_ciphertext", nullable = false)
	private String clientSecretCiphertext;

	protected TenantOAuthApp() {
	}

	public TenantOAuthApp(UUID tenantId, PlatformOAuthProvider provider) {
		this.tenantId = tenantId;
		this.provider = provider;
	}

	public void replace(String clientId, String clientSecretCiphertext) {
		this.clientId = clientId;
		this.clientSecretCiphertext = clientSecretCiphertext;
	}

	public PlatformOAuthProvider getProvider() {
		return provider;
	}

	public String getClientId() {
		return clientId;
	}

	public String getClientSecretCiphertext() {
		return clientSecretCiphertext;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

}
