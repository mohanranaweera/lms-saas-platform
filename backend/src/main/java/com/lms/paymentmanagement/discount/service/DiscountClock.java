package com.lms.paymentmanagement.discount.service;

import com.lms.common.tenant.TenantContext;
import com.lms.tenantmanagement.api.ConfigDomain;
import com.lms.tenantmanagement.api.TenantConfigApi;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import org.springframework.stereotype.Component;

/**
 * Tenant-local "today" / "current month" for discounts (D1, D2, D10): the
 * tenant's {@code GENERAL.default_timezone}, UTC when unset or invalid - the
 * same resolution as the finance module, read through {@link TenantConfigApi}.
 * Also the single source of "now" so tests can subclass/mock it.
 */
@Component
public class DiscountClock {

	private static final ZoneId UTC = ZoneId.of("UTC");

	private final TenantConfigApi tenantConfigApi;

	private final TenantContext tenantContext;

	public DiscountClock(TenantConfigApi tenantConfigApi, TenantContext tenantContext) {
		this.tenantConfigApi = tenantConfigApi;
		this.tenantContext = tenantContext;
	}

	public Instant now() {
		return Instant.now();
	}

	public ZoneId tenantZone() {
		Object configured = tenantConfigApi
			.resolveValue(tenantContext.getTenantId(), ConfigDomain.GENERAL, "default_timezone")
			.orElse(null);
		if (configured instanceof String zoneName && !zoneName.isBlank()) {
			try {
				return ZoneId.of(zoneName);
			}
			catch (DateTimeException ignored) {
				// Registry validation should make this unreachable; fall back to UTC.
			}
		}
		return UTC;
	}

	public LocalDate today() {
		return LocalDate.ofInstant(now(), tenantZone());
	}

	public YearMonth currentMonth() {
		return YearMonth.from(today());
	}

	/** The tenant default currency - the currency of every rule value (D13). */
	public String tenantCurrency() {
		return tenantConfigApi.resolveDefaultCurrency(tenantContext.getTenantId());
	}

}
