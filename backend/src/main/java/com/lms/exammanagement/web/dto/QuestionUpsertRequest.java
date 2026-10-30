package com.lms.exammanagement.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * Module-bank question create / update body (contract {@code QuestionUpsertRequest}).
 * HTML is sanitized server-side; the content rules (non-empty, 2-10 options, exactly one
 * correct, marks range) are enforced by {@code QuestionContentValidator}. No tenant,
 * course or module id is accepted from the body.
 */
public record QuestionUpsertRequest(@Size(max = 200_000) String bodyHtml, BigDecimal marks,
		@Size(max = 200_000) String explanationHtml, @Size(max = 10) List<@Valid QuestionOptionUpsertRequest> options) {

}
