package com.lms.attendancemanagement.web;

import com.lms.attendancemanagement.service.AttendanceMarkCommand;
import com.lms.attendancemanagement.service.AttendanceMarkOutcome;
import com.lms.attendancemanagement.service.AttendanceMarkingService;
import com.lms.attendancemanagement.service.AttendanceRecordView;
import com.lms.attendancemanagement.service.AttendanceReportFilter;
import com.lms.attendancemanagement.service.AttendanceReportService;
import com.lms.attendancemanagement.service.AttendanceRosterEntryView;
import com.lms.attendancemanagement.service.AttendanceRosterView;
import com.lms.attendancemanagement.web.dto.AttendanceMarkResultResponse;
import com.lms.attendancemanagement.web.dto.AttendanceRecordResponse;
import com.lms.attendancemanagement.web.dto.AttendanceRosterEntryResponse;
import com.lms.attendancemanagement.web.dto.AttendanceRosterResponse;
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
 * The four endpoints named in plan §10. Stays thin - delegates entirely to
 * {@link AttendanceMarkingService}/{@link AttendanceReportService}, which
 * perform the real staff-matrix-or-Teacher-ownership authorization check per
 * method (via {@code AttendanceAccessGuard}, mirroring {@code
 * CourseController}'s established discipline). {@code
 * @PreAuthorize("isAuthenticated()")} here is therefore only a coarse gate,
 * except {@code /my}, which is owner-only by construction ({@code
 * hasRole('STUDENT')}, no id param).
 */
@RestController
@RequestMapping("/api/v1/attendance")
public class AttendanceController {

	private final AttendanceMarkingService attendanceMarkingService;

	private final AttendanceReportService attendanceReportService;

	public AttendanceController(AttendanceMarkingService attendanceMarkingService,
			AttendanceReportService attendanceReportService) {
		this.attendanceMarkingService = attendanceMarkingService;
		this.attendanceReportService = attendanceReportService;
	}

	@GetMapping("/sessions/{sessionId}/roster")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<AttendanceRosterResponse>> getSessionRoster(@PathVariable UUID sessionId) {
		AttendanceRosterView view = attendanceReportService.getSessionRoster(sessionId);
		return ResponseEntity.ok(ApiResponse.success(toResponse(view)));
	}

	@PostMapping("/sessions/{sessionId}/records")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<List<AttendanceMarkResultResponse>>> markAttendance(
			@PathVariable UUID sessionId, @Valid @RequestBody MarkAttendanceRequest request) {
		List<AttendanceMarkCommand> commands = request.marks()
			.stream()
			.map(entry -> new AttendanceMarkCommand(entry.studentId(), entry.status()))
			.toList();
		List<AttendanceMarkOutcome> outcomes = attendanceMarkingService.markAttendance(sessionId, commands);
		List<AttendanceMarkResultResponse> response = outcomes.stream()
			.map(AttendanceController::toResultResponse)
			.toList();
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	@GetMapping("/my")
	@PreAuthorize("hasRole('STUDENT')")
	public ResponseEntity<ApiResponse<PageResponse<AttendanceRecordResponse>>> myAttendance(
			@PageableDefault(size = 20, sort = "markedAt", direction = Sort.Direction.DESC) Pageable pageable,
			@RequestParam(required = false) UUID courseId, @RequestParam(required = false) Instant from,
			@RequestParam(required = false) Instant to) {
		PageResponse<AttendanceRecordView> page = attendanceReportService
			.getMyHistory(new AttendanceReportFilter(courseId, from, to), pageable);
		return ResponseEntity.ok(ApiResponse.success(toPageResponse(page)));
	}

	@GetMapping("/reports")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<PageResponse<AttendanceRecordResponse>>> attendanceReports(
			@PageableDefault(size = 20, sort = "markedAt", direction = Sort.Direction.DESC) Pageable pageable,
			@RequestParam(required = false) UUID courseId, @RequestParam(required = false) Instant from,
			@RequestParam(required = false) Instant to) {
		PageResponse<AttendanceRecordView> page = attendanceReportService
			.getReport(new AttendanceReportFilter(courseId, from, to), pageable);
		return ResponseEntity.ok(ApiResponse.success(toPageResponse(page)));
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
				view.status(), view.markedBy(), view.markedAt(), view.createdAt(), view.updatedAt());
	}

}
