package com.lms.integrationmanagement.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Webhook request body shape for the live-class meeting-provider webhook.
 * Deliberately declares NO {@code tenantId} field - mirroring {@code
 * PaymentWebhookPayload}'s exact rationale: {@code
 * @JsonIgnoreProperties(ignoreUnknown = true)} makes it structurally
 * impossible for a client-supplied tenant value to ever be read here: tenant
 * identity for a webhook-driven update is resolved exclusively from the
 * platform's own {@code class_session} row {@code providerReference} maps
 * to, per {@code LiveClassWebhookProcessingService}'s javadoc.
 *
 * @param eventId the provider's own event id - the idempotency key, together
 * with {@code provider} (fixed to {@code "ZOOM"} this wave).
 * @param eventType one of {@code "recording.completed"}/{@code
 * "session.attendance_synced"} (others are accepted and ignored, never a
 * 500).
 * @param providerReference the opaque meeting reference this event concerns
 * - resolved against {@code class_session.provider_reference}, never trusted
 * as a session/tenant id directly.
 * @param providerRecordingReference present only for {@code
 * "recording.completed"} - the opaque recording reference, never a playback
 * URL.
 * @param durationSeconds present only for {@code "recording.completed"}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LiveClassWebhookPayload(String eventId, String eventType, String providerReference,
		String providerRecordingReference, Integer durationSeconds) {

}
