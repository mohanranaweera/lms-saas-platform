package com.lms.paymentmanagement.order.web;

import com.lms.common.api.ApiResponse;
import com.lms.paymentmanagement.order.service.OrderPaymentStatusView;
import com.lms.paymentmanagement.order.service.OrderService;
import com.lms.paymentmanagement.order.service.OrderView;
import com.lms.paymentmanagement.order.web.dto.OrderCreateRequest;
import com.lms.paymentmanagement.order.web.dto.OrderPaymentStatusResponse;
import com.lms.paymentmanagement.order.web.dto.OrderResponse;
import com.lms.paymentmanagement.order.web.dto.PaymentInitiationResponse;
import com.lms.paymentmanagement.payment.service.PaymentInitiationService;
import com.lms.paymentmanagement.payment.service.PaymentInitiationView;
import jakarta.validation.Valid;
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
 * PAY-1/PAY-2's student-facing order endpoints. Stays thin, delegates
 * entirely to {@link OrderService}/{@link PaymentInitiationService}, which
 * perform the real owner-or-staff-VIEW / student-only authorization checks
 * (mirroring {@code CourseController}'s "coarse {@code @PreAuthorize} gate,
 * real check in the service layer" discipline).
 */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

	private final OrderService orderService;

	private final PaymentInitiationService paymentInitiationService;

	public OrderController(OrderService orderService, PaymentInitiationService paymentInitiationService) {
		this.orderService = orderService;
		this.paymentInitiationService = paymentInitiationService;
	}

	@PostMapping
	@PreAuthorize("hasRole('STUDENT')")
	public ResponseEntity<ApiResponse<OrderResponse>> createOrder(@Valid @RequestBody OrderCreateRequest request) {
		OrderView view = orderService.createOrder(request.courseId());
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(toResponse(view)));
	}

	@GetMapping("/{id}")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<OrderResponse>> getOrder(@PathVariable UUID id) {
		OrderView view = orderService.getOrder(id);
		return ResponseEntity.ok(ApiResponse.success(toResponse(view)));
	}

	@GetMapping("/{id}/payment-status")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<OrderPaymentStatusResponse>> getPaymentStatus(@PathVariable UUID id) {
		OrderPaymentStatusView view = orderService.getPaymentStatus(id);
		return ResponseEntity.ok(ApiResponse.success(new OrderPaymentStatusResponse(view.hasPaymentAttempt(),
				view.paymentId(), view.status(), view.confirmedAt())));
	}

	/**
	 * Initiates a gateway payment attempt for this order - student-only,
	 * owner-only (never a staff caller, even with a {@code VIEW} grant). Not
	 * named in the module's originally-drafted endpoint table but required
	 * to exercise the plan's own §9 item 2 transaction-boundary shape
	 * end-to-end; the checkout flow needs an explicit "start paying" action
	 * distinct from order creation.
	 */
	@PostMapping("/{id}/payments")
	@PreAuthorize("hasRole('STUDENT')")
	public ResponseEntity<ApiResponse<PaymentInitiationResponse>> initiatePayment(@PathVariable UUID id) {
		PaymentInitiationView view = paymentInitiationService.initiatePayment(id);
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse.success(new PaymentInitiationResponse(view.paymentId(), view.orderId(), view.status(),
					view.gatewayReference(), view.redirectTarget())));
	}

	private static OrderResponse toResponse(OrderView view) {
		return new OrderResponse(view.id(), view.studentId(), view.courseId(), view.amount(), view.currency(),
				view.status(), view.createdAt(), view.updatedAt());
	}

}
