package com.lms.attendancemanagement;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.lms.attendancemanagement.domain.AttendanceStatus;
import com.lms.attendancemanagement.web.dto.AttendanceMarkEntryRequest;
import com.lms.attendancemanagement.web.dto.AttendanceMarkResultResponse;
import com.lms.attendancemanagement.web.dto.AttendanceRecordResponse;
import com.lms.attendancemanagement.web.dto.AttendanceSummaryRowResponse;
import com.lms.attendancemanagement.web.dto.ClassSessionRosterResponse;
import com.lms.attendancemanagement.web.dto.MarkAttendanceRequest;
import com.lms.common.api.PageResponse;
import com.lms.identityaccessservice.HttpResult;
import com.lms.liveclassmanagement.web.dto.ClassSessionCreateRequest;
import com.lms.liveclassmanagement.web.dto.ClassSessionResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Wave 8 helpers layered on {@link AttendanceManagementTestSupport}: real
 * {@code class_session} scheduling through {@code POST /api/v1/class-sessions}
 * (fake meeting provider per the test profile), lifecycle transitions through
 * the real endpoints, and the new class-session attendance/summary endpoints.
 * Not itself a test class.
 */
public abstract class AttendanceClassSessionTestSupport extends AttendanceManagementTestSupport {

	// ------------------------------------------------------------------
	// class_session seeding.
	// ------------------------------------------------------------------

	/** A SCHEDULED session whose start is already in the past - markable (lifecycle gate open). */
	protected ClassSessionResponse scheduleStartedSession(AttendanceFixture fixture, String title) {
		Instant start = Instant.now().minus(30, ChronoUnit.MINUTES);
		return scheduleSessionOrFail(fixture, title, start, start.plus(1, ChronoUnit.HOURS));
	}

	/** A SCHEDULED session starting tomorrow - lifecycle gate closed. */
	protected ClassSessionResponse scheduleFutureSession(AttendanceFixture fixture, String title) {
		Instant start = Instant.now().plus(1, ChronoUnit.DAYS);
		return scheduleSessionOrFail(fixture, title, start, start.plus(1, ChronoUnit.HOURS));
	}

	protected ClassSessionResponse scheduleSessionOrFail(AttendanceFixture fixture, String title, Instant start,
			Instant end) {
		ClassSessionCreateRequest request = new ClassSessionCreateRequest(fixture.course().id(), null, title,
				"Description for " + title, start, end);
		MockHttpServletRequestBuilder builder = post("/api/v1/class-sessions").contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(request));
		HttpResult<ClassSessionResponse> result = parseSingle(
				perform(authenticated(builder, fixture.host(), fixture.adminToken())), ClassSessionResponse.class);
		if (result.getStatusCode() != HttpStatus.CREATED) {
			throw new IllegalStateException("Session scheduling failed: " + result.getStatusCode());
		}
		return result.getBody().data();
	}

	protected void transitionSessionOrFail(AttendanceFixture fixture, UUID sessionId, String action) {
		MockHttpServletRequestBuilder builder = post("/api/v1/class-sessions/{id}/" + action, sessionId);
		HttpResult<ClassSessionResponse> result = parseSingle(
				perform(authenticated(builder, fixture.host(), fixture.adminToken())), ClassSessionResponse.class);
		if (result.getStatusCode() != HttpStatus.OK) {
			throw new IllegalStateException("Session " + action + " failed: " + result.getStatusCode());
		}
	}

	// ------------------------------------------------------------------
	// Wave 8 attendance endpoints.
	// ------------------------------------------------------------------

	protected HttpResult<ClassSessionRosterResponse> getClassSessionRoster(String host, String token,
			UUID classSessionId) {
		MockHttpServletRequestBuilder builder = get("/api/v1/attendance/class-sessions/{id}/roster", classSessionId);
		return parseSingle(perform(authenticated(builder, host, token)), ClassSessionRosterResponse.class);
	}

	protected HttpResult<List<AttendanceMarkResultResponse>> markClassSession(String host, String token,
			UUID classSessionId, List<AttendanceMarkEntryRequest> marks) {
		MockHttpServletRequestBuilder builder = post("/api/v1/attendance/class-sessions/{id}/records", classSessionId)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(new MarkAttendanceRequest(marks)));
		return parseList(perform(authenticated(builder, host, token)), AttendanceMarkResultResponse.class);
	}

	protected HttpResult<List<AttendanceMarkResultResponse>> markClassSessionOne(String host, String token,
			UUID classSessionId, UUID studentId, AttendanceStatus status) {
		return markClassSession(host, token, classSessionId, List.of(new AttendanceMarkEntryRequest(studentId, status)));
	}

	protected HttpResult<List<AttendanceSummaryRowResponse>> getCourseSummary(String host, String token,
			UUID courseId) {
		MockHttpServletRequestBuilder builder = get("/api/v1/attendance/summary").param("courseId",
				courseId.toString());
		return parseList(perform(authenticated(builder, host, token)), AttendanceSummaryRowResponse.class);
	}

	protected HttpResult<List<AttendanceSummaryRowResponse>> getMySummary(String host, String token) {
		MockHttpServletRequestBuilder builder = get("/api/v1/attendance/my/summary");
		return parseList(perform(authenticated(builder, host, token)), AttendanceSummaryRowResponse.class);
	}

	protected HttpResult<PageResponse<AttendanceRecordResponse>> getMyAttendanceForSession(String host, String token,
			UUID classSessionId) {
		return getMyAttendance(host, token, "classSessionId=" + classSessionId);
	}

	// ------------------------------------------------------------------
	// Direct-DB assertions.
	// ------------------------------------------------------------------

	protected long countSheetsForSession(UUID classSessionId) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM attendance_sheet WHERE class_session_id = ?",
				Long.class, classSessionId);
	}

	protected long countRecordsForSession(UUID classSessionId) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM attendance_record ar "
				+ "JOIN attendance_sheet s ON s.id = ar.sheet_id WHERE s.class_session_id = ?", Long.class,
				classSessionId);
	}

}
