package com.lms.exammanagement.web.dto;

import jakarta.validation.constraints.Size;
import java.util.UUID;

/** One answer of {@link QuestionUpsertRequest}: {@code id} = keep an existing answer (update only). */
public record QuestionOptionUpsertRequest(UUID id, @Size(max = 200_000) String html, Boolean correct) {

}
