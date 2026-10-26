package com.lms.reportinganalytics.service;

import com.lms.common.api.FieldError;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.reportinganalytics.web.dto.UnpaidStudentsRunRequest;
import java.time.DateTimeException;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Server-side validation and tenant course ownership check. */
@Component
public class UnpaidStudentsRequestValidator {

	private final CourseLookupApi courseLookupApi;

	public UnpaidStudentsRequestValidator(CourseLookupApi courseLookupApi) {
		this.courseLookupApi = courseLookupApi;
	}

	public UnpaidStudentsCriteria validate(UnpaidStudentsRunRequest request) {
		if (request == null) {
			throw new ReportValidationException(List.of(new FieldError("body", "is required")));
		}
		List<FieldError> errors = new ArrayList<>();
		if (request.courseId() == null) {
			errors.add(new FieldError("courseId", "is required"));
		}
		YearMonth month = null;
		if (request.billingMonth() == null || request.billingMonth().isBlank()) {
			errors.add(new FieldError("billingMonth", "is required"));
		}
		else {
			try {
				month = YearMonth.parse(request.billingMonth().trim());
			}
			catch (DateTimeException ex) {
				errors.add(new FieldError("billingMonth", "must be an ISO month (yyyy-MM)"));
			}
		}
		if (errors.isEmpty() && courseLookupApi.getCourseSummaries(Set.of(request.courseId())).isEmpty()) {
			errors.add(new FieldError("courseId", "course not found"));
		}
		if (!errors.isEmpty()) {
			throw new ReportValidationException(errors);
		}
		return new UnpaidStudentsCriteria(request.courseId(), month, Boolean.TRUE.equals(request.atLeastOneAttended()));
	}

}
