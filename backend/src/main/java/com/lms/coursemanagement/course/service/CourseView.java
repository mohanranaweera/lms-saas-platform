package com.lms.coursemanagement.course.service;

import com.lms.coursemanagement.course.domain.CoursePricingModel;
import com.lms.coursemanagement.course.domain.CourseStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Service-level read result composed from {@link
 * com.lms.coursemanagement.course.domain.Course} - never the JPA entity
 * itself crosses into {@code web}. Mirrors {@code
 * usermanagement.staff.service.StaffAccount}'s role in that module.
 *
 * <p>{@code resolvedAmount}/{@code currency}/{@code requiresManualQuote} are
 * the same pricing-model-aware fields {@code PublicCourseView} exposes on
 * the anonymous storefront (Wave 2 gap fix) - an authenticated caller (e.g.
 * a student on the checkout page, per {@code CourseAccessGuard}'s PUBLIC
 * -course carve-out) needs to see what a {@code MONTHLY}/{@code SESSION}
 * course's current billing period would actually charge before enrolling,
 * not just its {@code pricingModel}. {@code null}/{@code false} when not yet
 * resolvable (e.g. no open billing period configured) - never a silent
 * {@code $0}.
 *
 * <p>KLASS-T4a additions (plan §4.1): registration flag, informational
 * enrollment fee, short-lived cover URL ({@code null} when no cover or the
 * object store is unavailable), invite links, Payment Valid Days (= {@code
 * accessDurationDays}), current commission rate ({@code null} for students),
 * teachers (primary first), tags, linked source courses, display order and
 * {@code priceDisplay} (ONE_TIME price / MONTHLY current period / otherwise
 * {@code null}) and the tenant default {@code timezone} (IANA id, default UTC;
 * lets any course manager render session times without tenant-config access).
 *
 * <p>Multi-course checkout (V90): {@code facilityFeeOverride} ({@code null} =
 * inherit), the tenant default fee / basis, the resulting {@code
 * effectiveFacilityFee} and the course {@code postageFee}.
 */
public record CourseView(UUID id, UUID teacherId, String name, String slug, String category, String subject,
		String stream, String grade, String academicYear, String description, BigDecimal price,
		Integer accessDurationDays, String enrollmentRule, CourseStatus status, CoursePricingModel pricingModel,
		Instant archivedAt, Instant createdAt, Instant updatedAt, BigDecimal resolvedAmount, String currency,
		boolean requiresManualQuote, boolean registrationEnabled, BigDecimal enrollmentFee, String coverImageUrl,
		String whatsappInviteUrl, String telegramInviteUrl, Integer paymentValidDays, BigDecimal commissionRate,
		List<TeacherRef> teachers, List<TagRef> tags, List<LinkedCourseRef> linkedCourses, Integer displayOrder,
		PriceDisplay priceDisplay, String timezone, BigDecimal facilityFeeOverride,
		BigDecimal tenantDefaultFacilityFee, String facilityFeeBasis, BigDecimal effectiveFacilityFee,
		BigDecimal postageFee) {

	/** One assigned teacher; {@code name} falls back to the account email when no teacher profile exists. */
	public record TeacherRef(UUID id, String name, boolean primary) {

	}

	public record TagRef(UUID id, String name) {

	}

	public record LinkedCourseRef(UUID id, String title) {

	}

	/** {@code period} is {@code "MONTH"} for a MONTHLY course, else {@code null}. */
	public record PriceDisplay(BigDecimal amount, String currency, String period) {

	}

}
