package com.lms.coursemanagement.course.service;

import java.util.List;
import java.util.UUID;

public record PublicCourseOutline(List<Module> modules) {
	public record Module(UUID id, String title, int sequence, List<Lesson> lessons) {}
	public record Lesson(UUID id, String title, String label, String resourceType, int sequence) {}
}
