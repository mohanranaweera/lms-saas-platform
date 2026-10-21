package com.lms.paymentmanagement.checkout.web;

import com.lms.common.api.ApiResponse;
import com.lms.paymentmanagement.checkout.service.CheckoutComposerService;
import com.lms.paymentmanagement.checkout.service.CheckoutViews.CheckoutCart;
import com.lms.paymentmanagement.checkout.service.CheckoutViews.CheckoutCourse;
import com.lms.paymentmanagement.checkout.service.CheckoutViews.CheckoutOffers;
import com.lms.paymentmanagement.checkout.service.CheckoutViews.CheckoutOrder;
import com.lms.paymentmanagement.checkout.service.CheckoutViews.CheckoutQuote;
import com.lms.paymentmanagement.checkout.service.CheckoutViews.ItemSelection;
import com.lms.paymentmanagement.checkout.web.dto.CheckoutItemRequest;
import com.lms.paymentmanagement.checkout.web.dto.CheckoutOrderRequest;
import com.lms.paymentmanagement.checkout.web.dto.CheckoutQuoteRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * KLASS-T4c payment composer endpoints (plan §4.4) - student only, thin;
 * {@link CheckoutComposerService} does all validation, pricing and
 * authorization. Paying for the created order uses the existing {@code POST
 * /api/v1/orders/{id}/payments} and {@code POST /api/v1/orders/{orderId}/slips}.
 */
@RestController
@RequestMapping("/api/v1/checkout")
@PreAuthorize("hasRole('STUDENT')")
public class CheckoutComposerController {

	private final CheckoutComposerService composerService;

	public CheckoutComposerController(CheckoutComposerService composerService) {
		this.composerService = composerService;
	}

	@GetMapping("/courses")
	public ResponseEntity<ApiResponse<List<CheckoutCourse>>> courses(@RequestParam String scope) {
		return ResponseEntity.ok(ApiResponse.success(composerService.listCourses(scope)));
	}

	@GetMapping("/offers")
	public ResponseEntity<ApiResponse<CheckoutOffers>> offers(@RequestParam UUID courseId, @RequestParam String scope,
			@RequestParam(required = false) Integer year) {
		return ResponseEntity.ok(ApiResponse.success(composerService.offers(courseId, scope, year)));
	}

	@PostMapping("/quote")
	public ResponseEntity<ApiResponse<CheckoutQuote>> quote(@Valid @RequestBody CheckoutQuoteRequest request) {
		return ResponseEntity.ok(ApiResponse.success(composerService
			.quote(new CheckoutCart(request.courseId(), selections(request.items()), request.postageCourseIds(),
					request.discountCode(), request.acceptedRewardRuleIds()))));
	}

	/** 201 for a new order; 200 for an idempotency-key replay (same order returned). */
	@PostMapping("/orders")
	public ResponseEntity<ApiResponse<CheckoutOrder>> createOrder(@Valid @RequestBody CheckoutOrderRequest request) {
		CheckoutOrder order = composerService.createOrder(
				new CheckoutCart(request.courseId(), selections(request.items()), request.postageCourseIds(),
						request.discountCode(), request.acceptedRewardRuleIds()),
				request.quoteToken(), request.idempotencyKey());
		return ResponseEntity.status(order.idempotentReplay() ? HttpStatus.OK : HttpStatus.CREATED)
			.body(ApiResponse.success(order));
	}

	private static List<ItemSelection> selections(List<CheckoutItemRequest> items) {
		return items == null ? List.of() : items.stream().map(item -> item == null ? null : item.toSelection()).toList();
	}

}
