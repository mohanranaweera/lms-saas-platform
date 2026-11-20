package com.lms.coursemanagement.api;

import java.util.UUID;

/**
 * A course tag as other modules may see it (R2-D6): id and display name only -
 * never the normalized name or any other internal column.
 */
public record CourseTagRef(UUID id, String name) {

}
