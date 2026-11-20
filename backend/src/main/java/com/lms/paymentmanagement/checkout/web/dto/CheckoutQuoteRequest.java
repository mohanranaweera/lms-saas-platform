package com.lms.paymentmanagement.checkout.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * {@code POST /api/v1/checkout/quote}. No tenant/student/price fields - all
 * server-resolved. {@code courseId} (optional) is the default course of items
 * without their own {@code courseId}; {@code postageCourseIds} = courses the
 * student wants posted (each must have an item in the cart). DISC-01:
 * {@code discountCode} (optional, at most 40 chars) and {@code
 * acceptedRewardRuleIds} (free-course offers the student accepted, at most 10).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CheckoutQuoteRequest(UUID courseId, @Valid @Size(max = 100) List<CheckoutItemRequest> items,
		@Size(max = 20) List<UUID> postageCourseIds, @Size(max = 40) String discountCode,
		@Size(max = 10) List<UUID> acceptedRewardRuleIds) {

	public CheckoutQuoteRequest(UUID courseId, List<CheckoutItemRequest> items) {
		this(courseId, items, null, null, null);
	}

	public CheckoutQuoteRequest(UUID courseId, List<CheckoutItemRequest> items, List<UUID> postageCourseIds) {
		this(courseId, items, postageCourseIds, null, null);
	}

}
