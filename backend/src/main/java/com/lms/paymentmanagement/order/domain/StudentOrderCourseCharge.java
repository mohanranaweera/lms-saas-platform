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
 * V90 {@code student_order_course_charge}: the per-course facility-fee and
 * postage figures in effect when a payment-composer order was created - one
 * row per (order, course). Immutable and insert-only (every column {@code
 * updatable = false}, {@link Immutable}, repository refuses deletes), so a
 * later tenant-default / course-override / postage change never alters an
 * existing order. {@code courseId} is an opaque cross-module id (composite
 * tenant FK in V90).
 *
 * <p>{@code feeBasis} is the tenant facility-fee mode at order time ({@code
 * NONE|PER_COURSE|PER_LINE|PER_ORDER}); {@code facilityFeeSource} is {@code
 * COURSE_OVERRIDE|TENANT_DEFAULT|NOT_APPLICABLE}; {@code postageAmount}
 * equals {@code postageFee} when selected, else 0 (V90 CHECK).
 */
@Entity
@Immutable
@Table(name = "student_order_course_charge")
public class StudentOrderCourseCharge extends BaseEntity implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "order_id", nullable = false, updatable = false)
	private UUID orderId;

	@Column(name = "course_id", nullable = false, updatable = false)
	private UUID courseId;

	@Column(name = "currency", nullable = false, updatable = false, length = 3)
	private String currency;

	@Column(name = "fee_basis", nullable = false, updatable = false, length = 20)
	private String feeBasis;

	@Column(name = "facility_fee_source", nullable = false, updatable = false, length = 20)
	private String facilityFeeSource;

	@Column(name = "effective_facility_fee", nullable = false, updatable = false, precision = 12, scale = 2)
	private BigDecimal effectiveFacilityFee;

	@Column(name = "facility_fee_amount", nullable = false, updatable = false, precision = 12, scale = 2)
	private BigDecimal facilityFeeAmount;

	@Column(name = "postage_selected", nullable = false, updatable = false)
	private boolean postageSelected;

	@Column(name = "postage_fee", nullable = false, updatable = false, precision = 12, scale = 2)
	private BigDecimal postageFee;

	@Column(name = "postage_amount", nullable = false, updatable = false, precision = 12, scale = 2)
	private BigDecimal postageAmount;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected StudentOrderCourseCharge() {
	}

	public StudentOrderCourseCharge(UUID tenantId, UUID orderId, UUID courseId, String currency, String feeBasis,
			String facilityFeeSource, BigDecimal effectiveFacilityFee, BigDecimal facilityFeeAmount,
			boolean postageSelected, BigDecimal postageFee, Instant createdAt) {
		this.tenantId = tenantId;
		this.orderId = orderId;
		this.courseId = courseId;
		this.currency = currency;
		this.feeBasis = feeBasis;
		this.facilityFeeSource = facilityFeeSource;
		this.effectiveFacilityFee = effectiveFacilityFee;
		this.facilityFeeAmount = facilityFeeAmount;
		this.postageSelected = postageSelected;
		this.postageFee = postageFee;
		this.postageAmount = postageSelected ? postageFee : BigDecimal.ZERO.setScale(2);
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

	public UUID getCourseId() {
		return courseId;
	}

	public String getCurrency() {
		return currency;
	}

	public String getFeeBasis() {
		return feeBasis;
	}

	public String getFacilityFeeSource() {
		return facilityFeeSource;
	}

	public BigDecimal getEffectiveFacilityFee() {
		return effectiveFacilityFee;
	}

	public BigDecimal getFacilityFeeAmount() {
		return facilityFeeAmount;
	}

	public boolean isPostageSelected() {
		return postageSelected;
	}

	public BigDecimal getPostageFee() {
		return postageFee;
	}

	public BigDecimal getPostageAmount() {
		return postageAmount;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
