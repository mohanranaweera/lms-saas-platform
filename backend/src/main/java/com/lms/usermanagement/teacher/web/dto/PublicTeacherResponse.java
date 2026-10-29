package com.lms.usermanagement.teacher.web.dto;

import java.util.List;
import java.util.UUID;

public record PublicTeacherResponse(UUID id, String name, String qualifications, String description,
		String profileImageUrl, List<PublicTeacherCourseResponse> courses) {
}
