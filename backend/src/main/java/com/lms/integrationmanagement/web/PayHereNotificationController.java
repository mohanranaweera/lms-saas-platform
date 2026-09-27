package com.lms.integrationmanagement.web;

import com.lms.integrationmanagement.service.PayHereNotificationService;
import com.lms.integrationmanagement.service.PayHereNotificationService.Outcome;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * PayHere's server-to-server {@code notify_url} (PAY-GW-01 / ADR-017).
 * Unauthenticated and outside subdomain tenant resolution by design - covered
 * by ADR-011's {@code /api/v1/integrations/webhooks/**} carve-out; all
 * authenticity checks live in {@link PayHereNotificationService}. PayHere
 * posts {@code application/x-www-form-urlencoded}, not JSON.
 */
@RestController
@RequestMapping("/api/v1/integrations/webhooks")
public class PayHereNotificationController {

	private final PayHereNotificationService notificationService;

	public PayHereNotificationController(PayHereNotificationService notificationService) {
		this.notificationService = notificationService;
	}

	@PostMapping(value = "/payhere", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
	public ResponseEntity<Void> receive(@RequestParam MultiValueMap<String, String> params) {
		Outcome outcome = notificationService.handle(params.toSingleValueMap());
		return (outcome == Outcome.REJECTED) ? ResponseEntity.badRequest().build() : ResponseEntity.ok().build();
	}

}
