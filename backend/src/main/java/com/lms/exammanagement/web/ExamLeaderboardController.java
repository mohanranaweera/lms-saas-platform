package com.lms.exammanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.exammanagement.service.exam.ExamLeaderboardService;
import com.lms.exammanagement.service.exam.ExamResultsViews.LeaderboardDetailView;
import com.lms.exammanagement.service.exam.ExamResultsViews.LeaderboardRanksView;
import com.lms.exammanagement.service.exam.ExamResultsViews.LeaderboardSummaryView;
import com.lms.exammanagement.service.exam.ExamResultsViews.StudentLeaderboardView;
import com.lms.exammanagement.web.dto.ExamLeaderboardRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Klass exam leaderboards (Slice E, D7): staff / teacher CRUD and ranks, and the student views.
 * Thin: scope, tenant and visibility decisions are made by {@link ExamLeaderboardService}.
 */
@RestController
public class ExamLeaderboardController {

	private final ExamLeaderboardService leaderboardService;

	public ExamLeaderboardController(ExamLeaderboardService leaderboardService) {
		this.leaderboardService = leaderboardService;
	}

	@GetMapping("/api/v1/exam-leaderboards")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<List<LeaderboardSummaryView>>> list(
			@RequestParam(required = false) String search) {
		return ResponseEntity.ok(ApiResponse.success(leaderboardService.list(search)));
	}

	@PostMapping("/api/v1/exam-leaderboards")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<LeaderboardDetailView>> create(@RequestBody ExamLeaderboardRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse
				.success(leaderboardService.create(request.name(), request.examIds(), request.published())));
	}

	@GetMapping("/api/v1/exam-leaderboards/{leaderboardId}")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<LeaderboardDetailView>> get(@PathVariable UUID leaderboardId) {
		return ResponseEntity.ok(ApiResponse.success(leaderboardService.get(leaderboardId)));
	}

	@PutMapping("/api/v1/exam-leaderboards/{leaderboardId}")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<LeaderboardDetailView>> update(@PathVariable UUID leaderboardId,
			@RequestBody ExamLeaderboardRequest request) {
		return ResponseEntity.ok(ApiResponse.success(
				leaderboardService.update(leaderboardId, request.name(), request.examIds(), request.published())));
	}

	@DeleteMapping("/api/v1/exam-leaderboards/{leaderboardId}")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<Void> delete(@PathVariable UUID leaderboardId) {
		leaderboardService.delete(leaderboardId);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/api/v1/exam-leaderboards/{leaderboardId}/ranks")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<LeaderboardRanksView>> ranks(@PathVariable UUID leaderboardId,
			@RequestParam(required = false) String search, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(ApiResponse.success(leaderboardService.ranks(leaderboardId, search, page, size)));
	}

	@GetMapping("/api/v1/student/leaderboards")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<List<StudentLeaderboardView>>> studentList() {
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(ApiResponse.success(leaderboardService.studentList()));
	}

	@GetMapping("/api/v1/student/leaderboards/{leaderboardId}/ranks")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<LeaderboardRanksView>> studentRanks(@PathVariable UUID leaderboardId,
			@RequestParam(required = false) String search, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(ApiResponse.success(leaderboardService.studentRanks(leaderboardId, search, page, size)));
	}

}
