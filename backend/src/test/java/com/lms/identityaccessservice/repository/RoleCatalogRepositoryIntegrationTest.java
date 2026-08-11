package com.lms.identityaccessservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lms.common.AbstractIntegrationTest;
import com.lms.common.tenant.TenantContextHolder;
import com.lms.identityaccessservice.domain.RoleCatalogEntry;
import com.lms.identityaccessservice.domain.RoleCatalogEntry.RoleScope;
import com.lms.tenantmanagement.api.TenantStatus;
import com.lms.tenantmanagement.domain.Tenant;
import com.lms.tenantmanagement.repository.TenantRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Testcontainers-backed coverage for RBAC-1's role catalog (V7) and its FK
 * repoint of {@code tenant_user.role} (V8). No authentication needed for
 * these - they exercise persistence/schema behavior directly, not the
 * request/filter-chain path (see {@code PermissionEnforcementIntegrationTest}
 * for the RBAC-2 filter-chain coverage).
 */
class RoleCatalogRepositoryIntegrationTest extends AbstractIntegrationTest {

	private static final List<String> EXPECTED_CODES = List.of("PLATFORM_ADMIN", "TENANT_ADMIN", "FINANCE_STAFF",
			"COURSE_COORDINATOR", "STUDENT_SUPPORT", "CONTENT_MANAGER", "EXAM_MANAGER", "ATTENDANCE_OPERATOR",
			"READ_ONLY_AUDITOR", "TEACHER", "TEACHER_ASSISTANT", "STUDENT");

	@Autowired
	private RoleCatalogRepository roleCatalogRepository;

	@Autowired
	private TenantRepository tenantRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void v7SeedsExactlyTheTwelveExpectedCatalogRowsWithCorrectScope() {
		List<RoleCatalogEntry> all = roleCatalogRepository.findAll();

		assertThat(all).hasSize(12);
		assertThat(all).extracting(RoleCatalogEntry::getCode).containsExactlyInAnyOrderElementsOf(EXPECTED_CODES);

		RoleCatalogEntry platformAdmin = findByCode(all, "PLATFORM_ADMIN");
		assertThat(platformAdmin.getScope()).isEqualTo(RoleScope.PLATFORM);

		List<RoleCatalogEntry> everyOtherRow = all.stream().filter(row -> !"PLATFORM_ADMIN".equals(row.getCode())).toList();
		assertThat(everyOtherRow).hasSize(11);
		assertThat(everyOtherRow).allSatisfy(row -> assertThat(row.getScope()).isEqualTo(RoleScope.TENANT));
	}

	@Test
	void teacherAssistantIsTheOnlyRowFlaggedProvisional() {
		// The structural PROVISIONAL marker RBAC-1 requires: the role VALUE
		// exists (assignable), but its permission boundary is not ratified.
		// This must never silently spread to any other row.
		List<RoleCatalogEntry> all = roleCatalogRepository.findAll();

		RoleCatalogEntry teacherAssistant = findByCode(all, "TEACHER_ASSISTANT");
		assertThat(teacherAssistant.isProvisional()).isTrue();

		List<RoleCatalogEntry> everyOtherRow = all.stream().filter(row -> !"TEACHER_ASSISTANT".equals(row.getCode())).toList();
		assertThat(everyOtherRow).hasSize(11);
		assertThat(everyOtherRow).allSatisfy(row -> assertThat(row.isProvisional()).isFalse());
	}

	@Test
	void findByScopeAndIsActiveTrueReturnsOnlyTheElevenTenantAssignableActiveRolesInSortOrder() {
		List<RoleCatalogEntry> tenantRoles = roleCatalogRepository.findByScopeAndIsActiveTrueOrderBySortOrderAsc(RoleScope.TENANT);

		assertThat(tenantRoles).hasSize(11);
		assertThat(tenantRoles).extracting(RoleCatalogEntry::getCode).doesNotContain("PLATFORM_ADMIN");
		assertThat(tenantRoles).allSatisfy(row -> {
			assertThat(row.getScope()).isEqualTo(RoleScope.TENANT);
			assertThat(row.isActive()).isTrue();
		});
		List<Integer> sortOrders = tenantRoles.stream().map(RoleCatalogEntry::getSortOrder).toList();
		assertThat(sortOrders).isSorted();
	}

