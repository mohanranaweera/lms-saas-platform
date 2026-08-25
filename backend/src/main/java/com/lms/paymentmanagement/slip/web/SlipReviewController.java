package com.lms.paymentmanagement.slip.web;

import com.lms.common.api.ApiResponse;
import com.lms.common.api.PageResponse;
import com.lms.paymentmanagement.slip.domain.PaymentSlipStatus;
import com.lms.paymentmanagement.slip.service.PaymentSlipView;
import com.lms.paymentmanagement.slip.service.SlipReviewService;
import com.lms.paymentmanagement.slip.web.dto.PaymentSlipResponse;
import com.lms.paymentmanagement.slip.web.dto.SlipApproveRequest;
import com.lms.paymentmanagement.slip.web.dto.SlipRejectRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Page;
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
 * MVP-011/SLIP-3's staff review-queue and approve/reject/override endpoints.
 * {@code @PreAuthorize("isAuthenticated()")} is a coarse gate only - {@link
 * SlipReviewService} performs the real, {@code PAYMENTS_SLIPS}/{@code VIEW}
 * or {@code PAYMENTS_SLIPS}/{@code APPROVE}-gated check (mirroring {@code
 * RefundController}'s exact discipline); a student, Student Support, or
 * Read-only Auditor caller is rejected 403 server-side regardless of UI
 * state.
 */
@RestController
@RequestMapping("/api/v1/payment-slips")
public class SlipReviewController {

	private final SlipReviewService slipReviewService;

	public SlipReviewController(SlipReviewService slipReviewService) {
		this.slipReviewService = slipReviewService;
	}

	@GetMapping("/review-queue")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<PageResponse<PaymentSlipResponse>>> getReviewQueue(
			@RequestParam(required = false) PaymentSlipStatus status,
			@PageableDefault(size = 20, sort = "submittedAt", direction = Sort.Direction.ASC) Pageable pageable) {
		Page<PaymentSlipView> page = slipReviewService.getReviewQueue(status, pageable);
		PageResponse<PaymentSlipResponse> response = PageResponse.from(page.map(SlipController::toResponse));
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	@PostMapping("/{slipId}/approve")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<PaymentSlipResponse>> approve(@PathVariable UUID slipId,
			@Valid @RequestBody(required = false) SlipApproveRequest request) {
		String overrideReason = (request != null) ? request.overrideReason() : null;
		PaymentSlipView view = slipReviewService.approve(slipId, overrideReason);
		return ResponseEntity.ok(ApiResponse.success(SlipController.toResponse(view)));
	}

	@PostMapping("/{slipId}/reject")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<PaymentSlipResponse>> reject(@PathVariable UUID slipId,
			@Valid @RequestBody SlipRejectRequest request) {
		PaymentSlipView view = slipReviewService.reject(slipId, request.reason());
		return ResponseEntity.ok(ApiResponse.success(SlipController.toResponse(view)));
	}

}
