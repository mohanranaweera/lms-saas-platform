package com.lms.integrationmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.integrationmanagement.service.MediaConnectionViews.AuthorizationUrlView;
import com.lms.integrationmanagement.service.MediaConnectionViews.ConnectionTestResult;
import com.lms.integrationmanagement.service.MediaConnectionViews.ConnectionView;
import com.lms.integrationmanagement.service.TenantVideoProviderService;
import com.lms.integrationmanagement.service.TenantVideoProviderService.PendingChannelView;
import com.lms.integrationmanagement.service.TenantMediaIntegrationService.IntegrationTeacher;
import com.lms.integrationmanagement.service.TenantVideoProviderService.VideoProviderAccountView;
import com.lms.integrationmanagement.web.dto.IntegrationRequests.DisconnectRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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

	/** VIDEO Phase 5d: a new YouTube integration card (connected separately). */
	@PostMapping("/youtube/integrations")
	public ResponseEntity<ApiResponse<VideoProviderAccountView>> createIntegration(
			@Valid @RequestBody IntegrationRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse.success(service.createIntegration(request.name(), request.description(),
					request.allowedTeacherIds())));
	}

	@PutMapping("/youtube/integrations/{integrationId}")
	public ResponseEntity<ApiResponse<VideoProviderAccountView>> updateIntegration(@PathVariable UUID integrationId,
			@Valid @RequestBody IntegrationRequest request) {
		return ResponseEntity.ok(ApiResponse.success(service.updateIntegration(integrationId, request.name(),
				request.description(), request.allowedTeacherIds())));
	}

	/** Disconnects the card's channel and archives the card; its videos stay (Archived tab). */
	@DeleteMapping("/youtube/integrations/{integrationId}")
	public ResponseEntity<ApiResponse<Void>> deleteIntegration(@PathVariable UUID integrationId) {
		service.deleteIntegration(integrationId);
		return ResponseEntity.ok(ApiResponse.success(null));
	}

	@PostMapping("/youtube/integrations/{integrationId}/connect")
	public ResponseEntity<ApiResponse<AuthorizationUrlView>> connectYouTube(@PathVariable UUID integrationId) {
		return ResponseEntity.ok(ApiResponse.success(service.beginYouTubeConnect(integrationId)));
	}

	/** The institute's teachers by user id - the choices for an integration's allowed teachers. */
	@GetMapping("/youtube/teachers")
	public ResponseEntity<ApiResponse<List<IntegrationTeacher>>> teacherChoices() {
		return ResponseEntity.ok(ApiResponse.success(service.teacherChoices()));
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

	/** {@code allowedTeacherIds} empty (or absent) = every teacher may upload. */
	public record IntegrationRequest(@Size(max = 200) String name, @Size(max = 1000) String description,
			@Size(max = 500) List<UUID> allowedTeacherIds) {

	}

	/** {@code replaceChannel} must be true to swap the card's current channel for a different one. */
	public record ConfirmChannelRequest(Boolean replaceChannel) {

	}

}