	@Test
	void insertingATenantUserWithTheOldPreCatalogStaffRoleValueIsRejectedByTheDatabaseForeignKey() {
		// V8 dropped the old CHECK(role IN ('TENANT_ADMIN','STAFF','TEACHER','STUDENT'))
		// constraint and replaced it with a FK to role(code). 'STAFF' is not a
		// catalog code - this proves DB-level enforcement, not merely
		// application-level Role.valueOf.
		Tenant tenant = seedTenant("FK Reject Institute");

		assertThatThrownBy(() -> insertTenantUser(tenant.getId(), "fk-reject-staff@example.test", "STAFF"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void insertingATenantUserWithAnyNonCatalogRoleStringIsRejectedByTheDatabaseForeignKey() {
		Tenant tenant = seedTenant("FK Reject Institute 2");

		assertThatThrownBy(() -> insertTenantUser(tenant.getId(), "fk-reject-bogus@example.test", "NOT_A_REAL_ROLE_CODE"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void insertingATenantUserWithPlatformAdminRoleIsRejectedByTheDatabaseCheckConstraint() {
		// V9's specific gap-closing case: 'PLATFORM_ADMIN' IS a valid role(code)
		// row (see v7SeedsExactlyTheTwelveExpectedCatalogRowsWithCorrectScope
		// above), so V8's plain FK alone would accept it here - only V9's
		// chk_tenant_user_role_not_platform_admin CHECK constraint rejects it.
		Tenant tenant = seedTenant("FK Reject Institute 3");

		assertThatThrownBy(() -> insertTenantUser(tenant.getId(), "fk-reject-platform-admin@example.test", "PLATFORM_ADMIN"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void insertingATenantUserWithAValidCatalogRoleCodeSucceeds() {
		Tenant tenant = seedTenant("FK Accept Institute");

		int rowsInserted = insertTenantUser(tenant.getId(), "fk-accept-finance@example.test", "FINANCE_STAFF");

		assertThat(rowsInserted).isEqualTo(1);
	}

	@Test
	void roleCatalogRepositoryIsNotTenantScopedAndReturnsGlobalRowsRegardlessOfTenantContextState() {
		// Sanity check, not a tenant-isolation test: RoleCatalogRepository is a
		// plain JpaRepository (the `role` table holds zero tenant-owned rows -
		// see its own javadoc), so its result must be identical no matter what
		// TenantContextHolder currently holds.
		List<RoleCatalogEntry> withNoTenantContext = roleCatalogRepository.findAll();

		TenantContextHolder.set(TENANT_A);
		List<RoleCatalogEntry> withTenantAContext;
		try {
			withTenantAContext = roleCatalogRepository.findAll();
		}
		finally {
			TenantContextHolder.clear();
		}

		TenantContextHolder.set(TENANT_B);
		List<RoleCatalogEntry> withTenantBContext;
		try {
			withTenantBContext = roleCatalogRepository.findAll();
		}
		finally {
			TenantContextHolder.clear();
		}

		List<String> expectedCodes = withNoTenantContext.stream().map(RoleCatalogEntry::getCode).toList();
		assertThat(withTenantAContext).hasSize(withNoTenantContext.size());
		assertThat(withTenantAContext).extracting(RoleCatalogEntry::getCode).containsExactlyInAnyOrderElementsOf(expectedCodes);
		assertThat(withTenantBContext).hasSize(withNoTenantContext.size());
		assertThat(withTenantBContext).extracting(RoleCatalogEntry::getCode).containsExactlyInAnyOrderElementsOf(expectedCodes);
	}

	/**
	 * {@code Tenant} exposes no public constructor (see its own javadoc) -
	 * these FK/constraint tests only need any persisted, valid tenant row to
	 * satisfy {@code tenant_user.tenant_id}'s FK, so seed one directly via
	 * SQL rather than the registration/approval workflow, matching the
	 * pattern already established in
	 * {@code TenantLookupServiceIntegrationTest#seedTenant}.
	 */
	private Tenant seedTenant(String name) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update(
				"INSERT INTO tenant (id, name, subdomain, status, requested_plan, contact_name, contact_email, "
						+ "contact_phone, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), now())",
				id, name, uniqueSubdomain(), TenantStatus.ACTIVE.toColumnValue(), "starter", "Test Contact",
				"contact-" + id + "@example.test", "+1-555-0100");
		return tenantRepository.findById(id).orElseThrow();
	}

	private int insertTenantUser(UUID tenantId, String email, String roleCode) {
		return jdbcTemplate.update(
				"INSERT INTO tenant_user (id, tenant_id, email, password_hash, role, status, must_change_password) "
						+ "VALUES (?, ?, ?, ?, ?, 'active', false)",
				UUID.randomUUID(), tenantId, email, "irrelevant-hash-value", roleCode);
	}

	private static RoleCatalogEntry findByCode(List<RoleCatalogEntry> rows, String code) {
		return rows.stream()
			.filter(row -> code.equals(row.getCode()))
			.findFirst()
			.orElseThrow(() -> new AssertionError("Expected catalog row '" + code + "' not found"));
	}

	private static String uniqueSubdomain() {
		return "role-catalog-test-" + UUID.randomUUID().toString().substring(0, 8);
	}

}
