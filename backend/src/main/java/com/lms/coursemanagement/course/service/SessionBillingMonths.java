package com.lms.coursemanagement.course.service;

import com.lms.tenantmanagement.api.ConfigDomain;
import com.lms.tenantmanagement.api.TenantConfigApi;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * The one rule for a Monthly course's session billing month: the calendar
 * month of the session start in the tenant's {@code default_timezone} (UTC
 * when unset or invalid). Used when a session is saved and when a course
 * switches to Monthly, so both paths always agree.
 */
final class SessionBillingMonths {

	static final int MIN_YEAR = 2000;

	static final int MAX_YEAR = 2100;

	private SessionBillingMonths() {
	}

	static ZoneId tenantZone(TenantConfigApi tenantConfigApi, UUID tenantId) {
		try {
			Object configured = tenantConfigApi.resolveValue(tenantId, ConfigDomain.GENERAL, "default_timezone")
				.orElse(null);
			if (configured instanceof String name) {
				return ZoneId.of(name);
			}
		}
		catch (DateTimeException | IllegalArgumentException ex) {
			// Match the course-content timezone fallback for invalid legacy configuration.
		}
		return ZoneId.of("UTC");
	}

	/** Empty when the start is outside the supported 2000-2100 range. */
	static Optional<YearMonth> of(Instant startsAt, ZoneId zone) {
		ZonedDateTime localStart = startsAt.atZone(zone);
		if (localStart.getYear() < MIN_YEAR || localStart.getYear() > MAX_YEAR) {
			return Optional.empty();
		}
		return Optional.of(YearMonth.of(localStart.getYear(), localStart.getMonthValue()));
	}

}
