package com.lms.paymentmanagement.checkout.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.lms.paymentmanagement.checkout.service.CheckoutViews.ItemSelection;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * One composer item (plan §4.4; multi-course checkout §4). Deliberately has
 * NO price/amount/currency field: any client-sent price is silently dropped
 * ({@code ignoreUnknown}) and the server prices every item from its own
 * offers. {@code scope} is a plain string so an unsupported value is a 422
 * {@code ITEM_NOT_ELIGIBLE}, not a deserialization error. {@code courseId}
 * (optional) = the item's course; the request's top-level {@code courseId}
 * is the default for items without one.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CheckoutItemRequest(UUID courseId, @Size(max = 20) String scope, Integer year, Integer month,
		UUID moduleId, UUID lessonId) {

	public ItemSelection toSelection() {
		return new ItemSelection(scope == null ? null : scope.trim(), year, month, moduleId, lessonId, courseId);
	}

}
