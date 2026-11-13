package com.lms.coursemanagement.course.web.dto;

/** Optional body of publish/unpublish: when sent, must equal the variant's current version. */
public record CourseLocalizationVersionRequest(Long expectedVersion) {

}
