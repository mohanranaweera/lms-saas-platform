package com.lms.integrationmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.integrationmanagement.service.MediaConnectionService;
import com.lms.integrationmanagement.service.MediaConnectionViews.ConnectionTestResult;
import com.lms.integrationmanagement.service.MediaConnectionViews.ConnectionView;
import com.lms.integrationmanagement.service.MediaConnectionViews.ImpactView;
import com.lms.integrationmanagement.service.MediaConnectionViews.TeacherProvidersView;
import com.lms.integrationmanagement.service.MediaConnectionViews.TenantIntegrationsView;
import com.lms.integrationmanagement.service.MediaConnectionViews.VideoHostView;
import com.lms.integrationmanagement.web.dto.IntegrationRequests.BunnyAccountRequest;
import com.lms.integrationmanagement.web.dto.IntegrationRequests.DisconnectRequest;
import com.lms.integrationmanagement.web.dto.IntegrationRequests.DynTubeAccountRequest;
import com.lms.integrationmanagement.web.dto.IntegrationRequests.TeacherProvidersRequest;
import com.lms.integrationmanagement.web.dto.IntegrationRequests.VideoHostRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant Admin integrations page (MEDIA-INT-01). Coarse {@code
 * isAuthenticated()} gate only - the {@code INTEGRATIONS} permission check
 * runs in the service. Tenant comes from the authenticated context.
 */
@RestController
@RequestMapping("/api/v1/tenant-admin/integrations")
@PreAuthorize("isAuthenticated()")
public class TenantAdminIntegrationController {

	private final MediaConnectionService service;

	public TenantAdminIntegrationController(MediaConnectionService service) {
		this.service = service;
	}

	@GetMapping
	public ResponseEntity<ApiResponse<TenantIntegrationsView>> overview() {
		return ResponseEntity.ok(ApiResponse.success(service.adminOverview()));
	}

	@PutMapping("/teacher-providers")
	public ResponseEntity<ApiResponse<TeacherProvidersView>> updateTeacherProviders(
			@Valid @RequestBody TeacherProvidersRequest request) {
		return ResponseEntity.ok(ApiResponse.success(service.updateTeacherProviders(request.zoomEnabled(),
				request.youtubeEnabled(), request.vimeoEnabled())));
	}

	@PutMapping("/dyntube")
	public ResponseEntity<ApiResponse<ConnectionView>> saveDynTube(@Valid @RequestBody DynTubeAccountRequest request) {
		return ResponseEntity.ok(ApiResponse.success(service.saveDynTube(request.apiKey())));
	}

	@PutMapping("/bunny")
	public ResponseEntity<ApiResponse<ConnectionView>> saveBunny(@Valid @RequestBody BunnyAccountRequest request) {
		return ResponseEntity.ok(ApiResponse.success(service.saveBunny(request.apiKey(), request.libraryId(),
				request.cdnHostname(), request.tokenAuthKey())));
	}

	@PostMapping("/connections/{id}/test")
	public ResponseEntity<ApiResponse<ConnectionTestResult>> test(@PathVariable UUID id) {
		return ResponseEntity.ok(ApiResponse.success(service.adminTest(id)));
	}

	@GetMapping("/connections/{id}/impact")
	public ResponseEntity<ApiResponse<ImpactView>> impact(@PathVariable UUID id) {
		return ResponseEntity.ok(ApiResponse.success(service.adminImpact(id)));
	}

	@PostMapping("/connections/{id}/disconnect")
	public ResponseEntity<ApiResponse<ConnectionView>> disconnect(@PathVariable UUID id,
			@Valid @RequestBody(required = false) DisconnectRequest request) {
		return ResponseEntity
			.ok(ApiResponse.success(service.adminDisconnect(id, (request == null) ? null : request.reason())));
	}

	@PutMapping("/video-host")
	public ResponseEntity<ApiResponse<VideoHostView>> setVideoHost(@Valid @RequestBody VideoHostRequest request) {
		return ResponseEntity.ok(ApiResponse.success(service.setVideoHost(request.videoHost())));
	}

}
