package com.lms.financeexpensemanagement.service;

import com.lms.common.api.FieldError;
import com.lms.common.error.FieldValidationException;
import com.lms.common.tenant.TenantContext;
import com.lms.tenantmanagement.api.ConfigDomain;
import com.lms.tenantmanagement.api.TenantConfigApi;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Wave 7 §10 judgment call 5 - finance date ranges are calendar dates in the
 * tenant's own {@code GENERAL.default_timezone} (default {@code UTC}), so a
 * ledger entry recorded at 00:30 local time on the 1st of a month lands in
 * that month, not the previous one.
 */
@Component
public class FinancePeriodResolver {

	/** Upper bound on a single report range, to keep per-request ledger reads bounded. */
	static final long MAX_RANGE_DAYS = 731;

	private final TenantConfigApi tenantConfigApi;

	private final TenantContext tenantContext;

	public FinancePeriodResolver(TenantConfigApi tenantConfigApi, TenantContext tenantContext) {
		this.tenantConfigApi = tenantConfigApi;
		this.tenantContext = tenantContext;
	}

	public ZoneId tenantZone() {
		Object configured = tenantConfigApi
			.resolveValue(tenantContext.getTenantId(), ConfigDomain.GENERAL, "default_timezone")
			.orElse(null);
		if (configured instanceof String zoneName) {
			try {
				return ZoneId.of(zoneName);
			}
			catch (DateTimeException ignored) {
				// Registry validation should make this unreachable; fall back
				// to UTC rather than failing a read-only report.
			}
		}
		return ZoneId.of("UTC");
	}

	public LocalDate today() {
		return LocalDate.now(tenantZone());
	}

	/**
	 * Resolves an optional {@code [from, to]} (inclusive) date range: both
	 * omitted = the current calendar month; validated {@code from <= to} and
	 * {@link #MAX_RANGE_DAYS}.
	 */
	public DateRange resolve(LocalDate from, LocalDate to) {
		LocalDate today = today();
		LocalDate resolvedFrom = (from != null) ? from : (to != null ? to.withDayOfMonth(1) : today.withDayOfMonth(1));
		LocalDate resolvedTo = (to != null) ? to : (from != null ? today : today.withDayOfMonth(today.lengthOfMonth()));
		if (resolvedFrom.isAfter(resolvedTo)) {
			throw new FieldValidationException("Invalid date range",
					List.of(new FieldError("from", "must be on or before 'to'")));
		}
		if (ChronoUnit.DAYS.between(resolvedFrom, resolvedTo) > MAX_RANGE_DAYS) {
			throw new FieldValidationException("Invalid date range",
					List.of(new FieldError("to", "range must not exceed " + MAX_RANGE_DAYS + " days")));
		}
		ZoneId zone = tenantZone();
		return new DateRange(resolvedFrom, resolvedTo, resolvedFrom.atStartOfDay(zone).toInstant(),
				resolvedTo.plusDays(1).atStartOfDay(zone).toInstant(), zone);
	}

	/**
	 * @param startInclusive instant of {@code from} 00:00 in the tenant zone.
	 * @param endExclusive instant of the day after {@code to}, 00:00 in the tenant zone.
	 */
	public record DateRange(LocalDate from, LocalDate to, Instant startInclusive, Instant endExclusive, ZoneId zone) {

	}

}
