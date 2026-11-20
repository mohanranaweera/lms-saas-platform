package com.lms.coursemanagement.course.service;

import com.lms.coursemanagement.course.domain.CourseStatus;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Service-level input for {@link CourseService#createCourse}. {@code
 * teacherId}/{@code settings.teacherIds()} are only ever honored when the
 * acting principal is a staff role - for a Teacher-role caller, {@code
 * CourseService} forces the teacher to the caller's own id server-side
 * regardless of what (if anything) is set here, per plan §12/§15.
 *
 * <p>KLASS-T4a: {@code slug}/{@code category} may be {@code null} (derived /
 * defaulted server-side); {@code price} is the ONE_TIME course price or the
 * MONTHLY amount, depending on {@code settings.pricingModel()}.
 */
public record NewCourseCommand(String name, String slug, String category, String subject, String stream,
		String grade, String academicYear, String description, BigDecimal price, Integer accessDurationDays,
		String enrollmentRule, CourseStatus status, UUID teacherId, CourseSettingsInput settings) {

	public NewCourseCommand {
		settings = settings == null ? CourseSettingsInput.NONE : settings;
	}

	/** Pre-KLASS shape (no settings). */
	public NewCourseCommand(String name, String slug, String category, String subject, String stream, String grade,
			String academicYear, String description, BigDecimal price, Integer accessDurationDays,
			String enrollmentRule, CourseStatus status, UUID teacherId) {
		this(name, slug, category, subject, stream, grade, academicYear, description, price, accessDurationDays,
				enrollmentRule, status, teacherId, CourseSettingsInput.NONE);
	}

}
