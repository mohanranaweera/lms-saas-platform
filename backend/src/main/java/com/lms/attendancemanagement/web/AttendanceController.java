package com.lms.attendancemanagement.web;

import com.lms.attendancemanagement.service.AttendanceClassSessionRosterView;
import com.lms.attendancemanagement.service.AttendanceClassSessionService;
import com.lms.attendancemanagement.service.AttendanceMarkCommand;
import com.lms.attendancemanagement.service.AttendanceMarkOutcome;
import com.lms.attendancemanagement.service.AttendanceMarkingService;
import com.lms.attendancemanagement.service.AttendanceRecordView;
import com.lms.attendancemanagement.service.AttendanceReportFilter;
import com.lms.attendancemanagement.service.AttendanceReportService;
import com.lms.attendancemanagement.service.AttendanceRosterEntryView;
import com.lms.attendancemanagement.service.AttendanceRosterView;
import com.lms.attendancemanagement.service.AttendanceSummaryService;
import com.lms.attendancemanagement.service.AttendanceSummaryView;
import com.lms.attendancemanagement.web.dto.AttendanceMarkResultResponse;
import com.lms.attendancemanagement.web.dto.AttendanceRecordResponse;
import com.lms.attendancemanagement.web.dto.AttendanceRosterEntryResponse;
import com.lms.attendancemanagement.web.dto.AttendanceRosterResponse;
import com.lms.attendancemanagement.web.dto.AttendanceSummaryRowResponse;
import com.lms.attendancemanagement.web.dto.ClassSessionRosterResponse;
import com.lms.attendancemanagement.web.dto.MarkAttendanceRequest;
import com.lms.common.api.ApiResponse;
import com.lms.common.api.PageResponse;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Attendance endpoints. Stays thin - delegates entirely to the services,
 * which perform the real staff-matrix-or-Teacher-ownership authorization
 * check per method (via {@code AttendanceAccessGuard}, mirroring {@code
 * CourseController}'s established discipline). {@code
 * @PreAuthorize("isAuthenticated()")} here is therefore only a coarse gate,
 * except the {@code /my} reads, which are owner-only by construction ({@code
 * hasRole('STUDENT')}, no id param).
 *
 * <p>Wave 8: {@code /class-sessions/{classSessionId}/...} is the primary
 * marking workflow ({@code ClassSession -> AttendanceSheet ->
 * AttendanceRecord}). The lesson-scoped {@code /sessions/{sessionId}/...}
 * endpoints are <b>deprecated</b> but unchanged in contract (approved API
 * contract; wave-08-plan.md §10.2).
 */
@RestController
@RequestMapping("/api/v1/attendance")
public class AttendanceController {

	private final AttendanceMarkingService attendanceMarkingService;

	private final AttendanceReportService attendanceReportService;

	private final AttendanceClassSessionService attendanceClassSessionService;

	private final AttendanceSummaryService attendanceSummaryService;

	public AttendanceController(AttendanceMarkingService attendanceMarkingService,
			AttendanceReportService attendanceReportService,
			AttendanceClassSessionService attendanceClassSessionService,
			AttendanceSummaryService attendanceSummaryService) {
		this.attendanceMarkingService = attendanceMarkingService;
		this.attendanceReportService = attendanceReportService;
		this.attendanceClassSessionService = attendanceClassSessionService;
		this.attendanceSummaryService = attendanceSummaryService;
	}

	// ------------------------------------------------------------------
	// Wave 8 - class-session attendance (primary workflow).
	// ------------------------------------------------------------------

	@GetMapping("/class-sessions/{classSessionId}/roster")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<ClassSessionRosterResponse>> getClassSessionRoster(
			@PathVariable UUID classSessionId) {
		AttendanceClassSessionRosterView view = attendanceClassSessionService.getRoster(classSessionId);
		return ResponseEntity.ok(ApiResponse.success(toResponse(view)));
	}

