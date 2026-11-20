package com.lms.coursemanagement.course.service;

import java.math.BigDecimal;

/**
 * Service-level input for {@link CourseService#updateCourse}. Has no {@code
 * status} field - that has its own dedicated write path ({@code
 * publish}/{@code unpublish}).
 *
 * <p>KLASS-T4a: {@code slug}/{@code category} {@code null} = unchanged.
 * {@code price} (optional) and {@code settings} route through the same
 * audited write paths as the dedicated endpoints: a price change goes
 * through {@code course_price_history} (ONE_TIME) or a new billing period
 * (MONTHLY), a pricing model change publishes {@code
 * CoursePricingModelChangedEvent}, and changing the PRIMARY teacher stays
 * Tenant-Admin-only (same rule as {@code reassignTeacher}).
 */
public record CourseEditCommand(String name, String slug, String category, String subject, String stream,
		String grade, String academicYear, String description, String enrollmentRule, Integer accessDurationDays,
		BigDecimal price, CourseSettingsInput settings, CourseChargesInput charges) {

	public CourseEditCommand {
		settings = settings == null ? CourseSettingsInput.NONE : settings;
		charges = charges == null ? CourseChargesInput.NONE : charges;
	}

	/** Pre-V90 shape (no facility-fee override / postage changes). */
	public CourseEditCommand(String name, String slug, String category, String subject, String stream, String grade,
			String academicYear, String description, String enrollmentRule, Integer accessDurationDays,
			BigDecimal price, CourseSettingsInput settings) {
		this(name, slug, category, subject, stream, grade, academicYear, description, enrollmentRule,
				accessDurationDays, price, settings, CourseChargesInput.NONE);
	}

	/** Pre-KLASS shape (no price, no settings). */
	public CourseEditCommand(String name, String slug, String category, String subject, String stream, String grade,
			String academicYear, String description, String enrollmentRule, Integer accessDurationDays) {
		this(name, slug, category, subject, stream, grade, academicYear, description, enrollmentRule,
				accessDurationDays, null, CourseSettingsInput.NONE, CourseChargesInput.NONE);
	}

}
