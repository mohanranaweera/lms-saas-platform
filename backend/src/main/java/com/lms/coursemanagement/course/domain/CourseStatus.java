package com.lms.coursemanagement.course.domain;

/**
 * {@code course.status} (V11) - both lifecycle and visibility for a course;
 * the spec never defines a separate axis, so a second column was not
 * invented (see {@code docs/plans/MVP-008 Course Management.md} §7). A
 * course defaults to {@link #DRAFT} and only becomes visible on the public
 * storefront once explicitly set to {@link #PUBLIC} via {@code
 * CourseService#publish}.
 */
public enum CourseStatus {

	DRAFT, PRIVATE, PUBLIC

}
