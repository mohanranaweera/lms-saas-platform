package com.lms.integrationmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.integrationmanagement.service.MediaConnectionViews.AuthorizationUrlView;
import com.lms.integrationmanagement.service.MediaConnectionViews.ConnectionTestResult;
import com.lms.integrationmanagement.service.MediaConnectionViews.ConnectionView;
import com.lms.integrationmanagement.service.TenantVideoProviderService;
import com.lms.integrationmanagement.service.TenantVideoProviderService.PendingChannelView;
import com.lms.integrationmanagement.service.TenantVideoProviderService.VideoProviderAccountView;
import com.lms.integrationmanagement.web.dto.IntegrationRequests.DisconnectRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant Admin → Settings → Video Providers (VIDEO Phase 3). Coarse {@code
 * isAuthenticated()} gate only - the {@code INTEGRATIONS} permission check
 * and tenant scoping run in {@link TenantVideoProviderService}. Responses
 * never contain provider tokens.
 */
@RestController
@RequestMapping("/api/v1/tenant-admin/video-providers")
@PreAuthorize("isAuthenticated()")
public class TenantVideoProviderController {

	private final TenantVideoProviderService service;

	public TenantVideoProviderController(TenantVideoProviderService service) {
		this.service = service;
	}

	@GetMapping
	public ResponseEntity<ApiResponse<List<VideoProviderAccountView>>> list() {
		return ResponseEntity.ok(ApiResponse.success(service.list()));
	}

	@PostMapping("/youtube/connect")
	public ResponseEntity<ApiResponse<AuthorizationUrlView>> connectYouTube() {
		return ResponseEntity.ok(ApiResponse.success(service.beginYouTubeConnect()));
	}

	@GetMapping("/youtube/pending/{pendingId}")
	public ResponseEntity<ApiResponse<PendingChannelView>> previewPending(@PathVariable String pendingId) {
		return ResponseEntity.ok(ApiResponse.success(service.previewPending(pendingId)));
	}

	@PostMapping("/youtube/pending/{pendingId}/confirm")
	public ResponseEntity<ApiResponse<VideoProviderAccountView>> confirmPending(@PathVariable String pendingId,
			@RequestBody(required = false) ConfirmChannelRequest request) {
		boolean replace = request != null && Boolean.TRUE.equals(request.replaceChannel());
		return ResponseEntity.ok(ApiResponse.success(service.confirmPending(pendingId, replace)));
	}

	@PostMapping("/{connectionId}/test")
	public ResponseEntity<ApiResponse<ConnectionTestResult>> test(@PathVariable UUID connectionId) {
		return ResponseEntity.ok(ApiResponse.success(service.test(connectionId)));
	}

	@PostMapping("/{connectionId}/disconnect")
	public ResponseEntity<ApiResponse<ConnectionView>> disconnect(@PathVariable UUID connectionId,
			@Valid @RequestBody(required = false) DisconnectRequest request) {
		return ResponseEntity
			.ok(ApiResponse.success(service.disconnect(connectionId, (request == null) ? null : request.reason())));
	}

	/** {@code replaceChannel} must be true to swap the tenant's current channel for a different one. */
	public record ConfirmChannelRequest(Boolean replaceChannel) {

	}

}
