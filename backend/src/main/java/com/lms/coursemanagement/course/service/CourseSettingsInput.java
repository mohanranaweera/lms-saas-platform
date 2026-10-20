package com.lms.coursemanagement.course.service;

import com.lms.coursemanagement.course.domain.CoursePricingModel;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * KLASS-T4a "Add New Course" / edit settings shared by {@link NewCourseCommand}
 * and {@link CourseEditCommand}. Every field is optional:
 * <ul>
 * <li>On create, {@code null} means "use the default".</li>
 * <li>On edit, {@code null} means "leave unchanged"; an empty list clears
 * tags/links; an empty string clears an invite link. {@code teacherIds} may
 * never be emptied.</li>
 * </ul>
 * {@code teacherIds} is honoured for staff callers only (first = primary);
 * {@code paymentValidDays} {@code 0} means "no limit" (stored as {@code NULL},
 * since V11 requires {@code access_duration_days > 0}).
 */
public record CourseSettingsInput(List<UUID> teacherIds, CoursePricingModel pricingModel, BigDecimal enrollmentFee,
		Integer paymentValidDays, BigDecimal commissionRate, String whatsappInviteUrl, String telegramInviteUrl,
		List<String> tagNames, List<UUID> linkedCourseIds) {

	public static final CourseSettingsInput NONE = new CourseSettingsInput(null, null, null, null, null, null, null,
			null, null);

}
