package com.lms.coursemanagement.course.web.dto;

/** {@code PUT /api/v1/courses/{courseId}/localizations/{locale}}; {@code expectedVersion} null = new variant. */
public record CourseLocalizationRequest(String title, String description, Long expectedVersion) {

}
