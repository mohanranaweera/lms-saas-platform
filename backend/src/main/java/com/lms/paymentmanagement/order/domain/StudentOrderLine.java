package com.lms.paymentmanagement.order.domain;

import com.lms.common.persistence.BaseEntity;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * One coverage item of a payment-composer order (V87 {@code
 * student_order_line}, D06/D07, KLASS-T4c). Immutable and insert-only: every
 * column is {@code updatable = false} and the entity is {@link Immutable}, so
 * a line can never be rewritten; the repository refuses deletes. Amounts are
 * the server-side quote snapshot ({@code line_total = unit_amount +
 * facility_fee_amount - discount_amount}, enforced by V87's CHECK).
 *
 * <p>{@code courseId}/{@code moduleId}/{@code lessonId}/{@code
 * billingPeriodId} are opaque cross-module ids (composite tenant FKs in V87),
 * never JPA associations.
 */
@Entity
@Immutable
@Table(name = "student_order_line")
public class StudentOrderLine extends BaseEntity implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "order_id", nullable = false, updatable = false)
	private UUID orderId;

	@Column(name = "line_no", nullable = false, updatable = false)
	private int lineNo;

	@Column(name = "scope", nullable = false, updatable = false, length = 20)
	private String scope;

	@Column(name = "course_id", nullable = false, updatable = false)
	private UUID courseId;

	@Column(name = "module_id", updatable = false)
	private UUID moduleId;

	@Column(name = "lesson_id", updatable = false)
	private UUID lessonId;

	@Column(name = "period_year", updatable = false)
	private Short periodYear;

	@Column(name = "period_month", updatable = false)
	private Short periodMonth;

	@Column(name = "billing_period_id", updatable = false)
	private UUID billingPeriodId;

	@Column(name = "label", nullable = false, updatable = false)
	private String label;

	@Column(name = "unit_amount", nullable = false, updatable = false, precision = 12, scale = 2)
	private BigDecimal unitAmount;

	@Column(name = "facility_fee_amount", nullable = false, updatable = false, precision = 12, scale = 2)
	private BigDecimal facilityFeeAmount;

	@Column(name = "discount_amount", nullable = false, updatable = false, precision = 12, scale = 2)
	private BigDecimal discountAmount;

	@Column(name = "line_total", nullable = false, updatable = false, precision = 12, scale = 2)
	private BigDecimal lineTotal;

	@Column(name = "currency", nullable = false, updatable = false, length = 3)
	private String currency;

	@Column(name = "coverage_key", nullable = false, updatable = false, length = 120)
	private String coverageKey;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	/**
	 * DISC-01 (V92): the {@code student_order_discount} of THIS order that
	 * produced {@code discountAmount} ({@code null} when undiscounted).
	 */
	@Column(name = "order_discount_id", updatable = false)
	private UUID orderDiscountId;

	/** DISC-01 (V92, D2): a free-course reward line ({@code discountAmount = unitAmount}). */
	@Column(name = "reward", nullable = false, updatable = false)
	private boolean reward;

	protected StudentOrderLine() {
	}

	/** DISC-01: a line with its discount link and reward marker (V92 CHECKs enforce the shape). */
	public StudentOrderLine(UUID tenantId, UUID orderId, int lineNo, String scope, UUID courseId, UUID moduleId,
			UUID lessonId, Integer periodYear, Integer periodMonth, UUID billingPeriodId, String label,
			BigDecimal unitAmount, BigDecimal facilityFeeAmount, BigDecimal discountAmount, String currency,
			String coverageKey, Instant createdAt, UUID orderDiscountId, boolean reward) {
		this(tenantId, orderId, lineNo, scope, courseId, moduleId, lessonId, periodYear, periodMonth, billingPeriodId,
				label, unitAmount, facilityFeeAmount, discountAmount, currency, coverageKey, createdAt);
		this.orderDiscountId = orderDiscountId;
		this.reward = reward;
	}

	public StudentOrderLine(UUID tenantId, UUID orderId, int lineNo, String scope, UUID courseId, UUID moduleId,
			UUID lessonId, Integer periodYear, Integer periodMonth, UUID billingPeriodId, String label,
			BigDecimal unitAmount, BigDecimal facilityFeeAmount, BigDecimal discountAmount, String currency,
			String coverageKey, Instant createdAt) {
		this.tenantId = tenantId;
		this.orderId = orderId;
		this.lineNo = lineNo;
		this.scope = scope;
		this.courseId = courseId;
		this.moduleId = moduleId;
		this.lessonId = lessonId;
		this.periodYear = periodYear == null ? null : periodYear.shortValue();
		this.periodMonth = periodMonth == null ? null : periodMonth.shortValue();
		this.billingPeriodId = billingPeriodId;
		this.label = label;
		this.unitAmount = unitAmount;
		this.facilityFeeAmount = facilityFeeAmount;
		this.discountAmount = discountAmount;
		this.lineTotal = unitAmount.add(facilityFeeAmount).subtract(discountAmount);
		this.currency = currency;
		this.coverageKey = coverageKey;
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

	public int getLineNo() {
		return lineNo;
	}

	public String getScope() {
		return scope;
	}

	public UUID getCourseId() {
		return courseId;
	}

	public UUID getModuleId() {
		return moduleId;
	}

	public UUID getLessonId() {
		return lessonId;
	}

	public Integer getPeriodYear() {
		return periodYear == null ? null : periodYear.intValue();
	}

	public Integer getPeriodMonth() {
		return periodMonth == null ? null : periodMonth.intValue();
	}

	public UUID getBillingPeriodId() {
		return billingPeriodId;
	}

	public String getLabel() {
		return label;
	}

	public BigDecimal getUnitAmount() {
		return unitAmount;
	}

	public BigDecimal getFacilityFeeAmount() {
		return facilityFeeAmount;
	}

	public BigDecimal getDiscountAmount() {
		return discountAmount;
	}

	public BigDecimal getLineTotal() {
		return lineTotal;
	}

	public String getCurrency() {
		return currency;
	}

	public String getCoverageKey() {
		return coverageKey;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public UUID getOrderDiscountId() {
		return orderDiscountId;
	}

	public boolean isReward() {
		return reward;
	}

}
