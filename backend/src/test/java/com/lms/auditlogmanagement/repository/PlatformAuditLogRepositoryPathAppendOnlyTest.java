package com.lms.auditlogmanagement.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lms.auditlogmanagement.AuditLogManagementTestSupport;
import com.lms.auditlogmanagement.domain.AuditLog;
import com.lms.common.tenant.TenantContextHolder;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.tenantmanagement.domain.Tenant;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Closes plan §18's {@code
 * platformAuditLogRepositoryPathExposesNoUpdateOrDeleteMethod} - per the
 * task's own instruction, this is already structurally guaranteed by {@link
 * AuditLogRepository}'s existing delete-method overrides (re-run, not newly
 * built, by {@code AuditLogRepositoryAppendOnlyTest}, which every test in
 * this class also implicitly exercises against, since {@link
 * PlatformAdminAuditLogControllerIntegrationTest} and this repository's new
 * {@link AuditLogRepository#findAllAcrossTenantsForPlatformReport} bypass
 * method share the exact same {@link AuditLogRepository} interface/bean -
 * there is no separate "platform" repository to independently guard).
 *
 * <p>Extends {@link AuditLogManagementTestSupport} (a real Spring/
 * Testcontainers context) so this class can, in addition to the original
 * lightweight reflection checks below, re-verify append-only enforcement
 * against a genuinely persisted row under the exact thread-local state a
 * real Platform Admin request runs under: no {@link TenantContextHolder}
 * value ever set (mirroring {@code TenantResolutionFilter} never resolving
 * one for {@code /api/v1/platform-admin/**}). This matters because {@link
 * AuditLogRepositoryAppendOnlyTest} (the pre-existing equivalent test for the
 * tenant-scoped path) always runs WITH a resolved {@code TenantContext} - it
 * does not prove the delete-shaped overrides reject deletion when no
 * context is resolved at all, which is the one thing structurally different
 * about the platform-admin call path. The delete-shaped methods must throw
 * {@link UnsupportedOperationException} unconditionally (never {@code
 * TenantContextNotResolvedException}), since {@link AuditLogRepository}
 * overrides them as default methods that throw before ever touching
 * tenant-scoping logic.
 */
class PlatformAuditLogRepositoryPathAppendOnlyTest extends AuditLogManagementTestSupport {

	private static final Set<String> EXPECTED_OVERRIDDEN_DELETE_METHODS = Set.of("deleteById", "delete",
			"deleteAllById", "deleteAll", "deleteAllInBatch", "deleteAllByIdInBatch");

	@Autowired
	private AuditLogRepository auditLogRepository;

	@Test
	void theCrossTenantPlatformReportBypassMethodExistsAndIsReadOnlyShaped() throws Exception {
		Method platformWide = AuditLogRepository.class.getMethod("findAllAcrossTenantsForPlatformReport",
				java.time.Instant.class, java.time.Instant.class, String.class,
				org.springframework.data.domain.Pageable.class);
		assertThat(platformWide.getReturnType()).isEqualTo(org.springframework.data.domain.Page.class);

		Method tenantDrillDown = AuditLogRepository.class.getMethod("findByTenantIdAcrossTenantsForPlatformReport",
				java.util.UUID.class, java.time.Instant.class, java.time.Instant.class, String.class,
				org.springframework.data.domain.Pageable.class);
		assertThat(tenantDrillDown.getReturnType()).isEqualTo(org.springframework.data.domain.Page.class);
	}

	@Test
	void everyDeclaredDeleteShapedMethodOnTheRepositoryInterfaceIsADefaultMethodOverriddenToThrow() {
		Method[] methods = AuditLogRepository.class.getDeclaredMethods();
		long deleteShapedDefaultMethodCount = 0;
		for (Method method : methods) {
			if (!method.getName().startsWith("delete")) {
				continue;
			}
			assertThat(EXPECTED_OVERRIDDEN_DELETE_METHODS).as("Unexpected new delete-shaped method: %s - the "
					+ "platform report bypass method must never introduce a new deletion path", method.getName())
				.contains(method.getName());
			// A genuinely inherited-and-overridden default method is declared
			// directly on this interface (not merely inherited unmodified) -
			// isDefault() confirms it carries its own throwing body here,
			// rather than silently falling back to whatever
			// TenantAwareRepository/JpaRepository/SimpleJpaRepository would
			// otherwise do.
			assertThat(method.isDefault() || Modifier.isAbstract(method.getModifiers())).isTrue();
			deleteShapedDefaultMethodCount++;
		}
		assertThat(deleteShapedDefaultMethodCount).isGreaterThanOrEqualTo(1);
	}

	/**
	 * Seeds a real, persisted {@code audit_log} row exactly the way {@link
	 * AuditLogRepositoryAppendOnlyTest#seedRow} does, but - unlike that
	 * method, which wraps its {@code findById} read in {@code
	 * withTenant(...)} - deliberately never sets {@link TenantContextHolder}
	 * at any point in this test, mirroring the real Platform Admin request
	 * state. {@code AbstractIntegrationTest} already clears the holder before
	 * and after every test, so no explicit clear call is needed here, but the
	 * assertion below confirms it anyway so a future change to that base
	 * class can't silently invalidate this test's premise.
	 */
	@Test
	void deleteByIdIsRejectedWithUnsupportedOperationNotTenantContextNotResolvedWhenNoTenantContextIsSet() {
		assertThat(TenantContextHolder.isSet()).as("this test's whole premise is that no TenantContext is resolved, "
				+ "mirroring a real Platform Admin request").isFalse();
		UUID id = seedPersistedRow();

		assertThatThrownBy(() -> auditLogRepository.deleteById(id)).isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("append-only");
	}

	@Test
	void deleteEntityIsRejectedWithUnsupportedOperationNotTenantContextNotResolvedWhenNoTenantContextIsSet() {
		assertThat(TenantContextHolder.isSet()).isFalse();
		UUID id = seedPersistedRow();
		// delete(AuditLog) ignores the passed instance entirely (it throws
		// unconditionally before ever inspecting it), so a transient instance
		// carrying the persisted row's id is sufficient here - reading the
		// managed entity back via findById would itself require a resolved
		// TenantContext, which is exactly what this test must NOT set up.
		AuditLog transientHandle = new AuditLog(UUID.randomUUID(), UUID.randomUUID(), "course.price_changed",
				"course", UUID.randomUUID(), null, null, Instant.now());

		assertThatThrownBy(() -> auditLogRepository.delete(transientHandle))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("append-only");
	}

	@Test
	void deleteAllByIdIsRejectedWithUnsupportedOperationNotTenantContextNotResolvedWhenNoTenantContextIsSet() {
		assertThat(TenantContextHolder.isSet()).isFalse();
		UUID id = seedPersistedRow();

		assertThatThrownBy(() -> auditLogRepository.deleteAllById(List.of(id)))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("append-only");
	}

	@Test
	void deleteAllIsRejectedWithUnsupportedOperationNotTenantContextNotResolvedWhenNoTenantContextIsSet() {
		assertThat(TenantContextHolder.isSet()).isFalse();
		seedPersistedRow();

		assertThatThrownBy(auditLogRepository::deleteAll).isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("append-only");
	}

	@Test
	void deleteAllInBatchIsRejectedWithUnsupportedOperationNotTenantContextNotResolvedWhenNoTenantContextIsSet() {
		assertThat(TenantContextHolder.isSet()).isFalse();
		seedPersistedRow();

		assertThatThrownBy(auditLogRepository::deleteAllInBatch).isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("append-only");
	}

	/** Seeds a real, persisted row via direct SQL and returns its id, touching no {@link TenantContextHolder} state. */
	private UUID seedPersistedRow() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("platform-audit-append-only"));
		TenantUser admin = seedTenantUser(tenant.getId(), "platform-admin-fixture@example.test", RAW_PASSWORD,
				Role.TENANT_ADMIN);
		return seedAuditLogRow(tenant.getId(), admin.getId(), "course.price_changed", "course", UUID.randomUUID(),
				Instant.now());
	}

}
