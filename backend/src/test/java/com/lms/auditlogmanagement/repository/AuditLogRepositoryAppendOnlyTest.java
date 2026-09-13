package com.lms.auditlogmanagement.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lms.auditlogmanagement.AuditLogManagementTestSupport;
import com.lms.auditlogmanagement.domain.AuditLog;
import com.lms.common.tenant.TenantContextHolder;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.tenantmanagement.domain.Tenant;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Proves all eight delete-shaped methods {@link AuditLogRepository} overrides
 * from {@code TenantAwareRepository}/{@code JpaRepository} - {@code
 * deleteById}, {@code delete}, {@code deleteAllById}, {@code
 * deleteAll(Iterable)}, {@code deleteAll()}, {@code deleteAllInBatch()},
 * {@code deleteAllInBatch(Iterable)}, {@code deleteAllByIdInBatch(Iterable)}
 * - throw {@link UnsupportedOperationException} rather than silently
 * deleting an append-only audit row, per that interface's own javadoc,
 * {@code .claude/rules/backend.md}'s append-only enforcement guidance, and
 * root {@code CLAUDE.md}'s "never delete financial history" rule (audit logs
 * are the same category of durable, non-deletable history). Mirrors {@code
 * CoursePriceHistoryRepositoryAppendOnlyTest}'s exact technique/coverage
 * shape for the analogous append-only repository in {@code
 * course-management}, but reuses this module's own {@link
 * AuditLogManagementTestSupport} seeding helpers rather than hand-rolling
 * raw SQL, since {@code seedActiveTenant}/{@code seedTenantUser}/{@code
 * seedAuditLogRow} already exist for this exact table.
 */
class AuditLogRepositoryAppendOnlyTest extends AuditLogManagementTestSupport {

	@Autowired
	private AuditLogRepository auditLogRepository;

	@Test
	void deleteByIdIsRejected() {
		AuditLog row = seedRow();
		assertThatThrownBy(() -> auditLogRepository.deleteById(row.getId())).isInstanceOf(
				UnsupportedOperationException.class).hasMessageContaining("append-only");
	}

	@Test
	void deleteEntityIsRejected() {
		AuditLog row = seedRow();
		assertThatThrownBy(() -> auditLogRepository.delete(row)).isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("append-only");
	}

	@Test
	void deleteAllByIdIsRejected() {
		AuditLog row = seedRow();
		assertThatThrownBy(() -> auditLogRepository.deleteAllById(List.of(row.getId()))).isInstanceOf(
				UnsupportedOperationException.class).hasMessageContaining("append-only");
	}

	@Test
	void deleteAllIterableIsRejected() {
		AuditLog row = seedRow();
		assertThatThrownBy(() -> auditLogRepository.deleteAll(List.of(row))).isInstanceOf(
				UnsupportedOperationException.class).hasMessageContaining("append-only");
	}

	@Test
	void deleteAllIsRejected() {
		seedRow();
		assertThatThrownBy(() -> auditLogRepository.deleteAll()).isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("append-only");
	}

	@Test
	void deleteAllInBatchNoArgsIsRejected() {
		seedRow();
		assertThatThrownBy(() -> auditLogRepository.deleteAllInBatch()).isInstanceOf(
				UnsupportedOperationException.class).hasMessageContaining("append-only");
	}

	@Test
	void deleteAllInBatchIterableIsRejected() {
		AuditLog row = seedRow();
		assertThatThrownBy(() -> auditLogRepository.deleteAllInBatch(List.of(row))).isInstanceOf(
				UnsupportedOperationException.class).hasMessageContaining("append-only");
	}

	@Test
	void deleteAllByIdInBatchIsRejected() {
		AuditLog row = seedRow();
		assertThatThrownBy(() -> auditLogRepository.deleteAllByIdInBatch(List.of(row.getId()))).isInstanceOf(
				UnsupportedOperationException.class).hasMessageContaining("append-only");
	}

	/**
	 * Seeds a real, persisted {@code audit_log} row (via {@code
	 * seedAuditLogRow}, independent of {@code AuditLogService#record}) and
	 * fetches it back through the tenant-scoped repository, so every
	 * delete-shaped method above is proven to reject deletion of an actual
	 * managed entity - not merely a nonexistent id, which would be a weaker
	 * proof.
	 */
	private AuditLog seedRow() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("audit-append-only"));
		TenantUser admin = seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		UUID id = seedAuditLogRow(tenant.getId(), admin.getId(), "course.price_changed", "course", UUID.randomUUID(),
				Instant.now());

		TenantContextHolder.set(tenant.getId());
		try {
			return auditLogRepository.findById(id).orElseThrow();
		}
		finally {
			TenantContextHolder.clear();
		}
	}

}
