package com.lms.coursemanagement.course.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One-per-course billing configuration, mapped 1:1 onto {@code
 * course_billing_configuration} (V38) - a structural child entity of {@link
 * Course}, mirroring {@link com.lms.coursemanagement.course.domain.CourseModule}'s
 * "opaque parent id, not a JPA association" pattern exactly, so every read
 * stays explicitly tenant-scoped through {@code
 * CourseBillingConfigurationRepository}.
 *
 * <p>{@code sessionRate} is only meaningful when the owning course's {@link
 * CoursePricingModel} is {@link CoursePricingModel#SESSION}; {@code
 * requiresManualQuote} is only meaningful when it is {@link
 * CoursePricingModel#CUSTOM}. This entity does not itself enforce that
 * relationship (it has no reference back to {@code course.pricing_model}) -
 * {@code BillingConfigurationService#createOrUpdateConfiguration} is the sole
 * write path and performs that validation before ever constructing/updating a
 * row here.
 */
@Entity
@Table(name = "course_billing_configuration")
public class CourseBillingConfiguration extends Auditable implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "course_id", nullable = false, updatable = false)
	private UUID courseId;

	@Column(name = "session_rate", precision = 12, scale = 2)
	private BigDecimal sessionRate;

	@Column(name = "currency", nullable = false, length = 3)
	private String currency;

	@Column(name = "requires_manual_quote", nullable = false)
	private boolean requiresManualQuote;

	protected CourseBillingConfiguration() {
	}

	public CourseBillingConfiguration(UUID tenantId, UUID courseId, BigDecimal sessionRate, String currency,
			boolean requiresManualQuote) {
		this.tenantId = tenantId;
		this.courseId = courseId;
		this.sessionRate = sessionRate;
		this.currency = currency;
		this.requiresManualQuote = requiresManualQuote;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getCourseId() {
		return courseId;
	}

	public BigDecimal getSessionRate() {
		return sessionRate;
	}

	public String getCurrency() {
		return currency;
	}

	public boolean isRequiresManualQuote() {
		return requiresManualQuote;
	}

	/**
	 * The only mutator - called exclusively by {@code
	 * BillingConfigurationService#createOrUpdateConfiguration} when a
	 * configuration already exists for the course (an "update", never a
	 * second row - V38's {@code UNIQUE (tenant_id, course_id)} enforces one
	 * configuration per course at the schema level too).
	 */
	public void update(BigDecimal sessionRate, String currency, boolean requiresManualQuote) {
		this.sessionRate = sessionRate;
		this.currency = currency;
		this.requiresManualQuote = requiresManualQuote;
	}

}
