package com.lms.auditlogmanagement.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

/**
 * Plain reflection-only unit test (no Spring context, no Testcontainers) that
 * pins down the exact regression this fix closes: {@code
 * AuditLogRepository#findByTenantIdAcrossTenantsForPlatformReport}'s {@code
 * tenant_id} predicate must be a real, sargable equality
 * ({@code a.tenant_id = CAST(:tenantId AS uuid)}), never the
 * self-referencing {@code a.tenant_id = COALESCE(CAST(:tenantId AS uuid),
 * a.tenant_id)} form the original, unsplit {@code
 * findAllAcrossTenantsForPlatformReport} used - a form PostgreSQL cannot
 * push down as an index condition against any {@code tenant_id}-leading
 * index (confirmed via {@code EXPLAIN} against a real Testcontainers
 * Postgres run during this fix), which meant the single-tenant drill-down
 * (the platform-admin per-tenant audit view,
 * {@code GET /platform-admin/audit-log/tenants/{tenantId}}) silently
 * degraded to the same platform-wide scan cost as the genuinely unfiltered
 * dashboard view.
 *
 * <p>This test guards the SQL text itself (not just end-to-end behavior,
 * which {@code PlatformAdminAuditLogControllerIntegrationTest
 * #platformAuditLogDrillDownFiltersToOneTenantWithoutLeakingOtherTenantsRows}
 * already covers) so a future edit that reintroduces the non-sargable
 * {@code COALESCE} form for {@code tenant_id} - while still returning
 * functionally-correct results - fails immediately, without needing a
 * Postgres {@code EXPLAIN} run in CI.
 */
class AuditLogPlatformReportQuerySargabilityTest {

	@Test
	void tenantScopedDrillDownQueryUsesASargableEqualityPredicateForTenantId() throws Exception {
		Method method = AuditLogRepository.class.getMethod("findByTenantIdAcrossTenantsForPlatformReport",
				java.util.UUID.class, java.time.Instant.class, java.time.Instant.class, String.class,
				org.springframework.data.domain.Pageable.class);
		Query query = method.getAnnotation(Query.class);

		assertThat(query).as("findByTenantIdAcrossTenantsForPlatformReport must be a native @Query method").isNotNull();
		assertThat(query.value()).as("tenant_id must be filtered with a real, sargable equality predicate - "
				+ "never a self-referencing COALESCE, which Postgres cannot push down as an index condition")
			.contains("a.tenant_id = CAST(:tenantId AS uuid)")
			.doesNotContain("COALESCE(CAST(:tenantId AS uuid), a.tenant_id)");
		assertThat(query.countQuery())
			.contains("a.tenant_id = CAST(:tenantId AS uuid)")
			.doesNotContain("COALESCE(CAST(:tenantId AS uuid), a.tenant_id)");
	}

	@Test
	void platformWideQueryCarriesNoTenantIdPredicateAtAll() throws Exception {
		Method method = AuditLogRepository.class.getMethod("findAllAcrossTenantsForPlatformReport",
				java.time.Instant.class, java.time.Instant.class, String.class,
				org.springframework.data.domain.Pageable.class);
		Query query = method.getAnnotation(Query.class);

		assertThat(query).as("findAllAcrossTenantsForPlatformReport must be a native @Query method").isNotNull();
		assertThat(query.value()).as("the platform-wide scan must carry no tenant_id predicate at all, "
				+ "so it relies purely on the occurred_at-leading index added for this exact access pattern")
			.doesNotContain("tenant_id");
		assertThat(query.countQuery()).doesNotContain("tenant_id");
	}

}
