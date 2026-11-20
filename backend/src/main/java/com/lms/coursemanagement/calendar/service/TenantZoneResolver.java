package com.lms.coursemanagement.calendar.service;

import com.lms.tenantmanagement.api.ConfigDomain;
import com.lms.tenantmanagement.api.TenantConfigApi;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The calendar's single time zone (CAL-01 spec §3): the tenant's {@code
 * GENERAL.default_timezone} (IANA id), {@code UTC} when unset or invalid -
 * never the server's or the browser's zone.
 */
@Component
public class TenantZoneResolver {

	private static final Logger log = LoggerFactory.getLogger(TenantZoneResolver.class);

	private static final ZoneId UTC = ZoneId.of("UTC");

	private final TenantConfigApi tenantConfigApi;

	public TenantZoneResolver(TenantConfigApi tenantConfigApi) {
		this.tenantConfigApi = tenantConfigApi;
	}

	public ZoneId zoneOf(UUID tenantId) {
		try {
			Object configured = tenantConfigApi.resolveValue(tenantId, ConfigDomain.GENERAL, "default_timezone")
				.orElse(null);
			if (configured instanceof String zoneName && !zoneName.isBlank()) {
				return ZoneId.of(zoneName.strip());
			}
		}
		catch (DateTimeException | IllegalArgumentException ex) {
			log.warn("Invalid tenant default_timezone; using UTC", ex);
		}
		return UTC;
	}

}
