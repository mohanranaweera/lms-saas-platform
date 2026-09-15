package com.lms.ledgersettlementmanagement.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.lms.common.api.PageResponse;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.identityaccessservice.web.dto.LoginResponse;
import com.lms.ledgersettlementmanagement.web.dto.PlatformLedgerEntryResponse;
import com.lms.paymentmanagement.PaymentManagementTestSupport;
import com.lms.paymentmanagement.order.web.dto.OrderResponse;
import com.lms.paymentmanagement.order.web.dto.PaymentInitiationResponse;
import com.lms.tenantmanagement.domain.Tenant;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * PADASH-2 (cross-tenant payment dashboard) - real Spring Security filter
 * chain, real Testcontainers Postgres. Extends {@link
 * PaymentManagementTestSupport} (not {@code AuthIntegrationTestSupport}
 * directly) so real, FK-satisfying {@code ledger_entry} rows can be created
 * the same way every other ledger test in this codebase does: order ->
 * initiate payment -> signed webhook (see {@code
 * PaymentAndLedgerIntegrationTest}/{@code PaymentCrossTenantIntegrationTest})
 * - {@code ledger_entry.order_id}/{@code payment_id} are composite-FK'd to
 * real {@code student_order}/{@code payment} rows scoped to the same tenant,
 * so a bare direct-insert without a real order/payment would violate those
 * constraints.
 *
 * <p>Uses two freshly-seeded, real {@code tenant} rows per cross-tenant test
 * (mirroring {@code PaymentCrossTenantIntegrationTest}'s own established
 * pattern) rather than the fixed {@code TENANT_A}/{@code TENANT_B} UUID
 * constants from {@code AbstractIntegrationTest} - those constants have no
 * corresponding persisted {@code tenant} row, so a full HTTP order/payment/
 * webhook flow against them would fail the same composite FK constraints.
 */
class PlatformAdminLedgerControllerIntegrationTest extends PaymentManagementTestSupport {

	// ------------------------------------------------------------------
	// Unauthenticated / tenant-JWT-replay.
	// ------------------------------------------------------------------

	@Test
	void unauthenticatedRequestToPlatformPaymentDashboardIsRejectedWith401Unauthenticated() {
		HttpResult<PageResponse<PlatformLedgerEntryResponse>> dashboard = platformDashboard(null);
		HttpResult<PageResponse<PlatformLedgerEntryResponse>> drillDown = platformDrillDown(null, UUID.randomUUID());

		assertUnauthenticated(dashboard);
		assertUnauthenticated(drillDown);
	}

	@Test
	void tenantScopedJwtAgainstPlatformPaymentDashboardEndpointsIsRejectedWith401SessionRevoked() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("padash2-ledger-cross-role"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String tenantToken = loginAndGetToken(host, "admin@example.test");

		assertSessionRevoked(platformDashboard(tenantToken));
		assertSessionRevoked(platformDrillDown(tenantToken, tenant.getId()));
	}

	private void assertUnauthenticated(HttpResult<?> result) {
		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(result.getBody().success()).isFalse();
		assertThat(result.getBody().error().code()).isEqualTo("UNAUTHENTICATED");
	}

	private void assertSessionRevoked(HttpResult<?> result) {
		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(result.getBody().success()).isFalse();
		assertThat(result.getBody().error().code()).isEqualTo("SESSION_REVOKED");
	}

	// ------------------------------------------------------------------
	// Cross-tenant, non-blended, per-row attribution.
	// ------------------------------------------------------------------

	@Test
	@Tag("cross-tenant")
	void crossTenantPaymentDashboardNeverMixesTenantAAndTenantBAmountsInOneAggregateRow() {
		Fixture a = seedTenantWithConfirmedLedgerEntry("padash2-dash-a");
		Fixture b = seedTenantWithConfirmedLedgerEntry("padash2-dash-b");
		String adminToken = seedAndLoginPlatformAdmin("padash2-dash-admin@platform.test");

		HttpResult<PageResponse<PlatformLedgerEntryResponse>> result = platformDashboard(adminToken);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		List<PlatformLedgerEntryResponse> content = result.getBody().data().content();
		// Every row is individually attributed to its own real seeded
		// tenant - never blended, never missing a discriminator.
		assertThat(content).allSatisfy(row -> {
			assertThat(row.tenantId()).isNotNull();
			assertThat(row.tenantName()).isNotNull();
		});
		List<UUID> paymentIdsForTenantA = content.stream()
			.filter(row -> row.tenantId().equals(a.tenant.getId()))
			.map(PlatformLedgerEntryResponse::paymentId)
			.toList();
		List<UUID> paymentIdsForTenantB = content.stream()
			.filter(row -> row.tenantId().equals(b.tenant.getId()))
			.map(PlatformLedgerEntryResponse::paymentId)
			.toList();
		assertThat(paymentIdsForTenantA).contains(a.initiation.paymentId());
		assertThat(paymentIdsForTenantB).contains(b.initiation.paymentId());
		// The row carrying tenant A's payment must never also claim tenant B's id, and vice versa.
		assertThat(content.stream().filter(row -> row.paymentId().equals(a.initiation.paymentId())).findFirst()
			.orElseThrow().tenantId()).isEqualTo(a.tenant.getId());
		assertThat(content.stream().filter(row -> row.paymentId().equals(b.initiation.paymentId())).findFirst()
			.orElseThrow().tenantId()).isEqualTo(b.tenant.getId());
	}

