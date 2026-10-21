package com.lms.integrationmanagement.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A verified provider status callback for one sent message (ADR-024 V8),
 * published by {@code integration-management} after the callback's signature
 * was checked with the tenant's own provider credentials. {@code tenantId}
 * comes from the stored webhook row the callback URL resolved to - never from
 * the payload - so a listener must apply it only within that tenant.
 *
 * @param providerMessageId the id the provider returned when the message was sent
 * @param status the provider status
 * @param occurredAt when the provider says the status happened
 * @param providerErrorCode the provider's numeric error code for {@link Status#FAILED}, else null
 */
public record MessagingDeliveryStatusEvent(UUID tenantId, MessagingChannelApi.Channel channel,
		String providerMessageId, Status status, Instant occurredAt, String providerErrorCode) {

	public enum Status {

		SENT, DELIVERED, READ, FAILED

	}

}
