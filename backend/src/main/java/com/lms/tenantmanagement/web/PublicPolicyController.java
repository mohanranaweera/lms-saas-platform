package com.lms.tenantmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.tenantmanagement.api.ConfigDomain;
import com.lms.tenantmanagement.api.TenantConfigApi;
import com.lms.tenantmanagement.web.dto.PublicPolicyResponse;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Anonymous read of one of the host tenant's published policies (LOGIN-01
 * Phase B): Terms of Service, Refund Policy, Privacy Policy. The tenant is the
 * one resolved from the request host by the shared tenant-resolution filter;
 * this endpoint accepts no tenant id and exposes only these three public,
 * plain-text {@code GENERAL} keys. An unset policy returns {@code text: null}
 * (the page says it isn't published yet); an unknown kind is {@code 404}.
 */
@RestController
@RequestMapping("/api/v1/public/tenant-config/policies")
public class PublicPolicyController {

	/** URL kind -> {@code GENERAL} key. */
	private static final Map<String, String> KEYS_BY_KIND = Map.of("terms", "terms_of_service", "refund",
			"refund_policy", "privacy", "privacy_policy");

	private final TenantConfigApi tenantConfigApi;

	private final TenantContext tenantContext;

	public PublicPolicyController(TenantConfigApi tenantConfigApi, TenantContext tenantContext) {
		this.tenantConfigApi = tenantConfigApi;
		this.tenantContext = tenantContext;
	}

	@GetMapping("/{kind}")
	public ResponseEntity<ApiResponse<PublicPolicyResponse>> getPolicy(@PathVariable String kind) {
		String key = KEYS_BY_KIND.get(kind);
		if (key == null) {
			throw new NotFoundException("Policy not found");
		}
		String text = tenantConfigApi.resolveValue(tenantContext.getTenantId(), ConfigDomain.GENERAL, key)
			.filter(String.class::isInstance)
			.map(String.class::cast)
			.filter(value -> !value.isBlank())
			.orElse(null);
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noCache())
			.body(ApiResponse.success(new PublicPolicyResponse(kind, text)));
	}

}
