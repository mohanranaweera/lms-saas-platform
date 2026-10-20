package com.lms.paymentmanagement.checkout.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * {@code POST /api/v1/checkout/orders} = the quote body + {@code quoteToken}
 * (required; the token of the quote the student confirmed - a 422 {@code
 * QUOTE_REQUIRED} without it, 409 {@code QUOTE_CHANGED} when the server
 * re-quote differs) + {@code idempotencyKey} (optional client-generated
 * dedup key scoped to (tenant, student) - never an identity signal). The
 * server re-quotes; any client total is ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CheckoutOrderRequest(UUID courseId, @Valid @Size(max = 100) List<CheckoutItemRequest> items,
		@Size(max = 20) List<UUID> postageCourseIds, @Size(max = 128) String quoteToken, UUID idempotencyKey) {

}
