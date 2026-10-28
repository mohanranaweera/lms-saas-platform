package com.lms.usermanagement.teacher.web.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.UUID;

public record TeacherOrderRequest(@NotEmpty List<UUID> teacherIds) {
}
