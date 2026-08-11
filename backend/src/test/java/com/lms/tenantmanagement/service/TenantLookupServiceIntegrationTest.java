package com.lms.tenantmanagement.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.common.AbstractIntegrationTest;
import com.lms.tenantmanagement.api.TenantResolution;
import com.lms.tenantmanagement.api.TenantStatus;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Testcontainers-backed, service-level coverage of {@code TEN-3}'s
 * {@code tenant-management}-owned half:
 * {@link TenantLookupService#resolveBySubdomain(String)}. No HTTP
 * filter/controller exists for subdomain resolution yet (see that class's
 * own doc comment and plan SS9/SS20) - this proves the lookup contract
 * itself against a real Postgres.
 *
 * <p>{@code tenant} is platform-level, not tenant-owned (it does not
 * implement {@code TenantOwned}), so {@code TenantContextHolder}/{@code
 * withTenant(...)} is not relevant to seeding or reading it - both rows are
 * seeded directly via {@link JdbcTemplate}, matching {@code
 * V2__create_tenant_table.sql}'s exact column list. Uses the
 * {@code TENANT_A}/{@code TENANT_B} two-tenant seeding convention from
 * {@code TenantAwareRepositoryFixtureIntegrationTest} (docs/architecture/
 * multi-tenancy.md SS3) as the two seeded tenants' own {@code id} values,
 * since this table's PK is exactly what {@code resolveBySubdomain} resolves.
 *
 * <p>{@code @Transactional}: rolls back seeded rows after each test, exactly
 * like the fixture test - the lookup call happens in-process on the same
 * thread as the test (no HTTP hop), so it safely joins the same rolled-back
 * transaction.
 */
@Transactional
class TenantLookupServiceIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private TenantLookupService tenantLookupService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void resolvesEachSeededTenantByItsOwnSubdomainAgainstRealDb() {
		String subdomainA = uniqueSubdomain("look-a");
		String subdomainB = uniqueSubdomain("look-b");
		seedTenant(TENANT_A, subdomainA, TenantStatus.TRIAL);
		seedTenant(TENANT_B, subdomainB, TenantStatus.ACTIVE);

		Optional<TenantResolution> resolvedA = tenantLookupService.resolveBySubdomain(subdomainA);
		Optional<TenantResolution> resolvedB = tenantLookupService.resolveBySubdomain(subdomainB);

		assertThat(resolvedA).isPresent();
		assertThat(resolvedA.get().tenantId()).isEqualTo(TENANT_A);
		assertThat(resolvedA.get().subdomain()).isEqualTo(subdomainA);
		assertThat(resolvedA.get().status()).isEqualTo(TenantStatus.TRIAL);

		assertThat(resolvedB).isPresent();
		assertThat(resolvedB.get().tenantId()).isEqualTo(TENANT_B);
		assertThat(resolvedB.get().subdomain()).isEqualTo(subdomainB);
		assertThat(resolvedB.get().status()).isEqualTo(TenantStatus.ACTIVE);
	}

	@Test
	void resolvingAnUnregisteredSubdomainReturnsEmptyWithNoFallbackToAnotherTenant() {
		// A registered tenant exists in the DB at the same time, so an empty
		// result here can only mean "not found" - never an accidental match
		// against the wrong row / a default-tenant fallback.
		seedTenant(TENANT_A, uniqueSubdomain("look-existing"), TenantStatus.ACTIVE);

		Optional<TenantResolution> resolved = tenantLookupService.resolveBySubdomain(uniqueSubdomain("nonexistent"));

		assertThat(resolved).isEmpty();
	}

	private void seedTenant(UUID id, String subdomain, TenantStatus status) {
		jdbcTemplate.update(
				"INSERT INTO tenant (id, name, subdomain, status, requested_plan, contact_name, contact_email, "
						+ "contact_phone, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), now())",
				id, "Institute " + subdomain, subdomain, status.toColumnValue(), "starter", "Contact Person",
				"contact-" + subdomain + "@example.test", "+1-555-0100");
	}

	private static String uniqueSubdomain(String prefix) {
		String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		return prefix + "-" + suffix;
	}

}
