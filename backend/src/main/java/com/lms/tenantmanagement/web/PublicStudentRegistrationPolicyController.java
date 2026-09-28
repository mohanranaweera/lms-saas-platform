package com.lms.tenantmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.common.tenant.TenantContext;
import com.lms.tenantmanagement.api.ConfigDomain;
import com.lms.tenantmanagement.api.StudentProfileField;
import com.lms.tenantmanagement.api.StudentProfileFieldPolicy;
import com.lms.tenantmanagement.api.TenantConfigApi;
import com.lms.tenantmanagement.service.StudentProfileFieldConfigService;
import com.lms.tenantmanagement.web.dto.PublicRegistrationFieldResponse;
import com.lms.tenantmanagement.web.dto.PublicStudentRegistrationPolicyResponse;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Second deliberately unauthenticated tenant-configuration read (Wave 3
 * gap-fill), modeled directly on {@link PublicBrandingController} - no
 * {@code @PreAuthorize} here, and this route is on {@code
 * SecurityFilterChainConfig}'s {@code permitAll()} list. The public,
 * unauthenticated student self-registration page ({@code
 * StudentRegistrationController}'s frontend counterpart) must know, before a
 * prospective student fills out anything, whether registration is open for
 * this tenant and which fields/steps to render - that is display/form-shape
 * information, not a privileged settings read, so like {@code
 * PublicBrandingController} it deliberately bypasses {@code
 * TenantConfigController}'s domain-level ({@code BRANDING_SETTINGS})
 * permission gate rather than reusing that endpoint.
 *
 * <p>Tenant identity still comes exclusively from {@code
 * TenantResolutionFilter}'s subdomain resolution - this controller is NOT
 * excluded from that filter, so {@link TenantContext} is already populated
 * for this request before this method runs, from the {@code Host} header,
 * never from a client-supplied tenant id/query param.
 *
 * <p>Only the three registration-flow {@link ConfigDomain#STUDENT}
 * properties, the five legacy per-field "required" booleans and the list of
 * fields asked at registration are ever returned (see {@link
 * PublicStudentRegistrationPolicyResponse}) - never the full generic {@code
 * tenant-config} property list or the tenant's full field configuration.
 *
 * <p>STU-PROF-01: the legacy {@code require*} booleans are now derived from
 * the effective student profile field settings (required AND asked at
 * registration), so they stay consistent with {@code registrationFields}
 * after a tenant saves its field settings.
 */
@RestController
@RequestMapping("/api/v1/public/tenant-config/student-registration-policy")
public class PublicStudentRegistrationPolicyController {

	/** Mirrors {@code ConfigPropertyRegistry#studentProperties()}'s registered defaults. */
	private static final boolean DEFAULT_PUBLIC_REGISTRATION_ENABLED = true;

	private static final boolean DEFAULT_APPROVAL_REQUIRED = false;

	private static final boolean DEFAULT_OTP_REQUIRED = false;

	private final TenantConfigApi tenantConfigApi;

	private final StudentProfileFieldConfigService fieldConfigService;

	private final TenantContext tenantContext;

	public PublicStudentRegistrationPolicyController(TenantConfigApi tenantConfigApi,
			StudentProfileFieldConfigService fieldConfigService, TenantContext tenantContext) {
		this.tenantConfigApi = tenantConfigApi;
		this.fieldConfigService = fieldConfigService;
		this.tenantContext = tenantContext;
	}

	@GetMapping
	public ResponseEntity<ApiResponse<PublicStudentRegistrationPolicyResponse>> getStudentRegistrationPolicy() {
		UUID tenantId = tenantContext.getTenantId();
		Map<String, Object> student = tenantConfigApi.resolveDomain(tenantId, ConfigDomain.STUDENT);
		List<StudentProfileFieldPolicy> policies = fieldConfigService.getFieldPolicies();
		Map<StudentProfileField, StudentProfileFieldPolicy> byField = new EnumMap<>(StudentProfileField.class);
		policies.forEach(policy -> byField.put(policy.field(), policy));

		List<PublicRegistrationFieldResponse> registrationFields = policies.stream()
			.filter(policy -> !policy.field().locked() && policy.collectAtRegistration())
			.map(policy -> new PublicRegistrationFieldResponse(policy.field().name(), policy.field().label(),
					policy.field().group().name(), policy.field().type().name(), policy.field().maxLength(),
					policy.required()))
			.toList();

		PublicStudentRegistrationPolicyResponse response = new PublicStudentRegistrationPolicyResponse(
				booleanOrDefault(student.get("public_registration_enabled"), DEFAULT_PUBLIC_REGISTRATION_ENABLED),
				booleanOrDefault(student.get("approval_required"), DEFAULT_APPROVAL_REQUIRED),
				booleanOrDefault(student.get("otp_required"), DEFAULT_OTP_REQUIRED),
				requiredAtRegistration(byField, StudentProfileField.GUARDIAN),
				requiredAtRegistration(byField, StudentProfileField.SCHOOL),
				requiredAtRegistration(byField, StudentProfileField.GRADE),
				requiredAtRegistration(byField, StudentProfileField.AL_STREAM),
				requiredAtRegistration(byField, StudentProfileField.MOBILE), registrationFields);
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	private static boolean requiredAtRegistration(Map<StudentProfileField, StudentProfileFieldPolicy> byField,
			StudentProfileField field) {
		StudentProfileFieldPolicy policy = byField.get(field);
		return policy != null && policy.required() && policy.collectAtRegistration();
	}

	private static boolean booleanOrDefault(Object value, boolean fallback) {
		return (value instanceof Boolean b) ? b : fallback;
	}

}
