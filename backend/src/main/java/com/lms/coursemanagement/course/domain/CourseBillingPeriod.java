package com.lms.coursemanagement.course.domain;

import com.lms.common.persistence.BaseEntity;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Append-only billed-amount history for a course's {@link
 * CourseBillingConfiguration}, mapped 1:1 onto {@code course_billing_period}
 * (V39) - the Wave 2 analogue of {@link CoursePriceHistory} for {@code
 * course.price}, but scoped to {@code MONTHLY}/{@code SESSION} billing
 * configurations instead.
 *
 * <p>{@code billingConfigurationId} is an OPAQUE id only, deliberately with
 * no live FK at the schema level (V39's header comment) - never re-read or
 * re-validated against a currently-existing {@code CourseBillingConfiguration}
 * row after insert, mirroring {@link CoursePriceHistory#getCourseId()}'s
 * identical orphan-tolerant contract.
 *
 * <p>{@code createdAt} is mapped {@code insertable = false} - this table's
 * {@code created_at} column carries its own DB-side {@code DEFAULT now()}
 * (V39's header comment explains why, unlike every other audit-timestamped
 * table in this schema), so the application never writes it; Hibernate only
 * ever reads it back after insert.
 *
 * <p>Append-only: {@code amount}/{@code currency}/{@code effectiveFrom}/
 * {@code createdBy} are set once, at construction, with no setters at all.
 * {@code effectiveTo} is the one narrow, explicitly-audited exception - see
 * {@link #close(Instant)} - settable exactly once, by {@code
 * BillingConfigurationService#addBillingPeriod} only, never a general-purpose
 * update.
 */
@Entity
@Table(name = "course_billing_period")
public class CourseBillingPeriod extends BaseEntity implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "billing_configuration_id", nullable = false, updatable = false)
	private UUID billingConfigurationId;

	@Column(name = "amount", nullable = false, updatable = false, precision = 12, scale = 2)
	private BigDecimal amount;

	@Column(name = "currency", nullable = false, updatable = false, length = 3)
	private String currency;

	@Column(name = "effective_from", nullable = false, updatable = false)
	private Instant effectiveFrom;

	@Column(name = "effective_to")
	private Instant effectiveTo;

	@Column(name = "created_by", updatable = false)
	private UUID createdBy;

	@Column(name = "created_at", insertable = false, updatable = false)
	private Instant createdAt;

	protected CourseBillingPeriod() {
	}

	public CourseBillingPeriod(UUID tenantId, UUID billingConfigurationId, BigDecimal amount, String currency,
			Instant effectiveFrom, UUID createdBy) {
		this.tenantId = tenantId;
		this.billingConfigurationId = billingConfigurationId;
		this.amount = amount;
		this.currency = currency;
		this.effectiveFrom = effectiveFrom;
		this.createdBy = createdBy;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getBillingConfigurationId() {
		return billingConfigurationId;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public String getCurrency() {
		return currency;
	}

	public Instant getEffectiveFrom() {
		return effectiveFrom;
	}

	public Instant getEffectiveTo() {
		return effectiveTo;
	}

	public boolean isOpen() {
		return effectiveTo == null;
	}

	public UUID getCreatedBy() {
		return createdBy;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	/**
	 * Closes this period exactly once - throws if already closed. Called
	 * exclusively by {@code BillingConfigurationService#addBillingPeriod} in
	 * the same transaction that inserts the new, now-current period.
	 */
	public void close(Instant at) {
		if (this.effectiveTo != null) {
			throw new IllegalStateException("This billing period is already closed");
		}
		this.effectiveTo = at;
	}

}
