package com.lms.paymentmanagement.manualpayment.service;

import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.paymentmanagement.manualpayment.service.ManualPaymentViews.ReceiptLine;
import com.lms.paymentmanagement.order.domain.StudentOrderLine;
import com.lms.tenantmanagement.api.ConfigDomain;
import com.lms.tenantmanagement.api.TenantConfigApi;
import com.lms.usermanagement.api.StudentLookupApi;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Shared, read-only helpers of the ADR-030 manual payment services: resolving
 * the URL's student_profile id into the student's {@code tenant_user.id}
 * (tenant-scoped, 404 otherwise - never trusting a client-supplied user id),
 * the tenant timezone and line mapping.
 */
@Component
class ManualPaymentSupport {

	private final StudentLookupApi studentLookupApi;

	private final TenantConfigApi tenantConfigApi;

	private final TenantContext tenantContext;

	private final CourseLookupApi courseLookupApi;

	ManualPaymentSupport(StudentLookupApi studentLookupApi, TenantConfigApi tenantConfigApi,
			TenantContext tenantContext, CourseLookupApi courseLookupApi) {
		this.studentLookupApi = studentLookupApi;
		this.tenantConfigApi = tenantConfigApi;
		this.tenantContext = tenantContext;
		this.courseLookupApi = courseLookupApi;
	}

	/** student_profile id (current tenant) -> tenant_user id; 404 "Student not found" otherwise. */
	UUID requireStudentUserId(UUID studentProfileId) {
		if (studentProfileId == null) {
			throw new NotFoundException("Student not found");
		}
		return studentLookupApi.resolveUserId(studentProfileId)
			.orElseThrow(() -> new NotFoundException("Student not found"));
	}

	/** The tenant's GENERAL {@code default_timezone}, UTC when absent or invalid. */
	ZoneId tenantZone() {
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
		return ZoneId.of("UTC");
	}

	Map<UUID, String> courseTitles(Collection<UUID> courseIds) {
		Map<UUID, String> titles = new HashMap<>();
		if (!courseIds.isEmpty()) {
			courseLookupApi.getCourseSummaries(new LinkedHashSet<>(courseIds)).forEach(summary -> titles.put(summary.id(), summary.name()));
		}
		return titles;
	}

	List<ReceiptLine> toLines(List<StudentOrderLine> lines, Map<UUID, String> titles) {
		return lines.stream()
			.map(line -> new ReceiptLine(line.getLineNo(), line.getCourseId(), titles.get(line.getCourseId()),
					line.getScope(), line.getLabel(), line.getPeriodYear(), line.getPeriodMonth(),
					line.getLineTotal()))
			.toList();
	}

	List<ReceiptLine> toLines(List<StudentOrderLine> lines) {
		Set<UUID> courseIds = new LinkedHashSet<>();
		lines.forEach(line -> courseIds.add(line.getCourseId()));
		return toLines(lines, courseTitles(courseIds));
	}

}
