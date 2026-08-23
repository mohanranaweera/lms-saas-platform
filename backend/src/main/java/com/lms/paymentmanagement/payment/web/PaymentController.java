package com.lms.paymentmanagement.payment.web;

import com.lms.common.api.ApiResponse;
import com.lms.paymentmanagement.payment.service.PaymentQueryService;
import com.lms.paymentmanagement.payment.service.PaymentView;
import com.lms.paymentmanagement.payment.web.dto.PaymentResponse;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

	private final PaymentQueryService paymentQueryService;

	public PaymentController(PaymentQueryService paymentQueryService) {
		this.paymentQueryService = paymentQueryService;
	}

	@GetMapping("/{id}")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<PaymentResponse>> getPayment(@PathVariable UUID id) {
		PaymentView view = paymentQueryService.getPayment(id);
		return ResponseEntity.ok(ApiResponse.success(toResponse(view)));
	}

	private static PaymentResponse toResponse(PaymentView view) {
		return new PaymentResponse(view.id(), view.orderId(), view.amount(), view.currency(), view.status(),
				view.gatewayReference(), view.confirmedAt(), view.createdAt());
	}

}
