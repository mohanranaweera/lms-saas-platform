package com.lms.reportinganalytics.service;

import com.lms.common.api.FieldError;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.coursemanagement.api.CourseSummary;
import com.lms.ledgersettlementmanagement.api.PaymentMethod;
import com.lms.reportinganalytics.service.support.ReportPeriodRules;
import com.lms.reportinganalytics.service.support.ReportPeriodRules.Period;
import com.lms.reportinganalytics.web.dto.StudentPaymentRunRequest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Server-side validation of the RPT-02 filter (spec section 2). Every failure
 * is a {@code 422 REPORT_VALIDATION} carrying one field error per problem.
 * Course ids are resolved through the tenant-scoped {@link CourseLookupApi}, so
 * a course of another tenant gets exactly the same error as an unknown id
 * (nothing about the other tenant leaks). The period and payment-method rules
 * are the ones RPT-01 uses ({@link ReportPeriodRules}).
 */
@Component
public class StudentPaymentRequestValidator {

	static final int MAX_COURSES = 50;

	private final CourseLookupApi courseLookupApi;

	public StudentPaymentRequestValidator(CourseLookupApi courseLookupApi) {
		this.courseLookupApi = courseLookupApi;
	}

	public StudentPaymentCriteria validate(StudentPaymentRunRequest request) {
		if (request == null) {
			throw new ReportValidationException(List.of(new FieldError("body", "is required")));
		}
		List<FieldError> errors = new ArrayList<>();
		List<UUID> courseIds = validateCourses(request.courseIds(), errors);
		PaymentMethod method = ReportPeriodRules.parseMethod(request.paymentMethod(), errors);
		Period period = ReportPeriodRules.parsePeriod(request.periodMode(), request.months(), request.startDate(),
				request.endDate(), errors);
		if (errors.isEmpty() && courseIds != null && !courseIds.isEmpty()) {
			verifyCoursesBelongToTenant(courseIds, errors);
		}
		if (!errors.isEmpty()) {
			throw new ReportValidationException(errors);
		}
		return new StudentPaymentCriteria(courseIds, method, period);
	}

	private static List<UUID> validateCourses(List<UUID> ids, List<FieldError> errors) {
		if (ids == null || ids.isEmpty()) {
			return List.of();
		}
		if (ids.stream().anyMatch(Objects::isNull)) {
			errors.add(new FieldError("courseIds", "must not contain empty values"));
			return null;
		}
		List<UUID> distinct = List.copyOf(new LinkedHashSet<>(ids));
		if (distinct.size() > MAX_COURSES) {
			errors.add(new FieldError("courseIds", "at most " + MAX_COURSES + " courses can be selected"));
			return null;
		}
		return distinct;
	}

	private void verifyCoursesBelongToTenant(List<UUID> courseIds, List<FieldError> errors) {
		Set<UUID> found = courseLookupApi.getCourseSummaries(Set.copyOf(courseIds))
			.stream()
			.map(CourseSummary::id)
			.collect(Collectors.toSet());
		for (int i = 0; i < courseIds.size(); i++) {
			if (!found.contains(courseIds.get(i))) {
				errors.add(new FieldError("courseIds[" + i + "]", "course not found"));
			}
		}
	}

}
