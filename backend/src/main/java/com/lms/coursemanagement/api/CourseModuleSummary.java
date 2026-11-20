package com.lms.coursemanagement.api;

import java.util.UUID;

/**
 * A module's identity and display data, returned by {@link CourseLookupApi#findModules(UUID)}
 * / {@link CourseLookupApi#findModule(UUID, UUID)} (Klass Exam Module Slice A - module
 * question banks).
 */
public record CourseModuleSummary(UUID moduleId, UUID courseId, String title, int sequence) {

}
