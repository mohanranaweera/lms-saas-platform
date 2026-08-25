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
import com.lms.paymentmanagement.slip.service.PaymentSlipView;
import com.lms.paymentmanagement.slip.service.SlipUploadService;
import com.lms.paymentmanagement.slip.web.dto.PaymentSlipFlagResponse;
import com.lms.paymentmanagement.slip.web.dto.PaymentSlipResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * PAY-1/PAY-2's student-facing order endpoints, plus MVP-011's slip-upload
 * endpoint (moved here from {@code SlipController} per this codebase's
 * established "an action nested under a different resource's URL lives in
 * the controller that owns that URL prefix" convention - see {@link
 * #initiatePayment}, the pre-existing precedent for exactly this shape).
 * Stays thin, delegates entirely to {@link OrderService}/{@link
 * PaymentInitiationService}/{@link SlipUploadService}, which perform the
 * real owner-or-staff-VIEW / student-only authorization checks (mirroring
 * {@code CourseController}'s "coarse {@code @PreAuthorize} gate, real check
 * in the service layer" discipline). The slip-read endpoints
 * ({@code GET /api/v1/payment-slips/**}) stay in {@link SlipController} -
 * they own a different URL prefix, not one nested under {@code /orders}.
 */
@RestController
@RequestMapping("/api/v1/orders")
@Validated
public class OrderController {

	private final OrderService orderService;

	private final PaymentInitiationService paymentInitiationService;

	private final SlipUploadService slipUploadService;

	public OrderController(OrderService orderService, PaymentInitiationService paymentInitiationService,
			SlipUploadService slipUploadService) {
		this.orderService = orderService;
		this.paymentInitiationService = paymentInitiationService;
		this.slipUploadService = slipUploadService;
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

	/**
	 * SLIP-1's upload endpoint - moved here from {@code SlipController} (see
	 * class javadoc). The URL is unchanged: {@code
	 * POST /api/v1/orders/{orderId}/slips}, student-only, owner-only (never a
	 * client-supplied studentId - see {@link
	 * SlipUploadService#uploadSlip(UUID, String, MultipartFile)}'s javadoc).
	 */
	@PostMapping(path = "/{orderId}/slips", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@PreAuthorize("hasRole('STUDENT')")
	public ResponseEntity<ApiResponse<PaymentSlipResponse>> uploadSlip(@PathVariable UUID orderId,
			@RequestParam("referenceNumber") @NotBlank @Size(max = 255) String referenceNumber,
			@RequestPart("file") MultipartFile file) {
		PaymentSlipView view = slipUploadService.uploadSlip(orderId, referenceNumber, file);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(toSlipResponse(view)));
	}

	private static OrderResponse toResponse(OrderView view) {
		return new OrderResponse(view.id(), view.studentId(), view.courseId(), view.amount(), view.currency(),
				view.status(), view.createdAt(), view.updatedAt());
	}

	private static PaymentSlipResponse toSlipResponse(PaymentSlipView view) {
		return new PaymentSlipResponse(view.id(), view.orderId(), view.studentId(), view.referenceNumber(),
				view.status(), view.submittedAt(), view.reviewerId(), view.reviewedAt(),
				view.flags()
					.stream()
					.map(f -> new PaymentSlipFlagResponse(f.id(), f.flagType(), f.detectedAt()))
					.toList(),
				view.studentEmail(), view.reviewerEmail(), view.orderAmount(), view.orderCurrency());
	}

}
