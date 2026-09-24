package com.lms.tenantmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.common.tenant.TenantContext;
import com.lms.tenantmanagement.api.ConfigDomain;
import com.lms.tenantmanagement.api.TenantConfigApi;
import com.lms.tenantmanagement.web.dto.PublicStudentRegistrationPolicyResponse;
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
 * <p>Only the eight registration-relevant {@link ConfigDomain#STUDENT}
 * properties are ever returned (see {@link PublicStudentRegistrationPolicyResponse}) -
 * never the full generic {@code tenant-config} property-list mechanism.
 */
@RestController
@RequestMapping("/api/v1/public/tenant-config/student-registration-policy")
public class PublicStudentRegistrationPolicyController {

	/** Mirrors {@code ConfigPropertyRegistry#studentProperties()}'s registered defaults. */
	private static final boolean DEFAULT_PUBLIC_REGISTRATION_ENABLED = true;

	private static final boolean DEFAULT_APPROVAL_REQUIRED = false;

	private static final boolean DEFAULT_OTP_REQUIRED = false;

	private static final boolean DEFAULT_REQUIRE_GUARDIAN_INFO = false;

	private static final boolean DEFAULT_REQUIRE_SCHOOL = false;

	private static final boolean DEFAULT_REQUIRE_GRADE = false;

	private static final boolean DEFAULT_REQUIRE_STREAM = false;

	private static final boolean DEFAULT_REQUIRE_MOBILE = false;

	private final TenantConfigApi tenantConfigApi;

	private final TenantContext tenantContext;

	public PublicStudentRegistrationPolicyController(TenantConfigApi tenantConfigApi, TenantContext tenantContext) {
		this.tenantConfigApi = tenantConfigApi;
		this.tenantContext = tenantContext;
	}

	@GetMapping
	public ResponseEntity<ApiResponse<PublicStudentRegistrationPolicyResponse>> getStudentRegistrationPolicy() {
		UUID tenantId = tenantContext.getTenantId();
		Map<String, Object> student = tenantConfigApi.resolveDomain(tenantId, ConfigDomain.STUDENT);
		PublicStudentRegistrationPolicyResponse response = new PublicStudentRegistrationPolicyResponse(
				booleanOrDefault(student.get("public_registration_enabled"), DEFAULT_PUBLIC_REGISTRATION_ENABLED),
				booleanOrDefault(student.get("approval_required"), DEFAULT_APPROVAL_REQUIRED),
				booleanOrDefault(student.get("otp_required"), DEFAULT_OTP_REQUIRED),
				booleanOrDefault(student.get("require_guardian_info"), DEFAULT_REQUIRE_GUARDIAN_INFO),
				booleanOrDefault(student.get("require_school"), DEFAULT_REQUIRE_SCHOOL),
				booleanOrDefault(student.get("require_grade"), DEFAULT_REQUIRE_GRADE),
				booleanOrDefault(student.get("require_stream"), DEFAULT_REQUIRE_STREAM),
				booleanOrDefault(student.get("require_mobile"), DEFAULT_REQUIRE_MOBILE));
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	private static boolean booleanOrDefault(Object value, boolean fallback) {
		return (value instanceof Boolean b) ? b : fallback;
	}

}
