package com.lms.auditlogmanagement.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.common.AbstractIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Post-review recommended improvement (database-architect): {@code
 * AuditLogPlatformReportQuerySargabilityTest} proves the two platform-report
 * queries' SQL TEXT is sargable (a real equality predicate, never a
 * self-referencing {@code COALESCE}), but a string-match assertion cannot
 * prove PostgreSQL's planner can actually satisfy the query via an index
 * scan - a type mismatch, unexpected cast, or wrong column order could
 * produce sargable-looking text that still forces a sequential scan. This
 * test runs a real {@code EXPLAIN} against a real Testcontainers Postgres
 * instance for both queries and asserts an Index Scan is available.
 *
 * <p>{@code SET LOCAL enable_seqscan = off} is used deliberately: at this
 * project's current (near-empty, pre-launch) row counts, PostgreSQL's cost
 * planner would legitimately prefer a sequential scan over an index scan
 * regardless of index quality (a seq scan on a handful of rows is
 * genuinely cheaper), which would make a plain, unforced {@code EXPLAIN}
 * assertion pass or fail depending on row count rather than on whether the
 * query is actually sargable - the property this test exists to check.
 * Disabling seq scans for the plan check answers the right question ("CAN
 * the planner use this index for this predicate shape at all") without
 * depending on synthetic data volume.
 */
class AuditLogPlatformReportQueryExplainIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	@Transactional
	void platformWideQueryPlanUsesTheOccurredAtLeadingIndexWhenSeqScanIsDisallowed() {
		jdbcTemplate.execute("SET LOCAL enable_seqscan = off");

		List<String> plan = explain("SELECT * FROM audit_log a WHERE "
				+ "a.occurred_at >= COALESCE(CAST(NULL AS timestamptz), a.occurred_at) "
				+ "AND a.occurred_at <= COALESCE(CAST(NULL AS timestamptz), a.occurred_at) "
				+ "AND a.action = COALESCE(CAST(NULL AS varchar), a.action) ORDER BY a.occurred_at DESC");

		String planText = String.join("\n", plan);
		assertThat(planText).as("platform-wide scan must be able to use an Index Scan, not force a Seq Scan - full "
				+ "plan:%n%s", planText).contains("Index Scan").contains("idx_audit_log_occurred_at_tenant");
	}

	@Test
	@Transactional
	void tenantScopedDrillDownQueryPlanUsesTheTenantLeadingIndexWhenSeqScanIsDisallowed() {
		jdbcTemplate.execute("SET LOCAL enable_seqscan = off");
		UUID tenantId = UUID.randomUUID();

		List<String> plan = explain("SELECT * FROM audit_log a WHERE a.tenant_id = CAST('" + tenantId + "' AS uuid) "
				+ "AND a.occurred_at >= COALESCE(CAST(NULL AS timestamptz), a.occurred_at) "
				+ "AND a.occurred_at <= COALESCE(CAST(NULL AS timestamptz), a.occurred_at) "
				+ "AND a.action = COALESCE(CAST(NULL AS varchar), a.action) ORDER BY a.occurred_at DESC");

		String planText = String.join("\n", plan);
		assertThat(planText).as("tenant-scoped drill-down must be able to use an Index Scan on the tenant_id-leading "
				+ "index, not force a Seq Scan - full plan:%n%s", planText)
			.contains("Index Scan")
			.contains("idx_audit_log_tenant_occurred_at");
	}

	private List<String> explain(String sql) {
		return jdbcTemplate.query("EXPLAIN " + sql, (rs, rowNum) -> rs.getString(1));
	}

}
