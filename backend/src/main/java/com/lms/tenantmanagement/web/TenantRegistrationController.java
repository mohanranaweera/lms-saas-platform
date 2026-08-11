package com.lms.tenantmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.tenantmanagement.service.TenantRegistrationCommand;
import com.lms.tenantmanagement.service.TenantRegistrationResult;
import com.lms.tenantmanagement.service.TenantRegistrationService;
import com.lms.tenantmanagement.web.dto.TenantRegistrationRequest;
import com.lms.tenantmanagement.web.dto.TenantRegistrationResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public, anonymous tenant self-registration endpoint ({@code TEN-1}). This
 * is the platform's one legitimate public/anonymous endpoint - see
 * {@code SecurityConfig}'s single added {@code permitAll()} rule for it.
 * Stays thin: delegates to {@link TenantRegistrationService}.
 */
@RestController
@RequestMapping("/api/v1/tenant-registrations")
public class TenantRegistrationController {

	private final TenantRegistrationService tenantRegistrationService;

	public TenantRegistrationController(TenantRegistrationService tenantRegistrationService) {
		this.tenantRegistrationService = tenantRegistrationService;
	}

	@PostMapping
	public ResponseEntity<ApiResponse<TenantRegistrationResponse>> register(
			@Valid @RequestBody TenantRegistrationRequest request) {
		TenantRegistrationCommand command = new TenantRegistrationCommand(request.name(), request.subdomain(),
				request.requestedPlan(), request.contactName(), request.contactEmail(), request.contactPhone());
		TenantRegistrationResult result = tenantRegistrationService.register(command);
		TenantRegistrationResponse response = new TenantRegistrationResponse(result.id(), result.subdomain(),
				result.status().toColumnValue());
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
	}

}
