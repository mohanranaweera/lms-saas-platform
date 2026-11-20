package com.lms.paymentmanagement.manualpayment.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.lms.paymentmanagement.checkout.web.dto.CheckoutItemRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * {@code POST /api/v1/staff/students/{studentId}/manual-payments/quote}
 * (ADR-030). No tenant, student, price or discount-code field - the student
 * is the URL's student_profile id resolved server-side, prices are the
 * server's offers.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ManualPaymentQuoteRequest(@Valid @Size(max = 100) List<CheckoutItemRequest> items,
		@Size(max = 20) List<UUID> postageCourseIds) {

}
