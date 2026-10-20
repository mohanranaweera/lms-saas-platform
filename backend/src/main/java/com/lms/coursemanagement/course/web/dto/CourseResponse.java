package com.lms.coursemanagement.course.web.dto;

import com.lms.coursemanagement.course.domain.CoursePricingModel;
import com.lms.coursemanagement.course.domain.CourseStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Public response shape for {@code /api/v1/courses} endpoints - never the
 * {@code Course} JPA entity. Used for both list and detail responses.
 *
 * <p>{@code resolvedAmount}/{@code currency}/{@code requiresManualQuote}: see
 * {@code CourseView}'s javadoc.
 *
 * <p>KLASS-T4a additive fields (plan §4.1): {@code registrationEnabled},
 * {@code enrollmentFee} (informational), {@code coverImageUrl} (short-lived
 * signed URL or {@code null}), {@code whatsappInviteUrl}, {@code
 * telegramInviteUrl}, {@code paymentValidDays} (= accessDurationDays), {@code
 * commissionRate} ({@code null} for students), {@code teachers} ({@code
 * [{id,name,primary}]}, primary first; {@code id} is the tenant_user id),
 * {@code tags} ({@code [{id,name}]}), {@code linkedCourses} ({@code
 * [{id,title}]}), {@code displayOrder}, {@code priceDisplay} ({@code
 * {amount,currency,period:"MONTH"|null}} or {@code null}) and {@code
 * timezone} (tenant default IANA zone).
 *
 * <p>Multi-course checkout (V90): {@code facilityFeeOverride} ({@code null} =
 * inherit), {@code tenantDefaultFacilityFee} + {@code facilityFeeBasis} (the
 * tenant {@code PAYMENT} facility-fee amount and mode), {@code
 * effectiveFacilityFee} (what this course is charged per basis unit; 0 for
 * NONE, tenant default for PER_ORDER) and {@code postageFee}.
 */
public record CourseResponse(UUID id, UUID teacherId, String name, String slug, String category, String subject,
		String stream, String grade, String academicYear, String description, BigDecimal price,
		Integer accessDurationDays, String enrollmentRule, CourseStatus status, CoursePricingModel pricingModel,
		Instant archivedAt, Instant createdAt, Instant updatedAt, BigDecimal resolvedAmount, String currency,
		boolean requiresManualQuote, boolean registrationEnabled, BigDecimal enrollmentFee, String coverImageUrl,
		String whatsappInviteUrl, String telegramInviteUrl, Integer paymentValidDays, BigDecimal commissionRate,
		List<TeacherRef> teachers, List<TagRef> tags, List<LinkedCourseRef> linkedCourses, Integer displayOrder,
		PriceDisplay priceDisplay, String timezone, BigDecimal facilityFeeOverride,
		BigDecimal tenantDefaultFacilityFee, String facilityFeeBasis, BigDecimal effectiveFacilityFee,
		BigDecimal postageFee) {

	public record TeacherRef(UUID id, String name, boolean primary) {

	}

	public record TagRef(UUID id, String name) {

	}

	public record LinkedCourseRef(UUID id, String title) {

	}

	public record PriceDisplay(BigDecimal amount, String currency, String period) {

	}

}
