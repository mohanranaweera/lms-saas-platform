package com.lms.exammanagement.web.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** {@code POST .../modules/{moduleId}/questions/copy}: where every question of the source module is copied to. */
public record QuestionCopyRequest(@NotNull UUID targetCourseId, @NotNull UUID targetModuleId) {

}
