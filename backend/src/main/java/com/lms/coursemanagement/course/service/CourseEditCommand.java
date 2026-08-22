package com.lms.coursemanagement.course.service;

/**
 * Service-level input for {@link CourseService#updateCourse}. Deliberately
 * has no {@code teacherId}, {@code price}, or {@code status} field - those
 * have their own dedicated write paths ({@code reassignTeacher}, {@code
 * changePrice}, {@code publish}/{@code unpublish}), per plan §10/§15(c).
 */
public record CourseEditCommand(String name, String slug, String category, String subject, String stream,
		String grade, String academicYear, String description, String enrollmentRule, Integer accessDurationDays) {

}
