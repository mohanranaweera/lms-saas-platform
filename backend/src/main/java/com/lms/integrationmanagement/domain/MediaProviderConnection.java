package com.lms.integrationmanagement.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import com.lms.integrationmanagement.api.MediaConnectionStatus;
import com.lms.integrationmanagement.api.MediaProvider;
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
 * A connected media/meeting provider account (MEDIA-INT-01 / ADR-018) -
 * either a teacher's own Zoom/YouTube/Vimeo account (OAuth) or the tenant's
 * DynTube/Bunny account (API key).
 *
 * <p>Secrets are only ever held as AES-256-GCM ciphertext (see {@code
 * MediaCredentialCipher}); this entity has no plaintext accessor. Rows are
 * never deleted - {@code class_session.provider_connection_id} references the
 * connection that hosted a meeting - so replacing or reconnecting marks the
 * old row DISCONNECTED and a new row is inserted.
 *
 * <p>Disconnecting keeps the row as history but WIPES its credentials: a
 * disconnected connection must never hold a token or key that might still be
 * valid at the provider (e.g. when best-effort revocation failed).
 */
@Entity
@Table(name = "media_provider_connection")
public class MediaProviderConnection extends Auditable implements TenantOwned {

	private static final int MAX_ERROR_LENGTH = 500;

	/**
	 * What {@code credentials_ciphertext} (NOT NULL) holds once disconnected.
	 * Deliberately not a ciphertext: nothing can decrypt it.
	 */
	public static final String WIPED_CREDENTIALS = "wiped";

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Enumerated(EnumType.STRING)
	@Column(name = "provider", nullable = false, updatable = false, length = 20)
	private MediaProvider provider;

	@Enumerated(EnumType.STRING)
	@Column(name = "scope", nullable = false, updatable = false, length = 10)
	private ConnectionScope scope;

	@Column(name = "owner_user_id", updatable = false)
	private UUID ownerUserId;

	@Enumerated(EnumType.STRING)
	@Column(name = "auth_type", nullable = false, updatable = false, length = 10)
	private ConnectionAuthType authType;

	@Column(name = "external_account_id", updatable = false)
	private String externalAccountId;

	@Column(name = "external_account_label")
	private String externalAccountLabel;

	@Column(name = "external_account_type", length = 30)
	private String externalAccountType;

