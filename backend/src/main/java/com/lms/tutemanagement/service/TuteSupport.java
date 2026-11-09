package com.lms.tutemanagement.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.tenant.TenantContext;
import com.lms.coursemanagement.api.CourseCatalogInfo;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.tenantmanagement.api.ConfigDomain;
import com.lms.tenantmanagement.api.TenantConfigApi;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Small shared reads for the tute services: tenant timezone (creation month), course currency
 * and the audit write (same transaction as the change it records).
 */
@Component
class TuteSupport {

	/** Spec section 5 default when the tenant has no (valid) {@code GENERAL.default_timezone}. */
	static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Colombo");

	private final TenantConfigApi tenantConfigApi;

	private final CourseLookupApi courseLookupApi;

	private final TenantContext tenantContext;

	private final AuditLogApi auditLogApi;

	TuteSupport(TenantConfigApi tenantConfigApi, CourseLookupApi courseLookupApi, TenantContext tenantContext,
			AuditLogApi auditLogApi) {
		this.tenantConfigApi = tenantConfigApi;
		this.courseLookupApi = courseLookupApi;
		this.tenantContext = tenantContext;
		this.auditLogApi = auditLogApi;
	}

	ZoneId tenantZone() {
		Object configured = tenantConfigApi
			.resolveValue(tenantContext.getTenantId(), ConfigDomain.GENERAL, "default_timezone")
			.orElse(null);
		if (configured instanceof String zone && !zone.isBlank()) {
			try {
				return ZoneId.of(zone);
			}
			catch (DateTimeException ignored) {
				// Registry validation makes this unreachable; fall back to the spec default.
			}
		}
		return DEFAULT_ZONE;
	}

	YearMonth monthOf(Instant instant) {
		return YearMonth.from(instant.atZone(tenantZone()));
	}

	/** The course's currency (tenant default currency for course-level prices). */
	String courseCurrency(UUID courseId) {
		return courseLookupApi.findCatalogInfo(courseId)
			.map(CourseCatalogInfo::currency)
			.filter(currency -> currency != null && !currency.isBlank())
			.orElseGet(() -> tenantConfigApi.resolveDefaultCurrency(tenantContext.getTenantId()));
	}

	UUID tenantId() {
		return tenantContext.getTenantId();
	}

	void audit(UUID actorId, String action, String targetEntity, UUID targetId, String reason,
			Map<String, Object> metadata) {
		auditLogApi.record(new AuditLogEntry(actorId, action, targetEntity, targetId, reason, metadata));
	}

}
