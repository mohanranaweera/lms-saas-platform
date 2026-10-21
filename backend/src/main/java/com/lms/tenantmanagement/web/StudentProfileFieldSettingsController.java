package com.lms.tenantmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.tenantmanagement.api.StudentProfileFieldPolicy;
import com.lms.tenantmanagement.service.StudentProfileFieldConfigService;
import com.lms.tenantmanagement.service.StudentProfileFieldSettingChange;
import com.lms.tenantmanagement.web.dto.StudentProfileFieldSettingResponse;
import com.lms.tenantmanagement.web.dto.StudentProfileFieldSettingsResponse;
import com.lms.tenantmanagement.web.dto.StudentProfileFieldSettingsUpdateRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant Admin student profile field settings (STU-PROF-01): which catalogue
 * fields a tenant's students are asked for, which are required, and which
 * appear on the public registration form. Same {@code BRANDING_SETTINGS}
 * gate as {@link TenantConfigController} (Tenant Admin: view/edit; Read-only
 * Auditor: view). The literal {@code /student-profile-fields} segment takes
 * precedence over {@code TenantConfigController}'s {@code /{domain}}
 * pattern. Stays thin - all logic in {@link StudentProfileFieldConfigService}.
 */
@RestController
@RequestMapping("/api/v1/tenant-config/student-profile-fields")
public class StudentProfileFieldSettingsController {

	private final StudentProfileFieldConfigService service;

	public StudentProfileFieldSettingsController(StudentProfileFieldConfigService service) {
		this.service = service;
	}

	@GetMapping
	@PreAuthorize("@permissionCheckService.hasPermission('BRANDING_SETTINGS', 'VIEW')")
	public ResponseEntity<ApiResponse<StudentProfileFieldSettingsResponse>> getSettings() {
		return ResponseEntity.ok(ApiResponse.success(toResponse(service.getSettings())));
	}

	@PutMapping
	@PreAuthorize("@permissionCheckService.hasPermission('BRANDING_SETTINGS', 'CREATE_EDIT')")
	public ResponseEntity<ApiResponse<StudentProfileFieldSettingsResponse>> updateSettings(
			@Valid @RequestBody StudentProfileFieldSettingsUpdateRequest request) {
		List<StudentProfileFieldSettingChange> changes = request.fields()
			.stream()
			.map(item -> new StudentProfileFieldSettingChange(item.key(), item.enabled(), item.required(),
					item.collectAtRegistration()))
			.toList();
		return ResponseEntity.ok(ApiResponse.success(toResponse(service.updateFieldPolicies(changes))));
	}

	private static StudentProfileFieldSettingsResponse toResponse(List<StudentProfileFieldPolicy> policies) {
		return new StudentProfileFieldSettingsResponse(policies.stream()
			.map(policy -> new StudentProfileFieldSettingResponse(policy.field().name(), policy.field().label(),
					policy.field().group().name(), policy.field().type().name(), policy.field().maxLength(),
					policy.field().sensitive(), policy.field().locked(), policy.enabled(), policy.required(),
					policy.collectAtRegistration()))
			.toList());
	}

}
