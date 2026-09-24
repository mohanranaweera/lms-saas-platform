package com.lms.integrationmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.integrationmanagement.api.LiveClassProviderApi;
import com.lms.integrationmanagement.domain.ClassSessionProviderEvent;
import com.lms.integrationmanagement.repository.ClassSessionProviderEventRepository;
import com.lms.integrationmanagement.web.dto.LiveClassWebhookPayload;
import com.lms.liveclassmanagement.api.LiveClassWebhookApi;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * Receives the (fake, Zoom-shaped) meeting provider's server-to-server
 * webhook (Wave 4). Deliberately owned by {@code integration-management},
 * never {@code live-class-management}, mirroring {@code
 * PaymentWebhookController}'s exact rationale - the webhook has no
 * subdomain/JWT to authenticate a normal request with; see {@code
 * TenantResolutionFilter}/{@code SecurityFilterChainConfig}'s existing {@code
 * "/api/v1/integrations/webhooks/**"} exclusions (permitAll +
 * tenant-resolution-skip), which already cover this new path without any
 * further change since they are wildcarded on the shared
 * {@code /api/v1/integrations/webhooks/} prefix.
 *
 * <p>Signature verification happens FIRST, before any parsing or state
 * change - a webhook that fails verification is rejected with NO {@code
 * class_session_provider_event} row created, per {@code
 * .claude/rules/security.md}'s webhook-verification requirement.
 *
 * <p>Idempotency: the idempotency-ledger row is inserted BEFORE any
 * live-class-management state change is attempted - a duplicate delivery
 * (same {@code (provider, providerEventId)}) hits {@code
 * uq_class_session_provider_event} and is caught here, turned into an
 * idempotent {@code 200 OK} no-op (never a 500, never a second mutation).
 */
@RestController
@RequestMapping("/api/v1/integrations/webhooks")
public class LiveClassWebhookController {

	private static final Logger log = LoggerFactory.getLogger(LiveClassWebhookController.class);

	private static final String SIGNATURE_HEADER = "X-Live-Class-Signature";

	private static final String PROVIDER = "ZOOM";

	private final LiveClassProviderApi liveClassProviderApi;

	private final LiveClassWebhookApi liveClassWebhookApi;

	private final ClassSessionProviderEventRepository providerEventRepository;

	private final ObjectMapper objectMapper;

	public LiveClassWebhookController(LiveClassProviderApi liveClassProviderApi, LiveClassWebhookApi liveClassWebhookApi,
			ClassSessionProviderEventRepository providerEventRepository, ObjectMapper objectMapper) {
		this.liveClassProviderApi = liveClassProviderApi;
		this.liveClassWebhookApi = liveClassWebhookApi;
		this.providerEventRepository = providerEventRepository;
		this.objectMapper = objectMapper;
	}

	@PostMapping(value = "/live-class", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ApiResponse<Void>> receiveLiveClassWebhook(@RequestBody String rawBody,
			@RequestHeader(value = SIGNATURE_HEADER, required = false) String signature) {
		if (!liveClassProviderApi.verifySignature(rawBody, signature)) {
			throw new AccessDeniedException("Invalid or missing webhook signature");
		}

		LiveClassWebhookPayload payload = objectMapper.readValue(rawBody, LiveClassWebhookPayload.class);
		if (payload.eventId() == null || payload.eventId().isBlank() || payload.eventType() == null
				|| payload.eventType().isBlank()) {
			// Malformed delivery (missing the idempotency key or event type) -
			// handled gracefully, never a 500, and never inserted into the
			// idempotency ledger (there is nothing valid to key it on).
			log.atWarn().setMessage("live_class.webhook.malformed_payload").log();
			return ResponseEntity.ok(ApiResponse.success(null));
		}

		ClassSessionProviderEvent recorded = recordDeliveryOrNull(payload);
		if (recorded == null) {
			// Duplicate delivery of an already-seen (provider, eventId) pair -
			// idempotent no-op, per class javadoc. Never re-run the
			// recording/attendance-sync side effects a second time.
			return ResponseEntity.ok(ApiResponse.success(null));
		}

		dispatch(payload);
		recorded.markProcessed();
		providerEventRepository.save(recorded);
		return ResponseEntity.ok(ApiResponse.success(null));
	}

	/** @return the newly-inserted ledger row, or {@code null} if this delivery is a duplicate. */
	private ClassSessionProviderEvent recordDeliveryOrNull(LiveClassWebhookPayload payload) {
		// Resolved from the platform's own class_session row, never trusted
		// from the payload (there is no tenantId field on LiveClassWebhookPayload
		// at all) - populated wherever resolvable, per V49's migration header.
		UUID tenantId = liveClassWebhookApi.resolveTenantId(payload.providerReference()).orElse(null);
		try {
			return providerEventRepository
				.save(new ClassSessionProviderEvent(tenantId, PROVIDER, payload.eventId(), payload.eventType(),
						Instant.now(), false));
		}
		catch (DataIntegrityViolationException ex) {
			log.atInfo()
				.setMessage("live_class.webhook.duplicate_delivery")
				.addKeyValue("provider", PROVIDER)
				.addKeyValue("eventId", payload.eventId())
				.log();
			return null;
		}
	}

	private void dispatch(LiveClassWebhookPayload payload) {
		String eventType = payload.eventType();
		if (eventType == null) {
			log.atWarn().setMessage("live_class.webhook.missing_event_type").log();
			return;
		}
		switch (eventType) {
			case "recording.completed" -> liveClassWebhookApi.handleRecordingCompleted(payload.providerReference(),
					payload.providerRecordingReference(), payload.durationSeconds());
			case "session.attendance_synced" -> liveClassWebhookApi.handleAttendanceSynced(payload.providerReference());
			default -> log.atInfo()
				.setMessage("live_class.webhook.unrecognized_event_type")
				.addKeyValue("eventType", eventType)
				.log();
		}
	}

}