	@PostMapping("/class-sessions/{classSessionId}/records")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<List<AttendanceMarkResultResponse>>> markClassSessionAttendance(
			@PathVariable UUID classSessionId, @Valid @RequestBody MarkAttendanceRequest request) {
		List<AttendanceMarkOutcome> outcomes = attendanceClassSessionService.markAttendance(classSessionId,
				toCommands(request));
		return ResponseEntity.ok(ApiResponse.success(outcomes.stream().map(AttendanceController::toResultResponse).toList()));
	}

	/** Per-student attendance percentages for one course (Teacher-owner or staff {@code ATTENDANCE}/{@code VIEW}). */
	@GetMapping("/summary")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<List<AttendanceSummaryRowResponse>>> courseSummary(
			@RequestParam UUID courseId, @RequestParam(required = false) Instant from,
			@RequestParam(required = false) Instant to) {
		List<AttendanceSummaryView> rows = attendanceSummaryService.getCourseSummary(courseId, from, to);
		return ResponseEntity.ok(ApiResponse.success(rows.stream().map(AttendanceController::toResponse).toList()));
	}

	/** The calling Student's own per-course attendance percentages. */
	@GetMapping("/my/summary")
	@PreAuthorize("hasRole('STUDENT')")
	public ResponseEntity<ApiResponse<List<AttendanceSummaryRowResponse>>> mySummary(
			@RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
		List<AttendanceSummaryView> rows = attendanceSummaryService.getMySummary(from, to);
		return ResponseEntity.ok(ApiResponse.success(rows.stream().map(AttendanceController::toResponse).toList()));
	}

	// ------------------------------------------------------------------
	// Legacy lesson-scoped marking (deprecated since Wave 8).
	// ------------------------------------------------------------------

	/** @deprecated since Wave 8 - use {@code /class-sessions/{classSessionId}/roster}. */
	@Deprecated
	@GetMapping("/sessions/{sessionId}/roster")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<AttendanceRosterResponse>> getSessionRoster(@PathVariable UUID sessionId) {
		AttendanceRosterView view = attendanceReportService.getSessionRoster(sessionId);
		return ResponseEntity.ok(ApiResponse.success(toResponse(view)));
	}

	/** @deprecated since Wave 8 - use {@code /class-sessions/{classSessionId}/records}. */
	@Deprecated
	@PostMapping("/sessions/{sessionId}/records")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<List<AttendanceMarkResultResponse>>> markAttendance(
			@PathVariable UUID sessionId, @Valid @RequestBody MarkAttendanceRequest request) {
		List<AttendanceMarkOutcome> outcomes = attendanceMarkingService.markAttendance(sessionId, toCommands(request));
		return ResponseEntity.ok(ApiResponse.success(outcomes.stream().map(AttendanceController::toResultResponse).toList()));
	}

	// ------------------------------------------------------------------
	// Reports.
	// ------------------------------------------------------------------

	@GetMapping("/my")
	@PreAuthorize("hasRole('STUDENT')")
	public ResponseEntity<ApiResponse<PageResponse<AttendanceRecordResponse>>> myAttendance(
			@PageableDefault(size = 20, sort = "markedAt", direction = Sort.Direction.DESC) Pageable pageable,
			@RequestParam(required = false) UUID courseId, @RequestParam(required = false) Instant from,
			@RequestParam(required = false) Instant to, @RequestParam(required = false) UUID classSessionId) {
		PageResponse<AttendanceRecordView> page = attendanceReportService
			.getMyHistory(new AttendanceReportFilter(courseId, from, to, null, classSessionId), pageable);
		return ResponseEntity.ok(ApiResponse.success(toPageResponse(page)));
	}

	@GetMapping("/reports")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<PageResponse<AttendanceRecordResponse>>> attendanceReports(
			@PageableDefault(size = 20, sort = "markedAt", direction = Sort.Direction.DESC) Pageable pageable,
			@RequestParam(required = false) UUID courseId, @RequestParam(required = false) Instant from,
			@RequestParam(required = false) Instant to, @RequestParam(required = false) UUID classSessionId) {
		PageResponse<AttendanceRecordView> page = attendanceReportService
			.getReport(new AttendanceReportFilter(courseId, from, to, null, classSessionId), pageable);
		return ResponseEntity.ok(ApiResponse.success(toPageResponse(page)));
	}

