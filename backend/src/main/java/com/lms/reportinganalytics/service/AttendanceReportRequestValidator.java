package com.lms.reportinganalytics.service;

import com.lms.common.api.FieldError;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.coursemanagement.api.CourseSummary;
import com.lms.reportinganalytics.config.AttendanceReportProperties;
import com.lms.reportinganalytics.web.dto.AttendanceRunRequest;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Server-side validation for the RPT-03 request. */
@Component
public class AttendanceReportRequestValidator {

	public static final String DEFAULT_REPORT_NAME = "Attendance Report";

	public static final int MAX_REPORT_NAME_LENGTH = 255;

	private final CourseLookupApi courseLookupApi;

	private final AttendanceReportProperties properties;

	public AttendanceReportRequestValidator(CourseLookupApi courseLookupApi, AttendanceReportProperties properties) {
		this.courseLookupApi = courseLookupApi;
		this.properties = properties;
	}

	public AttendanceCriteria validate(AttendanceRunRequest request) {
		ListBuilder errors = new ListBuilder();
		if (request == null) {
			throw new ReportValidationException(java.util.List.of(new FieldError("body", "is required")));
		}
		if (request.courseId() == null) {
			errors.add("courseId", "is required");
		}
		LocalDate start = parseDate(request.startDate(), "startDate", errors);
		LocalDate end = parseDate(request.endDate(), "endDate", errors);
		if (start != null && end != null) {
			if (start.isAfter(end)) {
				errors.add("endDate", "must be on or after startDate");
			}
			else if (ChronoUnit.DAYS.between(start, end) + 1 > properties.getMaxDateColumns()) {
				errors.add("endDate", "date range must not exceed " + properties.getMaxDateColumns() + " days");
			}
		}
		String title = request.reportName() == null || request.reportName().isBlank() ? DEFAULT_REPORT_NAME
				: request.reportName().trim();
		if (title.length() > MAX_REPORT_NAME_LENGTH) {
			errors.add("reportName", "must be at most " + MAX_REPORT_NAME_LENGTH + " characters");
		}
		if (errors.hasErrors()) {
			throw new ReportValidationException(errors.values());
		}
		CourseSummary course = courseLookupApi.getCourseSummaries(Set.of(request.courseId()))
			.stream()
			.findFirst()
			.orElse(null);
		if (course == null) {
			throw new ReportValidationException(java.util.List.of(new FieldError("courseId", "course not found")));
		}
		return new AttendanceCriteria(request.courseId(), start, end, title);
	}

	private static LocalDate parseDate(String value, String field, ListBuilder errors) {
		if (value == null || value.isBlank()) {
			errors.add(field, "is required");
			return null;
		}
		try {
			return LocalDate.parse(value.trim());
		}
		catch (DateTimeException ex) {
			errors.add(field, "must be an ISO date (yyyy-MM-dd)");
			return null;
		}
	}

	private static final class ListBuilder {

		private final ArrayList<FieldError> errors = new ArrayList<>();

		void add(String field, String message) {
			errors.add(new FieldError(field, message));
		}

		boolean hasErrors() {
			return !errors.isEmpty();
		}

		java.util.List<FieldError> values() {
			return java.util.List.copyOf(errors);
		}

	}

}
