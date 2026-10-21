package com.lms.enrollmentmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.common.api.PageResponse;
import com.lms.enrollmentmanagement.service.FreeCardService;
import com.lms.enrollmentmanagement.service.FreeCardViews.FreeCardView;
import com.lms.enrollmentmanagement.service.FreeCardViews.IssueCommand;
import com.lms.enrollmentmanagement.web.dto.FreeCardRequests.IssueFreeCardRequest;
import com.lms.enrollmentmanagement.web.dto.FreeCardRequests.RevokeFreeCardRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
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
 * ADR-030 Free Cards (plan §4). {@code @PreAuthorize} gates on the {@code
 * FREE_CARDS} area (VIEW / CREATE_EDIT / DELETE); {@link FreeCardService}
 * re-checks the same permission and enforces every business rule.
 */
@RestController
@RequestMapping("/api/v1/free-cards")
public class FreeCardController {

	static final String VIEW = "@permissionCheckService.hasPermission('FREE_CARDS', 'VIEW')";

	static final String ISSUE = "@permissionCheckService.hasPermission('FREE_CARDS', 'CREATE_EDIT')";

	static final String REVOKE = "@permissionCheckService.hasPermission('FREE_CARDS', 'DELETE')";

	private final FreeCardService freeCardService;

	public FreeCardController(FreeCardService freeCardService) {
		this.freeCardService = freeCardService;
	}

	@GetMapping
	@PreAuthorize(VIEW)
	public ResponseEntity<ApiResponse<PageResponse<FreeCardView>>> list(
			@RequestParam(required = false) UUID studentId, @RequestParam(required = false) UUID courseId,
			@RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return ResponseEntity.ok(ApiResponse.success(freeCardService.list(studentId, courseId, status, page, size)));
	}

	/** 201 for a new card; 200 for an idempotency-key replay (same card returned). */
	@PostMapping
	@PreAuthorize(ISSUE)
	public ResponseEntity<ApiResponse<FreeCardView>> issue(@RequestBody IssueFreeCardRequest request) {
		FreeCardView card = freeCardService.issue(new IssueCommand(request.studentId(), request.courseId(),
				request.cardType(), request.validFrom(), request.validTo(), request.reason(),
				request.idempotencyKey()));
		return ResponseEntity.status(card.replay() ? HttpStatus.OK : HttpStatus.CREATED)
			.body(ApiResponse.success(card));
	}

	@PostMapping("/{id}/revoke")
	@PreAuthorize(REVOKE)
	public ResponseEntity<ApiResponse<FreeCardView>> revoke(@PathVariable UUID id,
			@RequestBody(required = false) RevokeFreeCardRequest request) {
		return ResponseEntity
			.ok(ApiResponse.success(freeCardService.revoke(id, request == null ? null : request.reason())));
	}

}
