package com.lms.coursemanagement.api;

import java.util.UUID;

/**
 * Resolved module/course/teacher/publish-state for a single {@code
 * course_lesson}, scoped to the caller's own tenant context exactly like
 * every other {@link CourseLookupApi} read - see that interface's javadoc.
 * Added for content-management (MVP-009).
 */
public record LessonOwnership(UUID lessonId, UUID moduleId, UUID courseId, UUID teacherId, boolean coursePublished) {

}
