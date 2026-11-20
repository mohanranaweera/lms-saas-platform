package com.lms.usermanagement.teacher.web.dto;

import java.util.List;

public record TeacherPageResponse(List<TeacherResponse> content, int page, int size, long totalElements, int totalPages) {
}
