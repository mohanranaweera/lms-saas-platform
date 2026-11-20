package com.lms.integrationmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.integrationmanagement.service.GoogleOAuthAppService;
import com.lms.integrationmanagement.service.GoogleOAuthAppService.GoogleOAuthAppView;
import com.lms.integrationmanagement.service.GoogleOAuthAppService.VerifyView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform Admin → the platform's Google OAuth app used by every
 * institute's YouTube channel (VIDEO Phase 5c, ADR-018 Amendment 5). The
 * secret is write-only; validation, encryption and history live in
 * {@link GoogleOAuthAppService}.
 */
@RestController
@RequestMapping("/api/v1/platform-admin/oauth-apps/google")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class PlatformAdminOAuthAppController {

	private final GoogleOAuthAppService service;

	public PlatformAdminOAuthAppController(GoogleOAuthAppService service) {
		this.service = service;
	}

	@GetMapping
	public ResponseEntity<ApiResponse<GoogleOAuthAppView>> get() {
		return ResponseEntity.ok(ApiResponse.success(service.view()));
	}

	@PutMapping
	public ResponseEntity<ApiResponse<GoogleOAuthAppView>> save(@Valid @RequestBody GoogleOAuthAppRequest request) {
		return ResponseEntity.ok(ApiResponse.success(service.save(request.clientId(), request.clientSecret())));
	}

	@DeleteMapping
	public ResponseEntity<ApiResponse<GoogleOAuthAppView>> remove() {
		return ResponseEntity.ok(ApiResponse.success(service.remove()));
	}

	@PostMapping("/verify")
	public ResponseEntity<ApiResponse<VerifyView>> verify() {
		return ResponseEntity.ok(ApiResponse.success(service.verify()));
	}

	/** {@code toString} never prints the secret. */
	public record GoogleOAuthAppRequest(@Size(max = 1000) String clientId, @Size(max = 1000) String clientSecret) {

		@Override
		public String toString() {
			return "GoogleOAuthAppRequest[clientId=" + clientId + ", clientSecret=****]";
		}

	}

}
