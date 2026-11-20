package com.lms.coursemanagement.course.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.lms.coursemanagement.course.domain.CoursePricingModel;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Course edit request body ({@code PATCH /api/v1/courses/{id}}). Never
 * accepts {@code status} or the legacy single {@code teacherId} - those have
 * their own endpoints ({@code POST .../publish}, {@code POST .../teacher}).
 *
 * <p>{@code name} is optional (KLASS-T8b): when present it must be non-blank
 * (trimmed) and legacy fields keep full-replacement semantics, except {@code
 * slug}/{@code category}, which are optional ({@code null} = unchanged); a
 * partial body without {@code name} changes only the fields it carries.
 * KLASS-T4a fields are optional with "{@code null} = unchanged"; an empty
 * list clears tags/links; an empty string clears an invite link; {@code
 * teacherIds} changes go through the same rules as teacher reassignment
 * (changing the primary = Tenant Admin only). {@code price} is the ONE_TIME
 * price or MONTHLY fee and is applied through the audited price paths.
 *
 * <p>V90 (multi-course checkout): {@code facilityFeeInherit} ({@code true} =
 * clear the override and inherit the tenant default), {@code
 * facilityFeeOverride} (&gt;= 0, required when {@code facilityFeeInherit} is
 * {@code false}) and {@code postageFee} (&gt;= 0); absent = unchanged; audited
 * with before/after values.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CourseUpdateRequest(

		@Size(max = 255) String name,

		@Size(max = 160) @Pattern(regexp = "^$|^[a-z0-9]+(-[a-z0-9]+)*$",
				message = "must be lowercase letters, numbers, and single hyphens only") String slug,

		@Size(max = 100) String category,

		@Size(max = 100) String subject,

		@Size(max = 100) String stream,

		@Size(max = 50) String grade,

		@Size(max = 20) String academicYear,

		@Size(max = 5000) String description,

		@Size(max = 1000) String enrollmentRule,

		@Positive Integer accessDurationDays,

		@Size(max = 20) List<@NotNull UUID> teacherIds,

		CoursePricingModel pricingModel,

		@DecimalMin(value = "0.0", inclusive = true) @Digits(integer = 10, fraction = 2) BigDecimal price,

		@DecimalMin(value = "0.0", inclusive = true) @Digits(integer = 10, fraction = 2) BigDecimal enrollmentFee,

		@Min(0) @Max(36500) Integer paymentValidDays,

		@DecimalMin(value = "0.0", inclusive = true) @DecimalMax(value = "100.0",
				inclusive = true) @Digits(integer = 3, fraction = 2) BigDecimal commissionRate,

		@Size(max = 500) String whatsappInviteUrl,

		@Size(max = 500) String telegramInviteUrl,

		@Size(max = 50) List<@NotNull @Size(max = 100) String> tagNames,

		@Size(max = 50) List<@NotNull UUID> linkedCourseIds,

		Boolean facilityFeeInherit,

		@DecimalMin(value = "0.0", inclusive = true) @Digits(integer = 7, fraction = 2) BigDecimal facilityFeeOverride,

		@DecimalMin(value = "0.0", inclusive = true) @Digits(integer = 7, fraction = 2) BigDecimal postageFee) {

	/** Pre-V90 shape (no facility-fee override / postage). */
	public CourseUpdateRequest(String name, String slug, String category, String subject, String stream,
			String grade, String academicYear, String description, String enrollmentRule,
			Integer accessDurationDays, List<UUID> teacherIds, CoursePricingModel pricingModel, BigDecimal price,
			BigDecimal enrollmentFee, Integer paymentValidDays, BigDecimal commissionRate, String whatsappInviteUrl,
			String telegramInviteUrl, List<String> tagNames, List<UUID> linkedCourseIds) {
		this(name, slug, category, subject, stream, grade, academicYear, description, enrollmentRule,
				accessDurationDays, teacherIds, pricingModel, price, enrollmentFee, paymentValidDays, commissionRate,
				whatsappInviteUrl, telegramInviteUrl, tagNames, linkedCourseIds, null, null, null);
	}

	/** Pre-KLASS shape. */
	public CourseUpdateRequest(String name, String slug, String category, String subject, String stream,
			String grade, String academicYear, String description, String enrollmentRule,
			Integer accessDurationDays) {
		this(name, slug, category, subject, stream, grade, academicYear, description, enrollmentRule,
				accessDurationDays, null, null, null, null, null, null, null, null, null, null);
	}

}
