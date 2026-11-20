package com.lms.coursemanagement.api;

import java.util.UUID;

/**
 * Resolved module/course/teacher/publish-state for a single {@code
 * course_lesson}, scoped to the caller's own tenant context exactly like
 * every other {@link CourseLookupApi} read - see that interface's javadoc.
 * Added for content-management (MVP-009). {@code courseOpenToEnrolledStudents}
 * is {@code true} when the course is {@code PUBLIC} or {@code PRIVATE} (not
 * {@code DRAFT}) - enrolled Students may read its content only then.
 */
public record LessonOwnership(UUID lessonId, UUID moduleId, UUID courseId, UUID teacherId, boolean courseOpenToEnrolledStudents) {

}
