package com.lms.reportinganalytics.service;

import com.lms.common.api.FieldError;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.reportinganalytics.web.dto.CourseSummaryRunRequest;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Server-side shape and tenant-scoped teacher/course validation for RPT-05. */
@Component
public class CourseSummaryRequestValidator {

	public static final String DEFAULT_REPORT_NAME = "Teacher Report";

	public static final int MAX_REPORT_NAME_LENGTH = 255;

	private final CourseLookupApi courseLookupApi;

	public CourseSummaryRequestValidator(CourseLookupApi courseLookupApi) {
		this.courseLookupApi = courseLookupApi;
	}

	public CourseSummaryCriteria validate(CourseSummaryRunRequest request) {
		Errors errors = new Errors();
		if (request == null) {
			throw new ReportValidationException(List.of(new FieldError("body", "is required")));
		}
		if (request.teacherId() == null) {
			errors.add("teacherId", "is required");
		}
		if (request.courseId() == null) {
			errors.add("courseId", "is required");
		}
		LocalDate start = date(request.startDate(), "startDate", errors);
		LocalDate end = date(request.endDate(), "endDate", errors);
		YearMonth month = month(request.month(), errors);
		if (start != null && end != null && start.isAfter(end)) {
			errors.add("endDate", "must be on or after startDate");
		}
		if (month != null) {
			if (start != null && !YearMonth.from(start).equals(month)) {
				errors.add("month", "must match startDate's calendar month");
			}
			if (end != null && !YearMonth.from(end).equals(month)) {
				errors.add("month", "must match endDate's calendar month");
			}
		}
		String title = request.reportName() == null || request.reportName().isBlank() ? DEFAULT_REPORT_NAME
				: request.reportName().trim();
		if (title.length() > MAX_REPORT_NAME_LENGTH) {
			errors.add("reportName", "must be at most " + MAX_REPORT_NAME_LENGTH + " characters");
		}
		if (errors.any()) {
			throw new ReportValidationException(errors.values());
		}
		UUID assignedTeacher = courseLookupApi.getTeacherId(request.courseId()).orElse(null);
		if (assignedTeacher == null || !assignedTeacher.equals(request.teacherId())) {
			throw new ReportValidationException(List.of(new FieldError("courseId", "course is not available for selected teacher")));
		}
		return new CourseSummaryCriteria(request.teacherId(), request.courseId(), start, end, month, title);
	}

	private static LocalDate date(String value, String field, Errors errors) {
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

	private static YearMonth month(String value, Errors errors) {
		if (value == null || value.isBlank()) {
			errors.add("month", "is required");
			return null;
		}
		try {
			return YearMonth.parse(value.trim());
		}
		catch (DateTimeException ex) {
			errors.add("month", "must be an ISO month (yyyy-MM)");
			return null;
		}
	}

	private static final class Errors {

		private final List<FieldError> values = new ArrayList<>();

		void add(String field, String message) {
			values.add(new FieldError(field, message));
		}

		boolean any() {
			return !values.isEmpty();
		}

		List<FieldError> values() {
			return List.copyOf(values);
		}

	}

}
