package com.lms.coursemanagement.course.service;

import com.lms.coursemanagement.course.domain.CourseStatus;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Service-level input for {@link CourseService#createCourse}. {@code
 * teacherId} is only ever honored when the acting principal is a staff
 * role - for a Teacher-role caller, {@code CourseService} forces {@code
 * teacherId} to the caller's own id server-side regardless of what (if
 * anything) is set here, per plan §12/§15.
 */
public record NewCourseCommand(String name, String slug, String category, String subject, String stream,
		String grade, String academicYear, String description, BigDecimal price, Integer accessDurationDays,
		String enrollmentRule, CourseStatus status, UUID teacherId) {

}