	@Column(name = "credentials_ciphertext", nullable = false)
	private String credentialsCiphertext;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "settings_json", updatable = false)
	private String settingsJson;

	@Column(name = "access_token_expires_at")
	private Instant accessTokenExpiresAt;

	@Column(name = "granted_scopes", length = 1000)
	private String grantedScopes;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private MediaConnectionStatus status;

	@Enumerated(EnumType.STRING)
	@Column(name = "disconnected_by", length = 12)
	private ConnectionDisconnectedBy disconnectedBy;

	@Column(name = "disconnected_reason")
	private String disconnectedReason;

	@Column(name = "disconnected_at")
	private Instant disconnectedAt;

	@Column(name = "last_verified_at")
	private Instant lastVerifiedAt;

	@Column(name = "last_error", length = MAX_ERROR_LENGTH)
	private String lastError;

	/** TD-008 (V72): set while a Platform Admin has switched the tenant YouTube channel off. */
	@Column(name = "platform_disabled_at")
	private Instant platformDisabledAt;

	@Column(name = "platform_disabled_reason", length = MAX_ERROR_LENGTH)
	private String platformDisabledReason;

	protected MediaProviderConnection() {
	}

	private MediaProviderConnection(UUID tenantId, MediaProvider provider, ConnectionScope scope, UUID ownerUserId,
			ConnectionAuthType authType, String credentialsCiphertext, Instant verifiedAt) {
		this.tenantId = tenantId;
		this.provider = provider;
		this.scope = scope;
		this.ownerUserId = ownerUserId;
		this.authType = authType;
		this.credentialsCiphertext = credentialsCiphertext;
		this.status = MediaConnectionStatus.ACTIVE;
		this.lastVerifiedAt = verifiedAt;
	}

	/** A teacher's own Zoom/YouTube/Vimeo account, just authorized via OAuth. */
	public static MediaProviderConnection teacherOAuth(UUID tenantId, MediaProvider provider, UUID ownerUserId,
			String externalAccountId, String externalAccountLabel, String externalAccountType,
			String credentialsCiphertext, Instant accessTokenExpiresAt, String grantedScopes, Instant verifiedAt) {
		if (!provider.isTeacherScoped() || ownerUserId == null) {
			throw new IllegalArgumentException(provider + " is not a teacher-level provider");
		}
		MediaProviderConnection connection = new MediaProviderConnection(tenantId, provider, ConnectionScope.TEACHER,
				ownerUserId, ConnectionAuthType.OAUTH, credentialsCiphertext, verifiedAt);
		connection.externalAccountId = externalAccountId;
		connection.externalAccountLabel = externalAccountLabel;
		connection.externalAccountType = externalAccountType;
		connection.accessTokenExpiresAt = accessTokenExpiresAt;
		connection.grantedScopes = grantedScopes;
		return connection;
	}

	/**
	 * The tenant's own YouTube channel (VIDEO Phase 3, ADR-018 Amendment 2,
	 * V71): tenant-scoped, no owner, OAuth. Its credentials are sealed with the
	 * tenant AAD, never a user's.
	 */
	public static MediaProviderConnection tenantOAuth(UUID tenantId, MediaProvider provider, String externalAccountId,
			String externalAccountLabel, String credentialsCiphertext, Instant accessTokenExpiresAt,
			String grantedScopes, Instant verifiedAt) {
		if (provider != MediaProvider.YOUTUBE) {
			throw new IllegalArgumentException(provider + " has no tenant-level OAuth connection");
		}
		MediaProviderConnection connection = new MediaProviderConnection(tenantId, provider, ConnectionScope.TENANT,
				null, ConnectionAuthType.OAUTH, credentialsCiphertext, verifiedAt);
		connection.externalAccountId = externalAccountId;
		connection.externalAccountLabel = externalAccountLabel;
		connection.accessTokenExpiresAt = accessTokenExpiresAt;
		connection.grantedScopes = grantedScopes;
		return connection;
	}

	/** The tenant's DynTube/Bunny account, configured with API keys by a Tenant Admin. */
	public static MediaProviderConnection tenantApiKey(UUID tenantId, MediaProvider provider,
			String externalAccountId, String externalAccountLabel, String credentialsCiphertext, String settingsJson,
			Instant verifiedAt) {
		if (provider.isTeacherScoped()) {
			throw new IllegalArgumentException(provider + " is not a tenant-level provider");
		}
		MediaProviderConnection connection = new MediaProviderConnection(tenantId, provider, ConnectionScope.TENANT,
				null, ConnectionAuthType.API_KEY, credentialsCiphertext, verifiedAt);
		connection.externalAccountId = externalAccountId;
		connection.externalAccountLabel = externalAccountLabel;
		connection.settingsJson = settingsJson;
		return connection;
	}

	public boolean isActive() {
		return status == MediaConnectionStatus.ACTIVE;
	}

	public boolean isDisconnected() {
		return status == MediaConnectionStatus.DISCONNECTED;
	}

	/** Also wipes the stored credentials. @throws IllegalStateException if already disconnected. */
	public void disconnect(ConnectionDisconnectedBy by, String reason, Instant at) {
		if (isDisconnected()) {
			throw new IllegalStateException("This connection is already disconnected");
		}
		this.credentialsCiphertext = WIPED_CREDENTIALS;
		this.accessTokenExpiresAt = null;
		this.status = MediaConnectionStatus.DISCONNECTED;
		this.disconnectedBy = by;
		this.disconnectedReason = reason;
		this.disconnectedAt = at;
	}

	/** The provider rejected our credentials (revoked/expired) - the owner must reconnect. */
	public void markNeedsReconnect(String sanitisedError) {
		if (isDisconnected()) {
			return;
		}
		this.status = MediaConnectionStatus.NEEDS_RECONNECT;
		this.lastError = truncate(sanitisedError);
	}

	/** A successful check against the provider; refreshes the displayed label/type when supplied. */
	public void markVerified(Instant at, String externalAccountLabel, String externalAccountType) {
		if (isDisconnected()) {
			throw new IllegalStateException("This connection is disconnected");
		}
		this.status = MediaConnectionStatus.ACTIVE;
		this.lastVerifiedAt = at;
		this.lastError = null;
		if (externalAccountLabel != null) {
			this.externalAccountLabel = externalAccountLabel;
		}
		if (externalAccountType != null) {
			this.externalAccountType = externalAccountType;
		}
	}

	/**
	 * TD-008: a Platform Admin switches the tenant's YouTube channel off. The
	 * connection keeps its status and credentials (already-uploaded videos
	 * still play, Q-004) but takes no new uploads until a Platform Admin
	 * re-enables it.
	 */
	public void platformDisable(String reason, Instant at) {
		if (provider != MediaProvider.YOUTUBE || scope != ConnectionScope.TENANT) {
			throw new IllegalStateException("Only a tenant YouTube channel can be switched off by the platform");
		}
		if (isPlatformDisabled()) {
			throw new IllegalStateException("This channel is already switched off by the platform");
		}
		this.platformDisabledAt = at;
		this.platformDisabledReason = truncate(reason);
	}

	public void platformEnable() {
		if (!isPlatformDisabled()) {
			throw new IllegalStateException("This channel is not switched off by the platform");
		}
		this.platformDisabledAt = null;
		this.platformDisabledReason = null;
	}

	public boolean isPlatformDisabled() {
		return platformDisabledAt != null;
	}

	/** A transient failure (provider unreachable) - recorded, status unchanged. */
	public void recordError(String sanitisedError) {
		this.lastError = truncate(sanitisedError);
	}

	/** Stores refreshed OAuth tokens (already encrypted by the caller). */
	public void replaceTokens(String credentialsCiphertext, Instant accessTokenExpiresAt) {
		this.credentialsCiphertext = credentialsCiphertext;
		this.accessTokenExpiresAt = accessTokenExpiresAt;
	}

	private static String truncate(String value) {
		if (value == null) {
			return null;
		}
		return (value.length() <= MAX_ERROR_LENGTH) ? value : value.substring(0, MAX_ERROR_LENGTH);
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public MediaProvider getProvider() {
		return provider;
	}

	public ConnectionScope getScope() {
		return scope;
	}

	public UUID getOwnerUserId() {
		return ownerUserId;
	}

	public ConnectionAuthType getAuthType() {
		return authType;
	}

	public String getExternalAccountId() {
		return externalAccountId;
	}

	public String getExternalAccountLabel() {
		return externalAccountLabel;
	}

	public String getExternalAccountType() {
		return externalAccountType;
	}

	/** Ciphertext only - decrypt through {@code MediaCredentialCipher} at the point of use. */
	public String getCredentialsCiphertext() {
		return credentialsCiphertext;
	}

	/** Non-secret provider settings (JSON), e.g. Bunny library id / CDN hostname. */
	public String getSettingsJson() {
		return settingsJson;
	}

	public Instant getAccessTokenExpiresAt() {
		return accessTokenExpiresAt;
	}

	public String getGrantedScopes() {
		return grantedScopes;
	}

	public MediaConnectionStatus getStatus() {
		return status;
	}

	public ConnectionDisconnectedBy getDisconnectedBy() {
		return disconnectedBy;
	}

	public String getDisconnectedReason() {
		return disconnectedReason;
	}

	public Instant getDisconnectedAt() {
		return disconnectedAt;
	}

	public Instant getLastVerifiedAt() {
		return lastVerifiedAt;
	}

	public String getLastError() {
		return lastError;
	}

	public Instant getPlatformDisabledAt() {
		return platformDisabledAt;
	}

	public String getPlatformDisabledReason() {
		return platformDisabledReason;
	}

}
