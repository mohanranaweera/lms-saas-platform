package com.lms.paymentmanagement.slip.web;

import com.lms.common.api.ApiResponse;
import com.lms.integrationmanagement.api.SignedDownloadUrl;
import com.lms.paymentmanagement.slip.service.PaymentSlipView;
import com.lms.paymentmanagement.slip.service.SlipReviewService;
import com.lms.paymentmanagement.slip.web.dto.PaymentSlipFlagResponse;
import com.lms.paymentmanagement.slip.web.dto.PaymentSlipResponse;
import com.lms.paymentmanagement.slip.web.dto.SlipDownloadUrlResponse;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MVP-011's staff/owner-facing slip READ endpoints: detail read and signed
 * download-url read (both owner-or-staff-{@code VIEW}, plan §10/§15). Stays
 * thin, delegates entirely to {@link SlipReviewService}, which performs the
 * real authorization checks - mirrors {@code OrderController}/{@code
 * MaterialController}'s "coarse {@code @PreAuthorize} gate, real check in
 * the service layer" discipline. Scoped to one class-level {@code
 * @RequestMapping("/api/v1/payment-slips")}, matching this codebase's
 * one-prefix-per-controller convention. The upload endpoint ({@code
 * POST /api/v1/orders/{orderId}/slips}) lives in {@code OrderController}
 * instead - it is nested under the {@code /orders} resource, mirroring
 * {@code OrderController#initiatePayment}'s precedent for exactly this
 * shape.
 */
@RestController
@RequestMapping("/api/v1/payment-slips")
@Validated
public class SlipController {

	private final SlipReviewService slipReviewService;

	public SlipController(SlipReviewService slipReviewService) {
		this.slipReviewService = slipReviewService;
	}

	@GetMapping("/{slipId}")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<PaymentSlipResponse>> getSlip(@PathVariable UUID slipId) {
		PaymentSlipView view = slipReviewService.getSlipDetail(slipId);
		return ResponseEntity.ok(ApiResponse.success(toResponse(view)));
	}

	@GetMapping("/{slipId}/download-url")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<SlipDownloadUrlResponse>> getDownloadUrl(@PathVariable UUID slipId) {
		SignedDownloadUrl signed = slipReviewService.getDownloadUrl(slipId);
		return ResponseEntity.ok(ApiResponse.success(new SlipDownloadUrlResponse(signed.url(), signed.expiresAt())));
	}

	static PaymentSlipResponse toResponse(PaymentSlipView view) {
		return new PaymentSlipResponse(view.id(), view.orderId(), view.studentId(), view.referenceNumber(),
				view.status(), view.submittedAt(), view.reviewerId(), view.reviewedAt(),
				view.flags()
					.stream()
					.map(f -> new PaymentSlipFlagResponse(f.id(), f.flagType(), f.detectedAt()))
					.toList(),
				view.studentEmail(), view.reviewerEmail(), view.orderAmount(), view.orderCurrency());
	}

}
