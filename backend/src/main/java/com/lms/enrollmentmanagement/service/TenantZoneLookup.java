package com.lms.enrollmentmanagement.service;

import com.lms.common.tenant.TenantContext;
import com.lms.tenantmanagement.api.ConfigDomain;
import com.lms.tenantmanagement.api.TenantConfigApi;
import java.time.DateTimeException;
import java.time.ZoneId;
import org.springframework.stereotype.Component;

/**
 * The current tenant's configured {@code GENERAL.default_timezone}, UTC when unset
 * or invalid (ADR-028 §4) - the zone a calendar month is evaluated in.
 */
@Component
class TenantZoneLookup {

	private static final ZoneId FALLBACK = ZoneId.of("UTC");

	private final TenantConfigApi tenantConfigApi;

	private final TenantContext tenantContext;

	TenantZoneLookup(TenantConfigApi tenantConfigApi, TenantContext tenantContext) {
		this.tenantConfigApi = tenantConfigApi;
		this.tenantContext = tenantContext;
	}

	ZoneId currentTenantZone() {
		try {
			Object configured = tenantConfigApi
				.resolveValue(tenantContext.getTenantId(), ConfigDomain.GENERAL, "default_timezone")
				.orElse(null);
			if (configured instanceof String zone && !zone.isBlank()) {
				return ZoneId.of(zone);
			}
		}
		catch (DateTimeException | IllegalArgumentException ex) {
			// fall through to UTC
		}
		return FALLBACK;
	}

}
