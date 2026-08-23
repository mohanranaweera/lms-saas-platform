package com.lms.paymentmanagement.payment.web;

import com.lms.common.api.ApiResponse;
import com.lms.paymentmanagement.payment.service.PaymentRefundView;
import com.lms.paymentmanagement.payment.service.RefundService;
import com.lms.paymentmanagement.payment.web.dto.RefundCreateRequest;
import com.lms.paymentmanagement.payment.web.dto.RefundResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PAY-4's refund endpoints. {@code @PreAuthorize("isAuthenticated()")} is a
 * coarse gate only - {@link RefundService#processRefund} performs the real,
 * {@code PAYMENTS_SLIPS}/{@code APPROVE}-gated check (Finance Staff/
 * Institute Owner only; a student, Student Support, or Read-only Auditor
 * caller is rejected 403 server-side regardless of UI state).
 */
@RestController
@RequestMapping("/api/v1/payments")
public class RefundController {

	private final RefundService refundService;

	public RefundController(RefundService refundService) {
		this.refundService = refundService;
	}

	@PostMapping("/{id}/refunds")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<RefundResponse>> createRefund(@PathVariable UUID id,
			@Valid @RequestBody RefundCreateRequest request) {
		PaymentRefundView view = refundService.processRefund(id, request.amount(), request.reason(),
				request.idempotencyKey());
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(toResponse(view)));
	}

	@GetMapping("/{id}/refunds")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<List<RefundResponse>>> listRefunds(@PathVariable UUID id) {
		List<RefundResponse> responses = refundService.listRefunds(id).stream().map(RefundController::toResponse).toList();
		return ResponseEntity.ok(ApiResponse.success(responses));
	}

	private static RefundResponse toResponse(PaymentRefundView view) {
		return new RefundResponse(view.id(), view.originalPaymentId(), view.amount(), view.reason(),
				view.createdAt());
	}

}
