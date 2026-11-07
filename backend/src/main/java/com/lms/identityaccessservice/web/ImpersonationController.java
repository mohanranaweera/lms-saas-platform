package com.lms.identityaccessservice.web;

import com.lms.common.api.ApiResponse;
import com.lms.identityaccessservice.service.ImpersonationService;
import com.lms.identityaccessservice.service.ImpersonationService.SessionView;
import com.lms.identityaccessservice.service.ImpersonationService.TokenGrant;
import com.lms.identityaccessservice.web.dto.ImpersonationResponses.ImpersonatedStudent;
import com.lms.identityaccessservice.web.dto.ImpersonationResponses.ImpersonationCurrentResponse;
import com.lms.identityaccessservice.web.dto.ImpersonationResponses.ImpersonationTokenResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADR-033 support-session endpoints. {@code /token} and {@code /end} are reachable
 * without a bearer token (see {@code SecurityFilterChainConfig}); the handoff secret
 * travels only in the HttpOnly {@code lms_impersonation} cookie. The access token is
 * returned in the body for in-memory use only and is never refreshable through
 * {@code /auth/refresh}.
 */
@RestController
@RequestMapping("/api/v1/auth/impersonation")
public class ImpersonationController {

	private final ImpersonationService impersonationService;

	public ImpersonationController(ImpersonationService impersonationService) {
		this.impersonationService = impersonationService;
	}

	@PostMapping("/token")
	public ResponseEntity<ApiResponse<ImpersonationTokenResponse>> token(
			@CookieValue(name = ImpersonationService.COOKIE_NAME, required = false) String handoffSecret) {
		TokenGrant grant = impersonationService.exchange(handoffSecret);
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(ApiResponse.success(new ImpersonationTokenResponse(grant.accessToken(), grant.expiresAt(),
					student(grant.session()), grant.session().actorName())));
	}

	@PostMapping("/end")
	public ResponseEntity<Void> end(
			@CookieValue(name = ImpersonationService.COOKIE_NAME, required = false) String handoffSecret) {
		impersonationService.end(handoffSecret);
		return ResponseEntity.noContent()
			.header(HttpHeaders.SET_COOKIE, impersonationService.clearCookieHeader())
			.build();
	}

	@GetMapping("/current")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<ImpersonationCurrentResponse>> current() {
		SessionView view = impersonationService.current();
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(ApiResponse.success(new ImpersonationCurrentResponse(view.sessionId(), view.expiresAt(),
					student(view), view.actorName())));
	}

	private static ImpersonatedStudent student(SessionView view) {
		return new ImpersonatedStudent(view.studentProfileId(), view.studentIndexNumber(), view.studentFullName());
	}

}
