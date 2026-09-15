package com.lms.auditlogmanagement.repository;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lms.auditlogmanagement.AuditLogManagementTestSupport;
import com.lms.identityaccessservice.domain.PlatformAdminUser;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.tenantmanagement.domain.Tenant;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Post-review regression test (Critical finding): {@code
 * V33__restore_audit_log_actor_integrity_trigger.sql}'s {@code
 * trg_audit_log_actor_must_exist} trigger must reject an {@code audit_log}
 * write whose {@code actor_id} does not resolve to a known {@code
 * tenant_user} or {@code platform_admin_user} row, REGARDLESS of which Java
 * code path performs the write.
 *
 * <p>Every test here deliberately bypasses {@code AuditLogService} entirely -
 * using a raw {@code jdbcTemplate.update(...)} insert/update, exactly like
 * {@code AuditLogManagementTestSupport#seedAuditLogRow}'s own seeding
 * technique - so these tests prove the DATABASE itself rejects an invalid
 * actor, not merely that {@code AuditLogService#requireKnownActor} would
 * have caught it. That distinction is the entire point of this fix: a
 * service-layer-only guard is bypassable by any insert/update path that
 * doesn't go through {@code AuditLogService} (a future bug, a different
 * service calling {@code EntityManager.persist} directly, a data-fix
 * script); a trigger is not.
 */
class AuditLogActorIntegrityTriggerIntegrationTest extends AuditLogManagementTestSupport {

	@Test
	void insertingAnAuditLogRowWithANonexistentActorIdIsRejectedByTheDatabaseItself() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("audit-trigger-bad-actor"));
		UUID nonexistentActorId = UUID.randomUUID();

		assertThatThrownBy(() -> insertAuditLogRowRaw(tenant.getId(), nonexistentActorId))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void insertingAnAuditLogRowWithARealTenantUserActorIdSucceeds() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("audit-trigger-good-tenant-user"));
		TenantUser admin = seedTenantUser(tenant.getId(), "trigger-actor@example.test", RAW_PASSWORD,
				Role.TENANT_ADMIN);

		assertThatCode(() -> insertAuditLogRowRaw(tenant.getId(), admin.getId())).doesNotThrowAnyException();
	}

	@Test
	void insertingAnAuditLogRowWithARealPlatformAdminUserActorIdSucceeds() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("audit-trigger-good-platform-admin"));
		PlatformAdminUser admin = seedPlatformAdmin("audit-trigger-platform-admin@platform.test", RAW_PASSWORD);

		// Mirrors AuditLogService#recordForTenant's real shape: tenant_id is
		// the TARGET tenant, actor_id is the Platform Admin's own id (never
		// present in tenant_user for any tenant).
		assertThatCode(() -> insertAuditLogRowRaw(tenant.getId(), admin.getId())).doesNotThrowAnyException();
	}

	/**
	 * V34 regression test: the original composite FK this trigger replaced
	 * ({@code fk_audit_log_actor FOREIGN KEY (tenant_id, actor_id) REFERENCES
	 * tenant_user (tenant_id, id)}, dropped in V32) enforced not just "actor
	 * exists" but "actor belongs to this row's own tenant". V33's first cut
	 * of this trigger checked only existence, silently accepting a {@code
	 * tenant_user} actor recorded against a DIFFERENT tenant's row - V34
	 * closes that gap. This test proves the database itself, not just
	 * {@code AuditLogService}, rejects that mismatch.
	 */
	@Test
	void insertingAnAuditLogRowWithATenantUserActorBelongingToADifferentTenantIsRejectedByTheDatabaseItself() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("audit-trigger-cross-tenant-a"));
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("audit-trigger-cross-tenant-b"));
		TenantUser adminOfTenantA = seedTenantUser(tenantA.getId(), "trigger-cross-tenant-actor@example.test",
				RAW_PASSWORD, Role.TENANT_ADMIN);

		assertThatThrownBy(() -> insertAuditLogRowRaw(tenantB.getId(), adminOfTenantA.getId()))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void updatingAnExistingRowsActorIdToATenantUserActorBelongingToADifferentTenantIsRejectedByTheDatabaseItself() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("audit-trigger-cross-tenant-update-a"));
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("audit-trigger-cross-tenant-update-b"));
		TenantUser adminOfTenantB = seedTenantUser(tenantB.getId(), "trigger-cross-tenant-update-actor@example.test",
				RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser adminOfTenantA = seedTenantUser(tenantA.getId(), "trigger-cross-tenant-update-actor-a@example.test",
				RAW_PASSWORD, Role.TENANT_ADMIN);
		UUID rowId = seedAuditLogRow(tenantA.getId(), adminOfTenantA.getId(), "course.price_changed", "course",
				UUID.randomUUID(), Instant.now());

		assertThatThrownBy(() -> jdbcTemplate.update("UPDATE audit_log SET actor_id = ? WHERE id = ?",
				adminOfTenantB.getId(), rowId)).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void updatingAnExistingRowsActorIdToANonexistentActorIsRejectedByTheDatabaseItself() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("audit-trigger-update-bad-actor"));
		TenantUser admin = seedTenantUser(tenant.getId(), "trigger-update-actor@example.test", RAW_PASSWORD,
				Role.TENANT_ADMIN);
		UUID rowId = seedAuditLogRow(tenant.getId(), admin.getId(), "course.price_changed", "course",
				UUID.randomUUID(), Instant.now());
		UUID nonexistentActorId = UUID.randomUUID();

		assertThatThrownBy(() -> jdbcTemplate.update("UPDATE audit_log SET actor_id = ? WHERE id = ?",
				nonexistentActorId, rowId)).isInstanceOf(DataIntegrityViolationException.class);
	}

	/**
	 * Raw JDBC insert, deliberately independent of {@code
	 * AuditLogService}/{@code AuditLogRepository} - this is the "bypasses
	 * AuditLogService" insert path this test class exists to exercise.
	 * Mirrors {@code AuditLogManagementTestSupport#seedAuditLogRow}'s exact
	 * SQL shape.
	 */
	private void insertAuditLogRowRaw(UUID tenantId, UUID actorId) {
		jdbcTemplate.update(
				"INSERT INTO audit_log (id, tenant_id, actor_id, action, target_entity, target_id, reason, "
						+ "metadata, occurred_at) VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, ?)",
				UUID.randomUUID(), tenantId, actorId, "course.price_changed", "course", UUID.randomUUID(),
				Timestamp.from(Instant.now()));
	}

}
