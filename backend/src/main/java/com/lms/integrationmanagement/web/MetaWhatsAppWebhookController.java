package com.lms.integrationmanagement.web;

import com.lms.integrationmanagement.service.MetaWhatsAppWebhookService;
import com.lms.integrationmanagement.service.MetaWhatsAppWebhookService.Outcome;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Meta WhatsApp status webhook (ADR-024 V8). Public: no session or subdomain
 * exists for these calls, so {@code /api/v1/integrations/webhooks/**} skips
 * tenant resolution and authenticity is checked in
 * {@link MetaWhatsAppWebhookService} - the verify token for the GET handshake,
 * the App Secret HMAC over the raw body for POST. Responses are plain (Meta
 * does not read our {@code ApiResponse} envelope).
 */
@RestController
@RequestMapping("/api/v1/integrations/webhooks/meta-whatsapp")
public class MetaWhatsAppWebhookController {

	/** Meta's status callbacks are small; anything larger is not a status callback. */
	private static final int MAX_BODY_BYTES = 1024 * 1024;

	private final MetaWhatsAppWebhookService service;

	public MetaWhatsAppWebhookController(MetaWhatsAppWebhookService service) {
		this.service = service;
	}

	@GetMapping(value = "/{webhookKey}", produces = MediaType.TEXT_PLAIN_VALUE)
	public ResponseEntity<String> verify(@PathVariable String webhookKey,
			@RequestParam(value = "hub.mode", required = false) String mode,
			@RequestParam(value = "hub.verify_token", required = false) String verifyToken,
			@RequestParam(value = "hub.challenge", required = false) String challenge) {
		return service.verifySubscription(webhookKey, mode, verifyToken, challenge)
			.map(ResponseEntity::ok)
			.orElseGet(() -> ResponseEntity.status(HttpStatus.FORBIDDEN).build());
	}

	@PostMapping("/{webhookKey}")
	public ResponseEntity<Void> receive(@PathVariable String webhookKey, @RequestBody(required = false) byte[] rawBody,
			@RequestHeader(value = "X-Hub-Signature-256", required = false) String signature) {
		if (rawBody == null || rawBody.length == 0) {
			return ResponseEntity.badRequest().build();
		}
		if (rawBody.length > MAX_BODY_BYTES) {
			return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
		}
		return (service.receive(webhookKey, rawBody, signature) == Outcome.ACCEPTED) ? ResponseEntity.ok().build()
				: ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
	}

}
