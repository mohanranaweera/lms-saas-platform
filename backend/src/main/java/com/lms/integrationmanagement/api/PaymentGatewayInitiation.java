package com.lms.integrationmanagement.api;

/**
 * The reference/redirect-target pair a payment gateway adapter returns when
 * a payment is initiated. {@code reference} is the opaque id the gateway
 * will later echo back on its confirmation webhook (persisted as {@code
 * payment.gateway_reference}); {@code redirectTarget} is whatever the
 * frontend needs to send the student to (a URL, a client token, etc. -
 * treated as an opaque string by the backend either way).
 */
public record PaymentGatewayInitiation(String reference, String redirectTarget) {

}
