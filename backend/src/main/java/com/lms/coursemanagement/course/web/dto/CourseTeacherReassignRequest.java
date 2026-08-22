package com.lms.coursemanagement.course.web.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CourseTeacherReassignRequest(@NotNull UUID teacherId) {

}
