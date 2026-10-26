package com.lms.reportinganalytics.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.reportinganalytics.web.dto.CourseSummaryRunRequest;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class CourseSummaryRequestValidatorTest {

	@Test
	void rejectsCrossMonthDateRangeForSelectedMonth() {
		CourseLookupApi courses = Mockito.mock(CourseLookupApi.class);
		CourseSummaryRequestValidator validator = new CourseSummaryRequestValidator(courses);
		assertThatThrownBy(() -> validator.validate(new CourseSummaryRunRequest(UUID.randomUUID(), UUID.randomUUID(),
				"2026-10-31", "2026-11-01", "2026-10", "")))
			.isInstanceOf(ReportValidationException.class);
	}

	@Test
	void rejectsCourseAssignedToAnotherTeacherWithoutResolvingExistence() {
		UUID selected = UUID.randomUUID();
		CourseLookupApi courses = Mockito.mock(CourseLookupApi.class);
		UUID course = UUID.randomUUID();
		Mockito.when(courses.getTeacherId(course)).thenReturn(Optional.of(UUID.randomUUID()));
		CourseSummaryRequestValidator validator = new CourseSummaryRequestValidator(courses);
		assertThatThrownBy(() -> validator.validate(new CourseSummaryRunRequest(selected, course, "2026-10-01",
				"2026-10-31", "2026-10", "Teacher Report"))).isInstanceOf(ReportValidationException.class);
	}

}
