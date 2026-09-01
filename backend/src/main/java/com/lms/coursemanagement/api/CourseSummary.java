package com.lms.coursemanagement.api;

import java.util.UUID;

/**
 * The minimal, non-sensitive display fields of a course exposed to other
 * domains via {@link CourseLookupApi#getCourseSummaries(java.util.Set)} - this is a
 * display-name lookup, not a general course-detail leak. Deliberately
 * excludes {@code price}, {@code teacherId}, {@code description}, and
 * {@code status}; a caller needing any of those has its own narrow method on
 * {@link CourseLookupApi} to use instead.
 */
public record CourseSummary(UUID id, String name, String slug, String category) {

}
