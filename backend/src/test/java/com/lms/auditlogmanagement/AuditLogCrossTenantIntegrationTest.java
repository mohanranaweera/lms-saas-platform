package com.lms.auditlogmanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.auditlogmanagement.web.dto.AuditLogEntryResponse;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.tenantmanagement.domain.Tenant;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Mandatory per plan §14/§18 and root {@code CLAUDE.md}'s cross-tenant test
 * requirement, mirroring {@code ExamCrossTenantIntegrationTest}'s exact
 * structure. {@code audit-log-management} has no single-resource-by-id read
 * endpoint (only the tenant-scoped list/search), so - like {@code
 * ExamCrossTenantIntegrationTest}'s "colliding names never leak in a
 * tenant-wide staff list" tests - this proves tenant A's list/search
 * response never contains any row belonging to tenant B, including when a
 * filter is deliberately crafted to match one of tenant B's real rows.
 */
class AuditLogCrossTenantIntegrationTest extends AuditLogManagementTestSupport {

	@Test
	void tenantAsUnfilteredSearchNeverReturnsAnyOfTenantBsRows() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("audit-xt-a"));
		TenantUser adminA = seedTenantUser(tenantA.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("audit-xt-b"));
		TenantUser adminB = seedTenantUser(tenantB.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String hostA = hostFor(tenantA.getSubdomain());
		String hostB = hostFor(tenantB.getSubdomain());
		String tokenA = loginAndGetToken(hostA, "admin@example.test");
		String tokenB = loginAndGetToken(hostB, "admin@example.test");

		Instant now = Instant.now();
		UUID rowA = seedAuditLogRow(tenantA.getId(), adminA.getId(), "course.price_changed", "course",
				UUID.randomUUID(), now.minusSeconds(5));
		UUID rowB1 = seedAuditLogRow(tenantB.getId(), adminB.getId(), "course.price_changed", "course",
				UUID.randomUUID(), now.minusSeconds(4));
		UUID rowB2 = seedAuditLogRow(tenantB.getId(), adminB.getId(), "material.deleted", "material",
				UUID.randomUUID(), now.minusSeconds(3));
		UUID rowB3 = seedAuditLogRow(tenantB.getId(), adminB.getId(), "payment.refunded", "payment_refund",
				UUID.randomUUID(), now.minusSeconds(2));

		var tenantAUnfiltered = auditLogSearch(hostA, tokenA);
		assertThat(tenantAUnfiltered.getStatusCode()).isEqualTo(HttpStatus.OK);
		var unfilteredContent = tenantAUnfiltered.getBody().data().content();
		assertThat(unfilteredContent).extracting(AuditLogEntryResponse::id).containsExactly(rowA);
		assertThat(unfilteredContent).extracting(AuditLogEntryResponse::id).doesNotContain(rowB1, rowB2, rowB3);
		assertThat(tenantAUnfiltered.getBody().data().totalElements()).isEqualTo(1);

		// Sanity: tenant B can see its own 3 rows - proves the above emptiness
		// for tenant A is real isolation, not a broken query for everyone.
		var tenantBUnfiltered = auditLogSearch(hostB, tokenB);
		assertThat(tenantBUnfiltered.getBody().data().totalElements()).isEqualTo(3);
	}

	@Test
	void tenantAsSearchCraftedToMatchTenantBsRowsByActionStillReturnsNothingFromTenantB() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("audit-xt-action-a"));
		TenantUser adminA = seedTenantUser(tenantA.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("audit-xt-action-b"));
		TenantUser adminB = seedTenantUser(tenantB.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String hostA = hostFor(tenantA.getSubdomain());
		String tokenA = loginAndGetToken(hostA, "admin@example.test");

		Instant now = Instant.now();
		// Tenant A has NO row with this action - only tenant B does.
		UUID rowB = seedAuditLogRow(tenantB.getId(), adminB.getId(), "material.deleted", "material",
				UUID.randomUUID(), now.minusSeconds(5));
		seedAuditLogRow(tenantA.getId(), adminA.getId(), "course.price_changed", "course", UUID.randomUUID(),
				now.minusSeconds(4));

		var result = auditLogSearch(hostA, tokenA, "action=material.deleted");

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		var body = result.getBody().data();
		assertThat(body.content()).isEmpty();
		assertThat(body.totalElements()).isEqualTo(0);
		assertThat(body.content()).extracting(AuditLogEntryResponse::id).doesNotContain(rowB);
	}

	@Test
	void tenantAsSearchCraftedToMatchTenantBsRowsByTargetEntityAndDateRangeStillReturnsNothingFromTenantB() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("audit-xt-date-a"));
		TenantUser adminA = seedTenantUser(tenantA.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("audit-xt-date-b"));
		TenantUser adminB = seedTenantUser(tenantB.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String hostA = hostFor(tenantA.getSubdomain());
		String tokenA = loginAndGetToken(hostA, "admin@example.test");

		Instant sharedInstant = Instant.now().minusSeconds(30);
		UUID rowB = seedAuditLogRow(tenantB.getId(), adminB.getId(), "payment.refunded", "payment_refund",
				UUID.randomUUID(), sharedInstant);

		String from = sharedInstant.minusSeconds(60).toString();
		String to = sharedInstant.plusSeconds(60).toString();
		var result = auditLogSearch(hostA, tokenA,
				"targetEntity=payment_refund&from=" + from + "&to=" + to);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		var body = result.getBody().data();
		assertThat(body.content()).isEmpty();
		assertThat(body.totalElements()).isEqualTo(0);
		assertThat(body.content()).extracting(AuditLogEntryResponse::id).doesNotContain(rowB);
	}

	@Test
	void collidingActionAndTargetIdAcrossTwoTenantsNeverLeaksInTenantAsSearch() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("audit-xt-collide-a"));
		TenantUser adminA = seedTenantUser(tenantA.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("audit-xt-collide-b"));
		TenantUser adminB = seedTenantUser(tenantB.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String hostA = hostFor(tenantA.getSubdomain());
		String tokenA = loginAndGetToken(hostA, "admin@example.test");

		// Same action name AND the exact same target_id value used for both
		// tenants' rows - proves tenant scoping, not target_id uniqueness,
		// is what prevents the leak.
		UUID collidingTargetId = UUID.randomUUID();
		Instant now = Instant.now();
		UUID rowA = seedAuditLogRow(tenantA.getId(), adminA.getId(), "course.price_changed", "course",
				collidingTargetId, now.minusSeconds(10));
		seedAuditLogRow(tenantB.getId(), adminB.getId(), "course.price_changed", "course", collidingTargetId,
				now.minusSeconds(5));

		var result = auditLogSearch(hostA, tokenA, "action=course.price_changed&targetEntity=course");

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		var body = result.getBody().data();
		assertThat(body.totalElements()).isEqualTo(1);
		assertThat(body.content()).extracting(AuditLogEntryResponse::id).containsExactly(rowA);
	}

}
