package com.lms.coursemanagement.course.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Composite primary key of {@link CourseTagAssignment} - V85's {@code pk_course_tag_assignment (course_id, tag_id)}. */
@Embeddable
public class CourseTagAssignmentId implements Serializable {

	@Column(name = "course_id", nullable = false, updatable = false)
	private UUID courseId;

	@Column(name = "tag_id", nullable = false, updatable = false)
	private UUID tagId;

	protected CourseTagAssignmentId() {
	}

	public CourseTagAssignmentId(UUID courseId, UUID tagId) {
		this.courseId = courseId;
		this.tagId = tagId;
	}

	public UUID getCourseId() {
		return courseId;
	}

	public UUID getTagId() {
		return tagId;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof CourseTagAssignmentId other)) {
			return false;
		}
		return Objects.equals(courseId, other.courseId) && Objects.equals(tagId, other.tagId);
	}

	@Override
	public int hashCode() {
		return Objects.hash(courseId, tagId);
	}

}
