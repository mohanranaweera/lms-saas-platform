package com.lms.integrationmanagement.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Webhook request body shape. Deliberately declares NO {@code tenantId}
 * field - even if a test/fake gateway payload happens to include one for
 * debugging convenience, {@code @JsonIgnoreProperties(ignoreUnknown = true)}
 * makes it structurally impossible for that value to ever be read here, let
 * alone reach {@link com.lms.common.tenant.TenantContextHolder#set(java.util.UUID)}
 * - tenant identity for a webhook-driven update is resolved exclusively from
 * the platform's own {@code Payment} row the gateway's reference maps to,
 * per {@code PaymentConfirmationService}'s javadoc.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentWebhookPayload(String gatewayReference, boolean confirmed) {

}
