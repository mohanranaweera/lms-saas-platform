package com.lms.integrationmanagement.web;

import com.lms.integrationmanagement.config.MediaIntegrationProperties;
import com.lms.integrationmanagement.domain.ClassSessionProviderEvent;
import com.lms.integrationmanagement.gateway.ZoomWebhookSignatures;
import com.lms.integrationmanagement.gateway.media.ProviderHttpClient;
import com.lms.integrationmanagement.repository.ClassSessionProviderEventRepository;
import com.lms.integrationmanagement.service.MediaConnectionStore;
import com.lms.liveclassmanagement.api.LiveClassWebhookApi;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Zoom's real webhook (MEDIA-INT-01, contract {@code
 * docs/api/integrations.md}). Public ({@code /api/v1/integrations/webhooks/**}
 * is already permitAll and skips tenant resolution).
 *
 * <ol>
 * <li>The Zoom signature and timestamp are verified FIRST - a failure is a
 * 401 with nothing written.</li>
 * <li>{@code endpoint.url_validation}: answer Zoom's challenge.</li>
 * <li>{@code app_deauthorized}: disconnect that Zoom user's connections
 * (ADR-018 §5 - the only cross-tenant lookup).</li>
 * <li>Meeting events: recorded once in the existing idempotency ledger,
 * tenant resolved only via our own {@code class_session} (ADR-011). No state
 * change in this phase; {@code recording.completed} is accepted and
 * ignored (recordings are MEDIA-INT-04).</li>
 * </ol>
 *
 * Responses are plain JSON (Zoom does not read our {@code ApiResponse}
 * envelope).
 */
@RestController
@RequestMapping("/api/v1/integrations/webhooks")
public class ZoomWebhookController {

	private static final Logger log = LoggerFactory.getLogger(ZoomWebhookController.class);

	private static final Logger securityLog = LoggerFactory.getLogger("SECURITY");

	private static final String PROVIDER = "ZOOM";

	private final MediaIntegrationProperties properties;

	private final MediaConnectionStore connectionStore;

	private final LiveClassWebhookApi liveClassWebhookApi;

	private final ClassSessionProviderEventRepository providerEventRepository;

	private final ObjectMapper objectMapper;

	public ZoomWebhookController(MediaIntegrationProperties properties, MediaConnectionStore connectionStore,
			LiveClassWebhookApi liveClassWebhookApi, ClassSessionProviderEventRepository providerEventRepository,
			ObjectMapper objectMapper) {
		this.properties = properties;
		this.connectionStore = connectionStore;
		this.liveClassWebhookApi = liveClassWebhookApi;
		this.providerEventRepository = providerEventRepository;
		this.objectMapper = objectMapper;
	}

	@PostMapping("/zoom")
	public ResponseEntity<Map<String, String>> receive(@RequestBody String rawBody,
			@RequestHeader(value = "x-zm-signature", required = false) String signature,
			@RequestHeader(value = "x-zm-request-timestamp", required = false) String timestamp) {
		String secret = properties.getZoomWebhookSecretToken();
		if (!ZoomWebhookSignatures.verify(secret, timestamp, rawBody, signature, Instant.now())) {
			securityLog.atWarn().setMessage("media_integration.zoom_webhook_rejected").log();
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
		}
		JsonNode body;
		try {
			body = objectMapper.readTree(rawBody);
		}
		catch (JacksonException ex) {
			log.atWarn().setMessage("media_integration.zoom_webhook_malformed").log();
			return ResponseEntity.ok().build();
		}
		String event = ProviderHttpClient.text(body, "event");
		JsonNode payload = body.path("payload");
		if (event == null) {
			return ResponseEntity.ok().build();
		}
		switch (event) {
			case "endpoint.url_validation" -> {
				String plainToken = ProviderHttpClient.text(payload, "plainToken");
				if (plainToken == null) {
					return ResponseEntity.badRequest().build();
				}
				return ResponseEntity.ok(Map.of("plainToken", plainToken, "encryptedToken",
						ZoomWebhookSignatures.encryptPlainToken(secret, plainToken)));
			}
			case "app_deauthorized" -> {
				int disconnected = connectionStore
					.disconnectDeauthorizedZoomUser(ProviderHttpClient.text(payload, "user_id"));
				log.atInfo()
					.setMessage("media_integration.zoom_deauthorized_handled")
					.addKeyValue("connections", disconnected)
					.log();
			}
			default -> recordMeetingEvent(event, ProviderHttpClient.text(body, "event_ts"),
					ProviderHttpClient.text(payload.path("object"), "id"));
		}
		return ResponseEntity.ok().build();
	}

	/** Idempotency ledger only - a duplicate delivery hits the unique constraint and is ignored. */
	private void recordMeetingEvent(String event, String eventTs, String meetingId) {
		if (meetingId == null) {
			return;
		}
		UUID tenantId = liveClassWebhookApi.resolveTenantId(meetingId).orElse(null);
		String key = truncate(event + ":" + eventTs + ":" + meetingId, 255);
		try {
			providerEventRepository.save(
					new ClassSessionProviderEvent(tenantId, PROVIDER, key, truncate(event, 50), Instant.now(), true));
		}
		catch (DataIntegrityViolationException ex) {
			log.atInfo().setMessage("media_integration.zoom_webhook_duplicate").log();
		}
	}

	private static String truncate(String value, int max) {
		return (value.length() <= max) ? value : value.substring(0, max);
	}

}
