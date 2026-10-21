package com.lms.paymentmanagement.order.web.dto;

import com.lms.paymentmanagement.payment.domain.PaymentStatus;
import java.util.Map;
import java.util.UUID;

/**
 * PAY-GW-01: {@code formAction}/{@code formFields} are set for a form-POST
 * gateway (PayHere) - the frontend submits exactly these, already signed
 * server-side; both are {@code null} otherwise.
 */
public record PaymentInitiationResponse(UUID paymentId, UUID orderId, PaymentStatus status, String gatewayReference,
		String redirectTarget, String formAction, Map<String, String> formFields) {

}
