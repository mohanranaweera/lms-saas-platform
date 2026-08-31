package com.lms.enrollmentmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.common.api.PageResponse;
import com.lms.enrollmentmanagement.domain.ReactivationRequestStatus;
import com.lms.enrollmentmanagement.service.ReactivationRequestService;
import com.lms.enrollmentmanagement.service.ReactivationRequestView;
import com.lms.enrollmentmanagement.web.dto.ReactivationApproveRequest;
import com.lms.enrollmentmanagement.web.dto.ReactivationRejectRequest;
import com.lms.enrollmentmanagement.web.dto.ReactivationRequestResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * MVP-012/ENR-3's reactivation-request endpoints (plan §10). {@code
 * @PreAuthorize("isAuthenticated()")}/{@code hasRole('STUDENT')} are coarse
 * gates only - {@link ReactivationRequestService} performs the real,
 * ownership- or {@code ACCESS_EXPIRY}/{@code VIEW}/{@code APPROVE}-gated
 * check, mirroring {@code SlipReviewController}'s exact discipline.
 */
@RestController
public class ReactivationRequestController {

	private final ReactivationRequestService reactivationRequestService;

	public ReactivationRequestController(ReactivationRequestService reactivationRequestService) {
		this.reactivationRequestService = reactivationRequestService;
	}

	@PostMapping("/api/v1/enrollments/{enrollmentId}/reactivation-requests")
	@PreAuthorize("hasRole('STUDENT')")
	public ResponseEntity<ApiResponse<ReactivationRequestResponse>> submit(@PathVariable UUID enrollmentId) {
		ReactivationRequestView view = reactivationRequestService.submit(enrollmentId);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(toResponse(view)));
	}

	@GetMapping("/api/v1/reactivation-requests/my")
	@PreAuthorize("hasRole('STUDENT')")
	public ResponseEntity<ApiResponse<PageResponse<ReactivationRequestResponse>>> listMine(
			@PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
		Page<ReactivationRequestView> page = reactivationRequestService.listMine(pageable);
		return ResponseEntity.ok(ApiResponse.success(PageResponse.from(page.map(ReactivationRequestController::toResponse))));
	}

	@GetMapping("/api/v1/reactivation-requests/{id}")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<ReactivationRequestResponse>> getDetail(@PathVariable UUID id) {
		ReactivationRequestView view = reactivationRequestService.getDetail(id);
		return ResponseEntity.ok(ApiResponse.success(toResponse(view)));
	}

	@GetMapping("/api/v1/reactivation-requests")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<PageResponse<ReactivationRequestResponse>>> getQueue(
			@RequestParam(required = false) ReactivationRequestStatus status,
			@PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.ASC) Pageable pageable) {
		Page<ReactivationRequestView> page = reactivationRequestService.getQueue(status, pageable);
		return ResponseEntity.ok(ApiResponse.success(PageResponse.from(page.map(ReactivationRequestController::toResponse))));
	}

	@PostMapping("/api/v1/reactivation-requests/{id}/approve")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<ReactivationRequestResponse>> approve(@PathVariable UUID id,
			@Valid @RequestBody(required = false) ReactivationApproveRequest request) {
		String note = (request != null) ? request.note() : null;
		ReactivationRequestView view = reactivationRequestService.approve(id, note);
		return ResponseEntity.ok(ApiResponse.success(toResponse(view)));
	}

	@PostMapping("/api/v1/reactivation-requests/{id}/reject")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<ReactivationRequestResponse>> reject(@PathVariable UUID id,
			@Valid @RequestBody ReactivationRejectRequest request) {
		ReactivationRequestView view = reactivationRequestService.reject(id, request.reason());
		return ResponseEntity.ok(ApiResponse.success(toResponse(view)));
	}

	private static ReactivationRequestResponse toResponse(ReactivationRequestView view) {
		return new ReactivationRequestResponse(view.id(), view.enrollmentId(), view.requestedBy(), view.status(),
				view.reviewedBy(), view.reviewedAt(), view.newOrderId(), view.createdAt(), view.updatedAt());
	}

}
