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
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * V92 {@code discount_rule_version}: one immutable, append-only set of pricing
 * terms of a rule (D13). An edit appends a new version; orders reference the
 * exact version they used ({@code student_order_discount.rule_version_id}).
 */
@Entity
@Immutable
@Table(name = "discount_rule_version")
public class DiscountRuleVersion extends BaseEntity implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "rule_id", nullable = false, updatable = false)
	private UUID ruleId;

	@Column(name = "version_no", nullable = false, updatable = false)
	private int versionNo;

	@Column(name = "name", nullable = false, updatable = false, length = 120)
	private String name;

	@Column(name = "description", updatable = false, length = 1000)
	private String description;

	@Enumerated(EnumType.STRING)
	@Column(name = "discount_type", nullable = false, updatable = false, length = 30)
	private DiscountType discountType;

	@Enumerated(EnumType.STRING)
	@Column(name = "selection_mode", nullable = false, updatable = false, length = 20)
	private SelectionMode selectionMode;

	@Column(name = "min_count", updatable = false)
	private Integer minCount;

	@Enumerated(EnumType.STRING)
	@Column(name = "payment_type", updatable = false, length = 20)
	private DiscountPaymentType paymentType;

	@Enumerated(EnumType.STRING)
	@Column(name = "value_type", updatable = false, length = 20)
	private DiscountValueType valueType;

	@Column(name = "value", updatable = false, precision = 12, scale = 2)
	private BigDecimal value;

	@Column(name = "free_months", updatable = false)
	private Integer freeMonths;

	@Column(name = "code", updatable = false, length = 40)
	private String code;

	@Column(name = "valid_from", nullable = false, updatable = false)
	private LocalDate validFrom;

	@Column(name = "valid_to", nullable = false, updatable = false)
	private LocalDate validTo;

	@Column(name = "max_uses", updatable = false)
	private Integer maxUses;

	@Column(name = "min_purchase_amount", updatable = false, precision = 12, scale = 2)
	private BigDecimal minPurchaseAmount;

	@Column(name = "currency", nullable = false, updatable = false, length = 3)
	private String currency;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "created_by", nullable = false, updatable = false)
	private UUID createdBy;

	protected DiscountRuleVersion() {
	}

	/** All terms of one version; the caller has already validated their shape (V92 CHECKs mirror it). */
	public record Terms(String name, String description, DiscountType discountType, SelectionMode selectionMode,
			Integer minCount, DiscountPaymentType paymentType, DiscountValueType valueType, BigDecimal value,
			Integer freeMonths, String code, LocalDate validFrom, LocalDate validTo, Integer maxUses,
			BigDecimal minPurchaseAmount, String currency) {

	}

	public DiscountRuleVersion(UUID tenantId, UUID ruleId, int versionNo, Terms terms, Instant createdAt,
			UUID createdBy) {
		this.tenantId = tenantId;
		this.ruleId = ruleId;
		this.versionNo = versionNo;
		this.name = terms.name();
		this.description = terms.description();
		this.discountType = terms.discountType();
		this.selectionMode = terms.selectionMode();
		this.minCount = terms.minCount();
		this.paymentType = terms.paymentType();
		this.valueType = terms.valueType();
		this.value = terms.value();
		this.freeMonths = terms.freeMonths();
		this.code = terms.code();
		this.validFrom = terms.validFrom();
		this.validTo = terms.validTo();
		this.maxUses = terms.maxUses();
		this.minPurchaseAmount = terms.minPurchaseAmount();
		this.currency = terms.currency();
		this.createdAt = createdAt;
		this.createdBy = createdBy;
	}

	public Terms terms() {
		return new Terms(name, description, discountType, selectionMode, minCount, paymentType, valueType, value,
				freeMonths, code, validFrom, validTo, maxUses, minPurchaseAmount, currency);
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getRuleId() {
		return ruleId;
	}

	public int getVersionNo() {
		return versionNo;
	}

	public String getName() {
		return name;
	}

	public String getDescription() {
		return description;
	}

	public DiscountType getDiscountType() {
		return discountType;
	}

	public SelectionMode getSelectionMode() {
		return selectionMode;
	}

	public Integer getMinCount() {
		return minCount;
	}

	public DiscountPaymentType getPaymentType() {
		return paymentType;
	}

	public DiscountValueType getValueType() {
		return valueType;
	}

	public BigDecimal getValue() {
		return value;
	}

	public Integer getFreeMonths() {
		return freeMonths;
	}

	public String getCode() {
		return code;
	}

	public LocalDate getValidFrom() {
		return validFrom;
	}

	public LocalDate getValidTo() {
		return validTo;
	}

	public Integer getMaxUses() {
		return maxUses;
	}

	public BigDecimal getMinPurchaseAmount() {
		return minPurchaseAmount;
	}

	public String getCurrency() {
		return currency;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public UUID getCreatedBy() {
		return createdBy;
	}

}