	@Test
	void platformPaymentDashboardIsReadOnlyAndNeverMutatesUnderlyingPaymentOrLedgerRows() {
		seedTenantWithConfirmedLedgerEntry("padash2-readonly");
		String adminToken = seedAndLoginPlatformAdmin("padash2-readonly-admin@platform.test");

		Object[] before = ledgerChecksum();
		platformDashboard(adminToken);
		platformDashboard(adminToken);
		Object[] after = ledgerChecksum();

		assertThat(after).isEqualTo(before);
	}

	private Object[] ledgerChecksum() {
		Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM ledger_entry", Long.class);
		BigDecimal sum = jdbcTemplate.queryForObject("SELECT coalesce(sum(amount), 0) FROM ledger_entry",
				BigDecimal.class);
		return new Object[] { count, sum == null ? null : sum.stripTrailingZeros() };
	}

	@Test
	@Tag("cross-tenant")
	void tenantDrillDownReturnsOnlyThatTenantsRowsWithCorrectDiscriminator() {
		Fixture a = seedTenantWithConfirmedLedgerEntry("padash2-drill-a");
		Fixture b = seedTenantWithConfirmedLedgerEntry("padash2-drill-b");
		String adminToken = seedAndLoginPlatformAdmin("padash2-drill-admin@platform.test");

		HttpResult<PageResponse<PlatformLedgerEntryResponse>> result = platformDrillDown(adminToken, a.tenant.getId());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		List<PlatformLedgerEntryResponse> content = result.getBody().data().content();
		assertThat(content).isNotEmpty();
		assertThat(content).allMatch(row -> row.tenantId().equals(a.tenant.getId()));
		assertThat(content).noneMatch(row -> row.paymentId().equals(b.initiation.paymentId()));
	}

	@Test
	void drillDownEndpointStillRequiresPlatformAdminRoleRegardlessOfTenantIdMatchingCallersOwnTenant() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("padash2-drill-own-tenant"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String tenantAdminToken = loginAndGetToken(host, "admin@example.test");

		HttpResult<PageResponse<PlatformLedgerEntryResponse>> result = platformDrillDown(tenantAdminToken,
				tenant.getId());

		assertSessionRevoked(result);
	}

	@Test
	void drillDownWithANonexistentTenantIdReturns404() {
		String adminToken = seedAndLoginPlatformAdmin("padash2-drill-404-admin@platform.test");

		HttpResult<PageResponse<PlatformLedgerEntryResponse>> result = platformDrillDown(adminToken,
				UUID.randomUUID());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(result.getBody().error().code()).isEqualTo("NOT_FOUND");
	}

	// ------------------------------------------------------------------
	// Fixtures.
	// ------------------------------------------------------------------

	private Fixture seedTenantWithConfirmedLedgerEntry(String prefix) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain(prefix));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug(prefix), teacher.getId(), CourseStatus.PUBLIC));
		OrderResponse order = createOrderOrFail(host, studentToken, course.id());
		PaymentInitiationResponse initiation = initiatePaymentOrFail(host, studentToken, order.id());
		sendPaymentWebhook(initiation.gatewayReference(), true);
		return new Fixture(tenant, host, adminToken, order, initiation);
	}

	private record Fixture(Tenant tenant, String host, String adminToken, OrderResponse order,
			PaymentInitiationResponse initiation) {
	}

	// ------------------------------------------------------------------
	// Local HTTP helpers - PaymentManagementTestSupport's authenticated/
	// perform/parsePage already handle a null host (skips the Host header),
	// which is exactly the shape a platform-admin request needs.
	// ------------------------------------------------------------------

	private String seedAndLoginPlatformAdmin(String email) {
		seedPlatformAdmin(email, RAW_PASSWORD);
		HttpResult<LoginResponse> loginResult = platformAdminLogin(null, email, RAW_PASSWORD);
		if (loginResult.getStatusCode() != HttpStatus.OK) {
			throw new IllegalStateException("Platform admin login failed: " + loginResult.getStatusCode());
		}
		return loginResult.getBody().data().accessToken();
	}

	private HttpResult<PageResponse<PlatformLedgerEntryResponse>> platformDashboard(String token) {
		MockHttpServletRequestBuilder builder = get("/api/v1/platform-admin/payments/dashboard?size=100");
		return parsePage(perform(authenticated(builder, null, token)), PlatformLedgerEntryResponse.class);
	}

	private HttpResult<PageResponse<PlatformLedgerEntryResponse>> platformDrillDown(String token, UUID tenantId) {
		MockHttpServletRequestBuilder builder = get("/api/v1/platform-admin/payments/tenants/{tenantId}?size=100",
				tenantId);
		return parsePage(perform(authenticated(builder, null, token)), PlatformLedgerEntryResponse.class);
	}

}
