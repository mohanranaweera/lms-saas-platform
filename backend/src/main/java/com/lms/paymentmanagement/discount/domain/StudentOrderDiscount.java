package com.lms.paymentmanagement.discount.domain;

import com.lms.common.persistence.BaseEntity;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * V92 {@code student_order_discount}: the immutable snapshot of one discount
 * applied to one order (spec §3). Written in the order-placement transaction
 * and never changed - editing, disabling or archiving the rule never touches it.
 */
@Entity
@Immutable
@Table(name = "student_order_discount")
public class StudentOrderDiscount extends BaseEntity implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "order_id", nullable = false, updatable = false)
	private UUID orderId;

	@Column(name = "rule_id", nullable = false, updatable = false)
	private UUID ruleId;

	@Column(name = "rule_version_id", nullable = false, updatable = false)
	private UUID ruleVersionId;

	@Enumerated(EnumType.STRING)
	@Column(name = "discount_type", nullable = false, updatable = false, length = 30)
	private DiscountType discountType;

	@Column(name = "name", nullable = false, updatable = false, length = 120)
	private String name;

	@Column(name = "code", updatable = false, length = 40)
	private String code;

	@Enumerated(EnumType.STRING)
	@Column(name = "value_type", updatable = false, length = 20)
	private DiscountValueType valueType;

	@Column(name = "value", updatable = false, precision = 12, scale = 2)
	private BigDecimal value;

	@Column(name = "amount", nullable = false, updatable = false, precision = 12, scale = 2)
	private BigDecimal amount;

	/** JSON: qualifying course ids, advance periods, reward months, line numbers. */
	@Column(name = "qualification", nullable = false, updatable = false)
	private String qualification;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected StudentOrderDiscount() {
	}

	public StudentOrderDiscount(UUID tenantId, UUID orderId, UUID ruleId, UUID ruleVersionId,
			DiscountType discountType, String name, String code, DiscountValueType valueType, BigDecimal value,
			BigDecimal amount, String qualification, Instant createdAt) {
		this.tenantId = tenantId;
		this.orderId = orderId;
		this.ruleId = ruleId;
		this.ruleVersionId = ruleVersionId;
		this.discountType = discountType;
		this.name = name;
		this.code = code;
		this.valueType = valueType;
		this.value = value;
		this.amount = amount;
		this.qualification = qualification;
		this.createdAt = createdAt;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getOrderId() {
		return orderId;
	}

	public UUID getRuleId() {
		return ruleId;
	}

	public UUID getRuleVersionId() {
		return ruleVersionId;
	}

	public DiscountType getDiscountType() {
		return discountType;
	}

	public String getName() {
		return name;
	}

	public String getCode() {
		return code;
	}

	public DiscountValueType getValueType() {
		return valueType;
	}

	public BigDecimal getValue() {
		return value;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public String getQualification() {
		return qualification;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
