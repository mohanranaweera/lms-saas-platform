package com.lms.reportinganalytics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.coursemanagement.api.CourseSummary;
import com.lms.reportinganalytics.config.AttendanceReportProperties;
import com.lms.reportinganalytics.web.dto.AttendanceRunRequest;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AttendanceReportRequestValidatorTest {

	private CourseLookupApi courseLookupApi;

	private AttendanceReportRequestValidator validator;

	private UUID courseId;

	@BeforeEach
	void setUp() {
		courseLookupApi = mock(CourseLookupApi.class);
		AttendanceReportProperties properties = new AttendanceReportProperties();
		validator = new AttendanceReportRequestValidator(courseLookupApi, properties);
		courseId = UUID.randomUUID();
		when(courseLookupApi.getCourseSummaries(Set.of(courseId)))
			.thenReturn(List.of(new CourseSummary(courseId, "Biology", "biology", "Science")));
	}

	@Test
	void blankReportNameUsesDefault() {
		AttendanceCriteria result = validator.validate(new AttendanceRunRequest(courseId, "2026-10-01", "2026-10-24", "  "));

		assertThat(result.reportName()).isEqualTo("Attendance Report");
	}

	@Test
	void rejectsMissingCourseAndInvalidRange() {
		assertThatThrownBy(() -> validator.validate(new AttendanceRunRequest(null, "2026-10-24", "2026-10-01", "Report")))
			.isInstanceOf(ReportValidationException.class)
			.satisfies(error -> assertThat(((ReportValidationException) error).getFieldErrors())
				.extracting(field -> field.field()).contains("courseId", "endDate"));
	}

	@Test
	void rejectsForeignCourseWithoutDistinguishingItFromUnknownCourse() {
		UUID foreign = UUID.randomUUID();
		when(courseLookupApi.getCourseSummaries(Set.of(foreign))).thenReturn(List.of());

		assertThatThrownBy(() -> validator.validate(new AttendanceRunRequest(foreign, "2026-10-01", "2026-10-02", "Report")))
			.isInstanceOf(ReportValidationException.class)
			.satisfies(error -> assertThat(((ReportValidationException) error).getFieldErrors()).singleElement()
				.extracting(field -> field.message()).isEqualTo("course not found"));
	}

}
