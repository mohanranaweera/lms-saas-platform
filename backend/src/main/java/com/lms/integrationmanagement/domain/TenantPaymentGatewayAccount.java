package com.lms.integrationmanagement.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A tenant's own merchant account with an online payment gateway (PAY-GW-01,
 * ADR-017 - payment roadmap Phase 3). Payments initiated while this account is
 * ACTIVE are paid straight into the tenant's account ({@code
 * payment.collected_by = TENANT}).
 *
 * <p>The merchant secret is only ever held as AES-256-GCM ciphertext (see
 * {@code CredentialCipher}); this entity never exposes a plaintext accessor.
 * Rows are never deleted - replacing credentials disables the old row, since
 * payments keep referencing the account that collected them.
 */
@Entity
@Table(name = "tenant_payment_gateway_account")
public class TenantPaymentGatewayAccount extends Auditable implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Enumerated(EnumType.STRING)
	@Column(name = "provider", nullable = false, updatable = false, length = 20)
	private GatewayProvider provider;

	@Enumerated(EnumType.STRING)
	@Column(name = "mode", nullable = false, updatable = false, length = 10)
	private GatewayMode mode;

	@Column(name = "merchant_id", nullable = false, updatable = false, length = 64)
	private String merchantId;

	@Column(name = "merchant_secret_ciphertext", nullable = false, updatable = false)
	private String merchantSecretCiphertext;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 10)
	private GatewayAccountStatus status;

	@Enumerated(EnumType.STRING)
	@Column(name = "disabled_by", length = 10)
	private GatewayDisabledBy disabledBy;

	@Column(name = "disabled_reason")
	private String disabledReason;

	@Column(name = "disabled_at")
	private Instant disabledAt;

	protected TenantPaymentGatewayAccount() {
	}

	public TenantPaymentGatewayAccount(UUID tenantId, GatewayProvider provider, GatewayMode mode, String merchantId,
			String merchantSecretCiphertext) {
		this.tenantId = tenantId;
		this.provider = provider;
		this.mode = mode;
		this.merchantId = merchantId;
		this.merchantSecretCiphertext = merchantSecretCiphertext;
		this.status = GatewayAccountStatus.ACTIVE;
	}

	public boolean isActive() {
		return status == GatewayAccountStatus.ACTIVE;
	}

	public void disable(GatewayDisabledBy by, String reason, Instant at) {
		if (!isActive()) {
			throw new IllegalStateException("Account is already disabled");
		}
		this.status = GatewayAccountStatus.DISABLED;
		this.disabledBy = by;
		this.disabledReason = reason;
		this.disabledAt = at;
	}

	/** Only for a PLATFORM-disabled account being switched back on by a Platform Admin. */
	public void reEnable() {
		if (isActive()) {
			throw new IllegalStateException("Account is already active");
		}
		this.status = GatewayAccountStatus.ACTIVE;
		this.disabledBy = null;
		this.disabledReason = null;
		this.disabledAt = null;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public GatewayProvider getProvider() {
		return provider;
	}

	public GatewayMode getMode() {
		return mode;
	}

	public String getMerchantId() {
		return merchantId;
	}

	/** Ciphertext only - decrypt through {@code CredentialCipher} at the point of use. */
	public String getMerchantSecretCiphertext() {
		return merchantSecretCiphertext;
	}

	public GatewayAccountStatus getStatus() {
		return status;
	}

	public GatewayDisabledBy getDisabledBy() {
		return disabledBy;
	}

	public String getDisabledReason() {
		return disabledReason;
	}

	public Instant getDisabledAt() {
		return disabledAt;
	}

}
