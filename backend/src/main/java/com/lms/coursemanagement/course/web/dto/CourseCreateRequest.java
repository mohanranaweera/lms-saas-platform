package com.lms.coursemanagement.course.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.lms.coursemanagement.course.domain.CoursePricingModel;
import com.lms.coursemanagement.course.domain.CourseStatus;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Course creation request body (MVP-008, extended by KLASS-T4a). {@code
 * teacherId}/{@code teacherIds} are only ever honored for a staff-role caller
 * - a Teacher-role caller's own id is always used server-side, per plan
 * §12/§15 - see {@code CourseService#createCourse}.
 *
 * <p>{@code status}, if omitted, defaults to {@code DRAFT} server-side.
 *
 * <p>KLASS-T4a (all optional, additive): {@code slug} (derived from the name
 * when absent) and {@code category} (defaults to "General") are no longer
 * required; {@code teacherIds} (tenant_user ids, first = primary; the legacy
 * {@code teacherId} is used when absent); {@code pricingModel}
 * (FREE|ONE_TIME|MONTHLY|MODULE|DAILY|INSTALLMENT, default ONE_TIME);
 * {@code price} (ONE_TIME course price, or the MONTHLY fee); {@code
 * enrollmentFee} (informational only); {@code paymentValidDays} (0 = no
 * limit); {@code commissionRate} (0-100); https invite links; {@code
 * tagNames}; {@code linkedCourseIds}. Money may be sent as a JSON number or
 * a 2-decimal string.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CourseCreateRequest(

		@NotBlank @Size(max = 255) String name,

		@Size(max = 160) @Pattern(regexp = "^$|^[a-z0-9]+(-[a-z0-9]+)*$",
				message = "must be lowercase letters, numbers, and single hyphens only") String slug,

		@Size(max = 100) String category,

		@Size(max = 100) String subject,

		@Size(max = 100) String stream,

		@Size(max = 50) String grade,

		@Size(max = 20) String academicYear,

		@Size(max = 5000) String description,

		@DecimalMin(value = "0.0", inclusive = true) @Digits(integer = 10, fraction = 2) BigDecimal price,

		@Positive Integer accessDurationDays,

		@Size(max = 1000) String enrollmentRule,

		CourseStatus status,

		UUID teacherId,

		@Size(max = 20) List<@NotNull UUID> teacherIds,

		CoursePricingModel pricingModel,

		@DecimalMin(value = "0.0", inclusive = true) @Digits(integer = 10, fraction = 2) BigDecimal enrollmentFee,

		@Min(0) @Max(36500) Integer paymentValidDays,

		@DecimalMin(value = "0.0", inclusive = true) @DecimalMax(value = "100.0",
				inclusive = true) @Digits(integer = 3, fraction = 2) BigDecimal commissionRate,

		@Size(max = 500) String whatsappInviteUrl,

		@Size(max = 500) String telegramInviteUrl,

		@Size(max = 50) List<@NotNull @Size(max = 100) String> tagNames,

		@Size(max = 50) List<@NotNull UUID> linkedCourseIds) {

	/** Pre-KLASS shape. */
	public CourseCreateRequest(String name, String slug, String category, String subject, String stream,
			String grade, String academicYear, String description, BigDecimal price, Integer accessDurationDays,
			String enrollmentRule, CourseStatus status, UUID teacherId) {
		this(name, slug, category, subject, stream, grade, academicYear, description, price, accessDurationDays,
				enrollmentRule, status, teacherId, null, null, null, null, null, null, null, null, null);
	}

}
