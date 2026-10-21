package com.lms.paymentmanagement.manualpayment.web;

import com.lms.common.api.ApiResponse;
import com.lms.paymentmanagement.checkout.service.CheckoutViews.CheckoutCart;
import com.lms.paymentmanagement.checkout.service.CheckoutViews.CheckoutOffers;
import com.lms.paymentmanagement.checkout.service.CheckoutViews.CheckoutQuote;
import com.lms.paymentmanagement.checkout.service.CheckoutViews.ItemSelection;
import com.lms.paymentmanagement.checkout.web.dto.CheckoutItemRequest;
import com.lms.paymentmanagement.manualpayment.service.ManualPaymentQueryService;
import com.lms.paymentmanagement.manualpayment.service.ManualPaymentService;
import com.lms.paymentmanagement.manualpayment.service.ManualPaymentViews.HistoryEntry;
import com.lms.paymentmanagement.manualpayment.service.ManualPaymentViews.PaymentContext;
import com.lms.paymentmanagement.manualpayment.service.ManualPaymentViews.ReceiptView;
import com.lms.paymentmanagement.manualpayment.service.ManualPaymentViews.RecordCommand;
import com.lms.paymentmanagement.manualpayment.web.dto.ManualPaymentQuoteRequest;
import com.lms.paymentmanagement.manualpayment.web.dto.ManualPaymentRecordRequest;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADR-030 staff manual payment endpoints (plan §4). {@code {studentId}} is a
 * student_profile id, resolved server-side within the caller's tenant (404
 * otherwise). {@code @PreAuthorize} is a coarse gate on the same {@code
 * PAYMENTS_SLIPS} area the services check again (VIEW for reads, APPROVE for
 * quote/record) - a student caller is 403 either way.
 */
@RestController
@RequestMapping("/api/v1/staff/students/{studentId}")
public class ManualPaymentController {

	static final String VIEW = "@permissionCheckService.hasPermission('PAYMENTS_SLIPS', 'VIEW')";

	static final String APPROVE = "@permissionCheckService.hasPermission('PAYMENTS_SLIPS', 'APPROVE')";

	private final ManualPaymentService manualPaymentService;

	private final ManualPaymentQueryService queryService;

	public ManualPaymentController(ManualPaymentService manualPaymentService, ManualPaymentQueryService queryService) {
		this.manualPaymentService = manualPaymentService;
		this.queryService = queryService;
	}

	@GetMapping("/payment-context")
	@PreAuthorize(VIEW)
	public ResponseEntity<ApiResponse<PaymentContext>> paymentContext(@PathVariable UUID studentId) {
		return ResponseEntity.ok(ApiResponse.success(queryService.paymentContext(studentId)));
	}

	@GetMapping("/payment-offers")
	@PreAuthorize(VIEW)
	public ResponseEntity<ApiResponse<CheckoutOffers>> paymentOffers(@PathVariable UUID studentId,
			@RequestParam UUID courseId, @RequestParam String scope, @RequestParam(required = false) Integer year) {
		return ResponseEntity.ok(ApiResponse.success(queryService.offers(studentId, courseId, scope, year)));
	}

	@GetMapping("/payment-history")
	@PreAuthorize(VIEW)
	public ResponseEntity<ApiResponse<List<HistoryEntry>>> paymentHistory(@PathVariable UUID studentId,
			@RequestParam UUID courseId) {
		return ResponseEntity.ok(ApiResponse.success(queryService.history(studentId, courseId)));
	}

	@PostMapping("/manual-payments/quote")
	@PreAuthorize(APPROVE)
	public ResponseEntity<ApiResponse<CheckoutQuote>> quote(@PathVariable UUID studentId,
			@Valid @RequestBody ManualPaymentQuoteRequest request) {
		CheckoutCart cart = new CheckoutCart(null, selections(request.items()), request.postageCourseIds(), null,
				List.of());
		return ResponseEntity.ok(ApiResponse.success(manualPaymentService.quote(studentId, cart)));
	}

	/** 201 for a new receipt; 200 for an idempotency-key replay (same receipt returned). */
	@PostMapping("/manual-payments")
	@PreAuthorize(APPROVE)
	public ResponseEntity<ApiResponse<ReceiptView>> record(@PathVariable UUID studentId,
			@Valid @RequestBody ManualPaymentRecordRequest request) {
		ReceiptView receipt = manualPaymentService.record(studentId,
				new RecordCommand(selections(request.items()), request.postageCourseIds(), request.quoteToken(),
						request.staffAdjustmentAmount(), request.adjustmentReason(), request.expectedReceivedAmount(),
						request.paymentMethod(), request.receivedOn(), request.referenceNumber(), request.notes(),
						request.idempotencyKey()));
		return ResponseEntity.status(receipt.replay() ? HttpStatus.OK : HttpStatus.CREATED)
			.body(ApiResponse.success(receipt));
	}

	private static List<ItemSelection> selections(List<CheckoutItemRequest> items) {
		return items == null ? List.of() : items.stream().map(item -> item == null ? null : item.toSelection()).toList();
	}

}