	/** Wave 3 staff-facing, studentId-scoped attendance report - see {@link AttendanceReportService#getReportForStudent}. */
	@GetMapping("/students/{id}/report")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<PageResponse<AttendanceRecordResponse>>> attendanceReportForStudent(
			@PathVariable UUID id,
			@PageableDefault(size = 20, sort = "markedAt", direction = Sort.Direction.DESC) Pageable pageable,
			@RequestParam(required = false) UUID courseId, @RequestParam(required = false) Instant from,
			@RequestParam(required = false) Instant to, @RequestParam(required = false) UUID classSessionId) {
		PageResponse<AttendanceRecordView> page = attendanceReportService.getReportForStudent(id,
				new AttendanceReportFilter(courseId, from, to, null, classSessionId), pageable);
		return ResponseEntity.ok(ApiResponse.success(toPageResponse(page)));
	}

	// ------------------------------------------------------------------
	// Mapping.
	// ------------------------------------------------------------------

	private static List<AttendanceMarkCommand> toCommands(MarkAttendanceRequest request) {
		return request.marks()
			.stream()
			.map(entry -> new AttendanceMarkCommand(entry.studentId(), entry.status()))
			.toList();
	}

	private static ClassSessionRosterResponse toResponse(AttendanceClassSessionRosterView view) {
		List<ClassSessionRosterResponse.Entry> roster = view.roster()
			.stream()
			.map(entry -> new ClassSessionRosterResponse.Entry(entry.studentId(), entry.studentName(), entry.status(),
					entry.currentlyEnrolled()))
			.toList();
		return new ClassSessionRosterResponse(view.sheetId(), view.classSessionId(), view.courseId(), view.title(),
				view.scheduledStart(), view.scheduledEnd(), view.sessionStatus(), view.markingOpen(),
				view.markingClosedReason(), roster);
	}

	private static AttendanceSummaryRowResponse toResponse(AttendanceSummaryView view) {
		return new AttendanceSummaryRowResponse(view.studentId(), view.studentName(), view.courseId(),
				view.courseName(), view.present(), view.late(), view.absent(), view.total(), view.attendanceRate());
	}

	private static AttendanceRosterResponse toResponse(AttendanceRosterView view) {
		List<AttendanceRosterEntryResponse> roster = view.roster()
			.stream()
			.map(AttendanceController::toRosterEntryResponse)
			.toList();
		return new AttendanceRosterResponse(view.courseId(), view.sessionId(), roster);
	}

	private static AttendanceRosterEntryResponse toRosterEntryResponse(AttendanceRosterEntryView entry) {
		return new AttendanceRosterEntryResponse(entry.studentId(), entry.status());
	}

	private static AttendanceMarkResultResponse toResultResponse(AttendanceMarkOutcome outcome) {
		AttendanceRecordResponse record = outcome.record() != null ? toResponse(outcome.record()) : null;
		return new AttendanceMarkResultResponse(outcome.studentId(), outcome.success(), record, outcome.reason());
	}

	private static PageResponse<AttendanceRecordResponse> toPageResponse(PageResponse<AttendanceRecordView> page) {
		List<AttendanceRecordResponse> content = page.content().stream().map(AttendanceController::toResponse).toList();
		return new PageResponse<>(content, page.page(), page.size(), page.totalElements(), page.totalPages());
	}

	private static AttendanceRecordResponse toResponse(AttendanceRecordView view) {
		return new AttendanceRecordResponse(view.id(), view.courseId(), view.sessionId(), view.studentId(),
				view.status(), view.markedBy(), view.markedAt(), view.createdAt(), view.updatedAt(), view.sheetId(),
				view.source(), view.classSessionId(), view.classSessionTitle());
	}

}
