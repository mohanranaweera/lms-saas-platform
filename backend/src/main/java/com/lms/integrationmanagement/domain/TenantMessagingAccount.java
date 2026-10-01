package com.lms.integrationmanagement.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import com.lms.integrationmanagement.api.MessagingChannelApi.Channel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A tenant's own SMS or WhatsApp provider account (Wave 11, V66). The
 * credentials are only ever held as AES-256-GCM ciphertext; this entity has
 * no plaintext accessor and its {@link #toString()} is the inherited identity
 * one. Rows are never deleted - replacing credentials disables the old row,
 * since deliveries keep referencing the account that sent them.
 */
@Entity
@Table(name = "tenant_messaging_account")
public class TenantMessagingAccount extends Auditable implements TenantOwned {

	/** Mirrors {@code ck_tenant_messaging_account_provider} - SANDBOX only until a vendor ADR (decision D1). */
	public enum Provider {

		SANDBOX

	}

	public enum Status {

		ACTIVE, DISABLED

	}

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Enumerated(EnumType.STRING)
	@Column(name = "channel", nullable = false, updatable = false, length = 10)
	private Channel channel;

	@Enumerated(EnumType.STRING)
	@Column(name = "provider", nullable = false, updatable = false, length = 20)
	private Provider provider;

	@Column(name = "sender_id", nullable = false, updatable = false, length = 40)
	private String senderId;

	@Column(name = "credentials_ciphertext", nullable = false)
	private String credentialsCiphertext;

	@Column(name = "credential_hint", nullable = false, updatable = false, length = 16)
	private String credentialHint;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 10)
	private Status status;

	@Column(name = "disabled_at")
	private Instant disabledAt;

	@Column(name = "disabled_by_user_id")
	private UUID disabledByUserId;

	protected TenantMessagingAccount() {
	}

	/**
	 * The ciphertext is bound (AAD) to this row's id, which is only generated on
	 * persist - so the row is created with a placeholder and the real ciphertext
	 * is set by {@link #sealCredentials(String)} in the same transaction.
	 */
	public TenantMessagingAccount(UUID tenantId, Channel channel, Provider provider, String senderId,
			String credentialHint) {
		this.tenantId = tenantId;
		this.channel = channel;
		this.provider = provider;
		this.senderId = senderId;
		this.credentialHint = credentialHint;
		this.credentialsCiphertext = "pending";
		this.status = Status.ACTIVE;
	}

	public void sealCredentials(String ciphertext) {
		this.credentialsCiphertext = ciphertext;
	}

	public void disable(UUID actorUserId, Instant at) {
		if (status != Status.ACTIVE) {
			throw new IllegalStateException("Account is already disabled");
		}
		this.status = Status.DISABLED;
		this.disabledAt = at;
		this.disabledByUserId = actorUserId;
	}

	public boolean isActive() {
		return status == Status.ACTIVE;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public Channel getChannel() {
		return channel;
	}

	public Provider getProvider() {
		return provider;
	}

	public String getSenderId() {
		return senderId;
	}

	public String getCredentialsCiphertext() {
		return credentialsCiphertext;
	}

	public String getCredentialHint() {
		return credentialHint;
	}

	public Status getStatus() {
		return status;
	}

	public Instant getDisabledAt() {
		return disabledAt;
	}

	/** AAD binding a ciphertext to exactly this row (wave-11-plan.md §2.3). */
	public String credentialsAad() {
		return "messaging:" + tenantId + ":" + channel + ":" + getId();
	}

}
