package com.lms.reportinganalytics.service.support;

import com.lms.tenantmanagement.api.ConfigDomain;
import com.lms.tenantmanagement.api.TenantConfigApi;
import com.lms.tenantmanagement.api.TenantLookupApi;
import com.lms.tenantmanagement.api.TenantSummary;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Shared by RPT-01 and RPT-02: the tenant facts every report header needs -
 * timezone ({@code GENERAL.default_timezone}, UTC fallback), institute name
 * and default currency. Always called with the tenant already resolved from
 * the trusted {@code TenantContext}.
 */
@Component
public class ReportTenantInfo {

	private final TenantConfigApi tenantConfigApi;

	private final TenantLookupApi tenantLookupApi;

	public ReportTenantInfo(TenantConfigApi tenantConfigApi, TenantLookupApi tenantLookupApi) {
		this.tenantConfigApi = tenantConfigApi;
		this.tenantLookupApi = tenantLookupApi;
	}

	public ZoneId zone(UUID tenantId) {
		Object configured = tenantConfigApi.resolveValue(tenantId, ConfigDomain.GENERAL, "default_timezone")
			.orElse(null);
		if (configured instanceof String zoneName) {
			try {
				return ZoneId.of(zoneName);
			}
			catch (DateTimeException ignored) {
				// Registry-validated; UTC fallback is defensive only.
			}
		}
		return ZoneId.of("UTC");
	}

	public String instituteName(UUID tenantId) {
		return tenantLookupApi.resolveTenantSummaries(Set.of(tenantId))
			.stream()
			.findFirst()
			.map(TenantSummary::name)
			.orElse("");
	}

	public String defaultCurrency(UUID tenantId) {
		return tenantConfigApi.resolveDefaultCurrency(tenantId);
	}

}
