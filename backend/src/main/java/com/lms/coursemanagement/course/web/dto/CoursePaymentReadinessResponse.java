package com.lms.coursemanagement.course.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** {@code GET /api/v1/courses/{courseId}/payment-readiness} - see docs/api/course-billing.md. */
public record CoursePaymentReadinessResponse(String pricingModel, boolean payable, List<Issue> issues) {

	public record Issue(String code, String message, @JsonInclude(JsonInclude.Include.NON_NULL) Integer count) {

	}

}
