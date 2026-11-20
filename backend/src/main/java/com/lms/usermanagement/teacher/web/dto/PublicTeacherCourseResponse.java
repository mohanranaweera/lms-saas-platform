package com.lms.usermanagement.teacher.web.dto;

import java.util.UUID;

public record PublicTeacherCourseResponse(UUID id, String name, String slug, String category, String coverImageUrl) {
}
