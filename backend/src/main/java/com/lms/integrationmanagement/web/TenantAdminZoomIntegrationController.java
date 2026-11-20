package com.lms.integrationmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.integrationmanagement.service.ZoomIntegrationFoundationService;
import com.lms.integrationmanagement.service.ZoomIntegrationViews.ExternalUserView;
import com.lms.integrationmanagement.service.ZoomIntegrationViews.IntegrationView;
import com.lms.integrationmanagement.web.dto.ZoomIntegrationRequests.MapTeacherRequest;
import com.lms.integrationmanagement.web.dto.ZoomIntegrationRequests.SaveIntegrationRequest;
import jakarta.validation.Valid;
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

/** Tenant-owned Zoom integration foundation. OAuth and provider calls arrive in later waves. */
@RestController
@RequestMapping("/api/v1/tenant-admin/zoom-integrations")
@PreAuthorize("isAuthenticated()")
public class TenantAdminZoomIntegrationController {

	private final ZoomIntegrationFoundationService service;

	public TenantAdminZoomIntegrationController(ZoomIntegrationFoundationService service) {
		this.service = service;
	}

	@GetMapping
	public ResponseEntity<ApiResponse<List<IntegrationView>>> list() {
		return ResponseEntity.ok(ApiResponse.success(service.list()));
	}

	@PostMapping
	public ResponseEntity<ApiResponse<IntegrationView>> create(@Valid @RequestBody SaveIntegrationRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(service.create(request.name(),
				request.description(), request.teacherPolicy(), request.allowedTeacherIds())));
	}

	@GetMapping("/{integrationId}")
	public ResponseEntity<ApiResponse<IntegrationView>> get(@PathVariable UUID integrationId) {
		return ResponseEntity.ok(ApiResponse.success(service.get(integrationId)));
	}

	@PutMapping("/{integrationId}")
	public ResponseEntity<ApiResponse<IntegrationView>> update(@PathVariable UUID integrationId,
			@Valid @RequestBody SaveIntegrationRequest request) {
		return ResponseEntity.ok(ApiResponse.success(service.update(integrationId, request.name(), request.description(),
				request.teacherPolicy(), request.allowedTeacherIds())));
	}

	@DeleteMapping("/{integrationId}")
	public ResponseEntity<ApiResponse<Void>> archive(@PathVariable UUID integrationId) {
		service.archive(integrationId);
		return ResponseEntity.ok(ApiResponse.success(null));
	}

	@GetMapping("/{integrationId}/external-users")
	public ResponseEntity<ApiResponse<List<ExternalUserView>>> externalUsers(@PathVariable UUID integrationId) {
		return ResponseEntity.ok(ApiResponse.success(service.listExternalUsers(integrationId)));
	}

	@PutMapping("/{integrationId}/teacher-mappings/{teacherUserId}")
	public ResponseEntity<ApiResponse<IntegrationView>> mapTeacher(@PathVariable UUID integrationId,
			@PathVariable UUID teacherUserId, @Valid @RequestBody MapTeacherRequest request) {
		return ResponseEntity.ok(ApiResponse.success(service.mapTeacher(integrationId, teacherUserId,
				request.externalUserId())));
	}

	@DeleteMapping("/{integrationId}/teacher-mappings/{teacherUserId}")
	public ResponseEntity<ApiResponse<Void>> revokeMapping(@PathVariable UUID integrationId,
			@PathVariable UUID teacherUserId) {
		service.revokeMapping(integrationId, teacherUserId);
		return ResponseEntity.ok(ApiResponse.success(null));
	}

}
