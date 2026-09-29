package com.lms.integrationmanagement.web;

import com.lms.integrationmanagement.service.OAuthConnectFlowService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The single OAuth redirect URI registered with Zoom/Google/Vimeo (ADR-018
 * §3). Public: permitAll and excluded from subdomain tenant resolution in
 * {@code SecurityFilterChainConfig}/{@code TenantResolutionFilter}. Its only
 * trust anchor is the single-use {@code state}; the tenant and teacher are
 * never read from this request. Always answers with a 302.
 */
@RestController
@RequestMapping("/api/v1/integrations/oauth")
public class OAuthCallbackController {

	private final OAuthConnectFlowService connectFlow;

	public OAuthCallbackController(OAuthConnectFlowService connectFlow) {
		this.connectFlow = connectFlow;
	}

	@GetMapping("/{provider}/callback")
	public ResponseEntity<Void> callback(@PathVariable String provider,
			@RequestParam(value = "code", required = false) String code,
			@RequestParam(value = "error", required = false) String error,
			@RequestParam(value = "state", required = false) String state) {
		return ResponseEntity.status(HttpStatus.FOUND)
			.header(HttpHeaders.LOCATION, connectFlow.completeCallback(provider, code, error, state).toString())
			.header(HttpHeaders.CACHE_CONTROL, "no-store")
			.build();
	}

}
