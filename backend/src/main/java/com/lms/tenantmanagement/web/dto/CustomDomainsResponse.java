package com.lms.tenantmanagement.web.dto;

import com.lms.tenantmanagement.service.CustomDomainService.CustomDomainsOverview;
import java.util.List;

/**
 * TEN-DOM-01: a tenant's custom domains. {@code enabled} is the Platform Admin
 * switch for the tenant; {@code available} says whether the platform supports
 * custom domains at all (both are needed to add or verify).
 */
public record CustomDomainsResponse(boolean enabled, boolean available, int maxDomains,
		List<CustomDomainResponse> domains) {

	public static CustomDomainsResponse from(CustomDomainsOverview overview) {
		return new CustomDomainsResponse(overview.enabled(), overview.available(), overview.maxDomains(),
				overview.domains().stream().map(CustomDomainResponse::from).toList());
	}

}
