package com.lms.coursemanagement.course.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import com.lms.coursemanagement.api.LessonPaymentType;
import com.lms.coursemanagement.api.LessonResourceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Structural child entity of {@link CourseModule}, mapped 1:1 onto {@code
 * course_lesson} (V11). No material content lives here - that is {@code
 * content-management}'s table, once it exists, referencing this entity's id
 * by FK from its own side (per {@code docs/plans/MVP-008 Course
 * Management.md} §7).
 *
 * <p>V85 (D03) adds the admin-UI "Session" fields: an optional time window,
 * a resource type (metadata only - see {@code LessonResourceTypeCatalog}), a
 * leaf payment policy with a PAID-only price, an optional label/description
 * and a server-derived billing month for Monthly covered sessions
 * (both-or-neither). The CHECK constraints in
 * V85 mirror {@code CourseLessonService}'s validation, which runs first and
 * returns stable 400 codes.
 */
@Entity
@Table(name = "course_lesson")
public class CourseLesson extends Auditable implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "module_id", nullable = false, updatable = false)
	private UUID moduleId;

	@Column(name = "title", nullable = false)
	private String title;

	@Column(name = "sequence", nullable = false)
	private Integer sequence;

	@Column(name = "starts_at")
	private Instant startsAt;

	@Column(name = "ends_at")
	private Instant endsAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "resource_type", length = 40)
	private LessonResourceType resourceType;

	@Enumerated(EnumType.STRING)
	@Column(name = "payment_type", nullable = false, length = 30)
	private LessonPaymentType paymentType = LessonPaymentType.COVERED_BY_CLASS_FEES;

	@Column(name = "price", precision = 12, scale = 2)
	private BigDecimal price;

	@Column(name = "description")
	private String description;

	@Column(name = "lesson_label")
	private String lessonLabel;

	@Column(name = "billing_year")
	private Short billingYear;

	@Column(name = "billing_month")
	private Short billingMonth;

	protected CourseLesson() {
	}

	public CourseLesson(UUID tenantId, UUID moduleId, String title, Integer sequence) {
		this.tenantId = tenantId;
		this.moduleId = moduleId;
		this.title = title;
		this.sequence = sequence;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getModuleId() {
		return moduleId;
	}

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public Integer getSequence() {
		return sequence;
	}

	public void setSequence(Integer sequence) {
		this.sequence = sequence;
	}

	public Instant getStartsAt() {
		return startsAt;
	}

	public Instant getEndsAt() {
		return endsAt;
	}

	/** Callers must validate {@code endsAt > startsAt} first (CourseLessonService). */
	public void setSchedule(Instant startsAt, Instant endsAt) {
		this.startsAt = startsAt;
		this.endsAt = endsAt;
	}

	public LessonResourceType getResourceType() {
		return resourceType;
	}

	public void setResourceType(LessonResourceType resourceType) {
		this.resourceType = resourceType;
	}

	public LessonPaymentType getPaymentType() {
		return paymentType;
	}

	public BigDecimal getPrice() {
		return price;
	}

	/**
	 * Sets the payment policy and price together so the PAID-only-price rule
	 * can never be half-applied. Callers must validate first
	 * (CourseLessonService); V85's {@code ck_course_lesson_price} is the backstop.
	 */
	public void setPaymentPolicy(LessonPaymentType paymentType, BigDecimal price) {
		this.paymentType = paymentType == null ? LessonPaymentType.COVERED_BY_CLASS_FEES : paymentType;
		this.price = price;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public String getLessonLabel() {
		return lessonLabel;
	}

	public void setLessonLabel(String lessonLabel) {
		this.lessonLabel = lessonLabel;
	}

	public Integer getBillingYear() {
		return billingYear == null ? null : billingYear.intValue();
	}

	public Integer getBillingMonth() {
		return billingMonth == null ? null : billingMonth.intValue();
	}

	/** Both-or-neither; callers validate ranges first (CourseLessonService). */
	public void setBillingMonth(Integer year, Integer month) {
		this.billingYear = year == null ? null : year.shortValue();
		this.billingMonth = month == null ? null : month.shortValue();
	}

	/** {@code true} when the session has an end time that has already passed. */
	public boolean isExpired(Instant now) {
		return endsAt != null && endsAt.isBefore(now);
	}

}
