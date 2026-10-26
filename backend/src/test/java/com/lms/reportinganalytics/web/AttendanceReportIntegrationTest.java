package com.lms.reportinganalytics.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.lms.attendancemanagement.AttendanceManagementTestSupport;
import com.lms.attendancemanagement.domain.AttendanceStatus;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** RPT-03 HTTP-level tenant, authorization, snapshot and mutation checks. */
class AttendanceReportIntegrationTest extends AttendanceManagementTestSupport {

	private static final String BASE = "/api/v1/reports/attendance";

	@Test
	void tenantAdminGeneratesRunAndDownloadsSameReportAsPdfWithoutAttendanceMutation() throws Exception {
		AttendanceFixture fixture = seedAttendanceFixture("rpt3-admin");
		markOneStudent(fixture.host(), fixture.teacherToken(), fixture.lessonId(), fixture.student().getId(),
				AttendanceStatus.PRESENT);
		long before = count(fixture.tenant().getId());
		MvcResult run = call(fixture.host(), fixture.adminToken(), post(BASE + "/runs"),
				body(fixture.course().id(), "2026-10-01", "2026-10-31", "Custom Attendance"));
		assertThat(run.getResponse().getStatus()).as(run.getResponse().getContentAsString()).isEqualTo(201);
		String runId = objectMapper.readTree(run.getResponse().getContentAsString()).path("data").path("runId").asString();
		MvcResult pdf = perform(authenticated(get(BASE + "/runs/{runId}/pdf", runId), fixture.host(), fixture.adminToken()));
		assertThat(pdf.getResponse().getStatus()).isEqualTo(200);
		assertThat(pdf.getResponse().getContentType()).isEqualTo(MediaType.APPLICATION_PDF_VALUE);
		assertThat(pdf.getResponse().getHeader("Content-Disposition")).contains("custom-attendance.pdf");
		assertThat(count(fixture.tenant().getId())).isEqualTo(before);
	}

	@Test
	void foreignCourseAndStudentCannotReadOrGenerateAttendanceReport() {
		AttendanceFixture own = seedAttendanceFixture("rpt3-own");
		AttendanceFixture foreign = seedAttendanceFixture("rpt3-foreign");
		MvcResult foreignRun = call(own.host(), own.adminToken(), post(BASE + "/runs"),
				body(foreign.course().id(), "2026-10-01", "2026-10-31", "Attendance Report"));
		assertThat(foreignRun.getResponse().getStatus()).isIn(403, 404, 422);
		MvcResult options = perform(authenticated(get(BASE + "/options"), own.host(), own.studentToken()));
		assertThat(options.getResponse().getStatus()).isEqualTo(403);
	}

	@Test
	void teacherCannotGenerateAnotherTeachersCourseReport() {
		AttendanceFixture fixture = seedAttendanceFixture("rpt3-teacher");
		TenantUser other = seedTenantUser(fixture.tenant().getId(), "other-teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		CourseResponse otherCourse = createCourseOrFail(fixture.host(), fixture.adminToken(),
				newCourseRequest(uniqueSlug("rpt3-other-course"), other.getId(), CourseStatus.PUBLIC));
		MvcResult result = call(fixture.host(), fixture.teacherToken(), post(BASE + "/runs"),
				body(otherCourse.id(), "2026-10-01", "2026-10-31", "Attendance Report"));
		assertThat(result.getResponse().getStatus()).isEqualTo(403);
	}

	private MvcResult call(String host, String token, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
			Map<String, Object> body) {
		return perform(authenticated(request.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body)), host, token));
	}

	private long count(UUID tenantId) {
		Long result = jdbcTemplate.queryForObject("SELECT count(*) FROM attendance_record WHERE tenant_id = ?", Long.class, tenantId);
		return result == null ? 0 : result;
	}

	private static Map<String, Object> body(UUID courseId, String start, String end, String name) {
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("courseId", courseId);
		values.put("startDate", start);
		values.put("endDate", end);
		values.put("reportName", name);
		return values;
	}

}
