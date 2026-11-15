package com.lms.financeexpensemanagement.domain;

import com.lms.common.persistence.BaseEntity;
import com.lms.common.persistence.TenantOwned;
import com.lms.financeexpensemanagement.domain.FinanceEnums.RoutingMethod;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** FIN-01: payment method code -> finance account. Replaced as a whole set. */
@Entity
@Table(name = "finance_payment_routing")
public class FinancePaymentRouting extends BaseEntity implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Enumerated(EnumType.STRING)
	@Column(name = "method_code", nullable = false, updatable = false, length = 20)
	private RoutingMethod methodCode;

	@Column(name = "account_id", nullable = false, updatable = false)
	private UUID accountId;

	@Column(name = "updated_by", nullable = false, updatable = false)
	private UUID updatedBy;

	@Column(name = "updated_at", nullable = false, updatable = false)
	private Instant updatedAt;

	protected FinancePaymentRouting() {
	}

	public FinancePaymentRouting(UUID tenantId, RoutingMethod methodCode, UUID accountId, UUID updatedBy,
			Instant updatedAt) {
		this.tenantId = tenantId;
		this.methodCode = methodCode;
		this.accountId = accountId;
		this.updatedBy = updatedBy;
		this.updatedAt = updatedAt;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public RoutingMethod getMethodCode() {
		return methodCode;
	}

	public UUID getAccountId() {
		return accountId;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

}
