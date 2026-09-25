package com.lms.paymentmanagement.order.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

/**
 * {@code POST /api/v1/orders/{id}/payments} request body (Wave 6 §3.3/§4) -
 * carries ONLY the optional, client-generated {@code idempotencyKey}, never
 * a caller-supplied {@code tenantId}/{@code orderId}/{@code amount} (those
 * remain server-resolved from the path/authenticated context exactly as
 * before this wave). The request body itself is optional at the controller
 * level - a caller that sends no body at all (this wave's predecessor
 * behavior) behaves identically to one that sends {@code {}} or an explicit
 * {@code null} key.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentInitiationRequest(UUID idempotencyKey) {

}
