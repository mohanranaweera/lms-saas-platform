package com.lms.integrationmanagement.domain;

import com.lms.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * The platform's own OAuth app credentials for one provider
 * ({@code platform_oauth_app}, V74 - VIDEO Phase 5c, ADR-018 Amendment 5).
 * Platform configuration, not tenant data: no {@code tenant_id}. The secret
 * is ciphertext only and is never returned by any API.
 */
@Entity
@Table(name = "platform_oauth_app")
public class PlatformOAuthApp extends BaseEntity {

	@Enumerated(EnumType.STRING)
	@Column(name = "provider", nullable = false, updatable = false)
	private PlatformOAuthProvider provider;

	@Column(name = "client_id", nullable = false)
	private String clientId;

	@Column(name = "client_secret_ciphertext", nullable = false)
	private String clientSecretCiphertext;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Column(name = "updated_by", nullable = false)
	private UUID updatedBy;

	protected PlatformOAuthApp() {
	}

	public PlatformOAuthApp(PlatformOAuthProvider provider) {
		this.provider = provider;
	}

	public void replace(String clientId, String clientSecretCiphertext, UUID actorId, Instant now) {
		this.clientId = clientId;
		this.clientSecretCiphertext = clientSecretCiphertext;
		this.updatedBy = actorId;
		this.updatedAt = now;
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

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public UUID getUpdatedBy() {
		return updatedBy;
	}

}
