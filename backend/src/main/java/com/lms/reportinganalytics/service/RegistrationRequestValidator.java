package com.lms.reportinganalytics.service;

import com.lms.common.api.FieldError;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.reportinganalytics.web.dto.RegistrationRunRequest;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Strict server validation for Registration Report filters. */
@Component
public class RegistrationRequestValidator {
	private static final int MAX_COURSES = 50;
	private static final long MAX_DAYS = 366;
	private static final Set<String> METHODS = Set.of("SELF_REGISTERED", "ADMIN_CREATED");
	private final CourseLookupApi courseLookupApi;
	public RegistrationRequestValidator(CourseLookupApi courseLookupApi) { this.courseLookupApi = courseLookupApi; }
	public RegistrationCriteria validate(RegistrationRunRequest request) {
		if (request == null) throw new ReportValidationException(List.of(new FieldError("body", "is required")));
		List<FieldError> errors = new ArrayList<>();
		List<UUID> courses = request.courseIds() == null ? List.of() : List.copyOf(new LinkedHashSet<>(request.courseIds()));
		if (courses.stream().anyMatch(java.util.Objects::isNull)) errors.add(new FieldError("courseIds", "must not contain null"));
		if (courses.size() > MAX_COURSES) errors.add(new FieldError("courseIds", "must contain at most " + MAX_COURSES + " courses"));
		LocalDate start = parse(request.startDate(), "startDate", errors);
		LocalDate end = parse(request.endDate(), "endDate", errors);
		if (start != null && end != null) {
			if (start.isAfter(end)) errors.add(new FieldError("endDate", "must be on or after start date"));
			else if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_DAYS) errors.add(new FieldError("endDate", "date range must not exceed " + MAX_DAYS + " days"));
		}
		String method = blankToNull(request.registrationMethod());
		if (method != null && !METHODS.contains(method)) errors.add(new FieldError("registrationMethod", "is not supported"));
		if (errors.isEmpty() && !courses.isEmpty() && courseLookupApi.getCourseSummaries(Set.copyOf(courses)).size() != courses.size())
			errors.add(new FieldError("courseIds", "contains an unknown course"));
		if (!errors.isEmpty()) throw new ReportValidationException(errors);
		return new RegistrationCriteria(courses, request.operatorId(), method, start, end);
	}
	private static LocalDate parse(String value, String field, List<FieldError> errors) {
		if (value == null || value.isBlank()) { errors.add(new FieldError(field, "is required")); return null; }
		try { return LocalDate.parse(value.trim()); }
		catch (DateTimeException ex) { errors.add(new FieldError(field, "must be an ISO date (yyyy-MM-dd)")); return null; }
	}
	private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
