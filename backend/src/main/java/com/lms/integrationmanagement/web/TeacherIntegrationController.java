package com.lms.integrationmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.integrationmanagement.service.MediaConnectionService;
import com.lms.integrationmanagement.service.MediaConnectionViews.AuthorizationUrlView;
import com.lms.integrationmanagement.service.MediaConnectionViews.ConnectionTestResult;
import com.lms.integrationmanagement.service.MediaConnectionViews.ConnectionView;
import com.lms.integrationmanagement.service.MediaConnectionViews.ImpactView;
import com.lms.integrationmanagement.service.MediaConnectionViews.TeacherIntegrationsView;
import com.lms.integrationmanagement.service.OAuthConnectFlowService;
import com.lms.integrationmanagement.web.dto.IntegrationRequests.DisconnectRequest;
import jakarta.validation.Valid;
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
 * A teacher's own Zoom/YouTube/Vimeo connections (MEDIA-INT-01). Coarse
 * {@code isAuthenticated()} gate only - the teacher-role and ownership checks
 * run in the services. No tenant or owner id is accepted.
 */
@RestController
@RequestMapping("/api/v1/teacher/integrations")
@PreAuthorize("isAuthenticated()")
public class TeacherIntegrationController {

	private final MediaConnectionService service;

	private final OAuthConnectFlowService connectFlow;

	public TeacherIntegrationController(MediaConnectionService service, OAuthConnectFlowService connectFlow) {
		this.service = service;
		this.connectFlow = connectFlow;
	}

	@GetMapping
	public ResponseEntity<ApiResponse<TeacherIntegrationsView>> overview() {
		return ResponseEntity.ok(ApiResponse.success(service.teacherOverview()));
	}

	@PostMapping("/{provider}/connect")
	public ResponseEntity<ApiResponse<AuthorizationUrlView>> connect(@PathVariable String provider) {
		return ResponseEntity.ok(ApiResponse.success(connectFlow.beginConnect(provider)));
	}

	/** Saves a finished OAuth authorization - only for the teacher who started it (security review S1). */
	@PostMapping("/pending/{pendingId}/confirm")
	public ResponseEntity<ApiResponse<ConnectionView>> confirm(@PathVariable String pendingId) {
		return ResponseEntity.ok(ApiResponse.success(connectFlow.confirmPending(pendingId)));
	}

	@PostMapping("/connections/{id}/test")
	public ResponseEntity<ApiResponse<ConnectionTestResult>> test(@PathVariable UUID id) {
		return ResponseEntity.ok(ApiResponse.success(service.testOwn(id)));
	}

	@GetMapping("/connections/{id}/impact")
	public ResponseEntity<ApiResponse<ImpactView>> impact(@PathVariable UUID id) {
		return ResponseEntity.ok(ApiResponse.success(service.impactOwn(id)));
	}

	@PostMapping("/connections/{id}/disconnect")
	public ResponseEntity<ApiResponse<ConnectionView>> disconnect(@PathVariable UUID id,
			@Valid @RequestBody(required = false) DisconnectRequest request) {
		return ResponseEntity
			.ok(ApiResponse.success(service.disconnectOwn(id, (request == null) ? null : request.reason())));
	}

}
