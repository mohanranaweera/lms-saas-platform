package com.lms.exammanagement.web.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record QuestionAnswerResultResponse(UUID questionId, String response, BigDecimal autoScore,
		BigDecimal manualScore) {

}
