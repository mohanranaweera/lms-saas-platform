package com.lms.attendancemanagement.repository;

import com.lms.common.persistence.CrossTenantPersistenceException;
import com.lms.common.tenant.TenantContextHolder;
import java.util.UUID;

/**
 * Defense-in-depth guard shared by every explicit-{@code tenantId} native/JPQL
 * query in this package ({@link AttendanceRecordRepository}, {@link
 * AttendanceSheetRepository}, {@link AttendanceSummaryRepository}). Those
 * queries are not structurally tenant-filtered by {@code
 * TenantAwareRepositoryImpl}, so this asserts the passed {@code tenantId}
 * equals {@link TenantContextHolder}'s own resolved value before the query
 * runs - throwing {@link CrossTenantPersistenceException}, the idiom {@code
 * TenantAwareRepositoryImpl#assertOwnedByCurrentTenant} already uses.
 */
final class AttendanceTenantAssertions {

	private AttendanceTenantAssertions() {
	}

	static void assertTenantIdMatchesContext(UUID tenantId, String table) {
		UUID currentTenantId = new TenantContextHolder().getTenantId();
		if (!currentTenantId.equals(tenantId)) {
			throw new CrossTenantPersistenceException(
					"Attempted to query " + table + " using a tenantId that does not match the current tenant context");
		}
	}

}
