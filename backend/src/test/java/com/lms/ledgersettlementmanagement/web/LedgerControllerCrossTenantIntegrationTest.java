package com.lms.ledgersettlementmanagement.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.common.api.PageResponse;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.ledgersettlementmanagement.web.dto.LedgerHistoryEntryResponse;
import com.lms.paymentmanagement.PaymentManagementTestSupport;
import com.lms.paymentmanagement.order.web.dto.OrderResponse;
import com.lms.paymentmanagement.order.web.dto.PaymentInitiationResponse;
import com.lms.tenantmanagement.domain.Tenant;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Dedicated cross-tenant coverage for {@code LedgerController}'s two
 * endpoints - {@code GET /api/v1/ledger/dashboard} (any authenticated role)
 * and {@code GET /api/v1/ledger/history} (student-only) - closing the gap
 * that their only prior proof was one incidental assertion inside {@code
 * PaymentCrossTenantIntegrationTest#ledgerDashboardNeverLeaksAnotherTenantsEntries}.
 *
 * <p>Extends {@link PaymentManagementTestSupport} (not {@code
 * AuthIntegrationTestSupport} directly), mirroring {@code
 * PlatformAdminLedgerControllerIntegrationTest}'s exact established pattern,
 * so real, FK-satisfying {@code ledger_entry} rows are produced the same way
 * every other ledger test in this codebase does: order -> initiate payment ->
 * signed webhook. {@code ledger_entry.order_id}/{@code payment_id} are
 * composite-FK'd to real {@code student_order}/{@code payment} rows scoped to
 * the same tenant, so a bare direct-insert without a real order/payment would
 * violate those constraints.
 *
 * <p>Uses two freshly-seeded, real {@code tenant} rows per test (never the
 * fixed {@code TENANT_A}/{@code TENANT_B} constants, which have no persisted
 * {@code tenant} row and would violate FKs on the webhook path).
 */
@Tag("cross-tenant")
class LedgerControllerCrossTenantIntegrationTest extends PaymentManagementTestSupport {

	@Test
	void dashboardCrossTenantNeverLeaksAnotherTenantsLedgerEntries() {
		Fixture a = seedTenantWithConfirmedLedgerEntry("ledger-ctrl-dash-a");
		Fixture b = seedTenantWithConfirmedLedgerEntry("ledger-ctrl-dash-b");

		HttpResult<PageResponse<LedgerHistoryEntryResponse>> dashboardB = getLedgerDashboard(b.host(), b.adminToken());

		assertThat(dashboardB.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(dashboardB.getBody().data().content()).extracting(LedgerHistoryEntryResponse::paymentId)
			.contains(b.initiation().paymentId())
			.doesNotContain(a.initiation().paymentId());

		// Sanity: tenant A's own dashboard DOES see its own entry (and never
		// tenant B's), proving the exclusion above is tenant isolation, not a
		// broken/empty dashboard.
		HttpResult<PageResponse<LedgerHistoryEntryResponse>> dashboardA = getLedgerDashboard(a.host(), a.adminToken());

		assertThat(dashboardA.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(dashboardA.getBody().data().content()).extracting(LedgerHistoryEntryResponse::paymentId)
			.contains(a.initiation().paymentId())
			.doesNotContain(b.initiation().paymentId());
	}

	@Test
	void historyCrossTenantNeverLeaksAnotherTenantsLedgerEntries() {
		Fixture a = seedTenantWithConfirmedLedgerEntry("ledger-ctrl-hist-a");
		Fixture b = seedTenantWithConfirmedLedgerEntry("ledger-ctrl-hist-b");

		HttpResult<List<LedgerHistoryEntryResponse>> historyB = getLedgerHistory(b.host(), b.studentToken());

		assertThat(historyB.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(historyB.getBody().data()).extracting(LedgerHistoryEntryResponse::paymentId)
			.contains(b.initiation().paymentId())
			.doesNotContain(a.initiation().paymentId());

		// Sanity: tenant A's own student sees their own entry via /history (and
		// never tenant B's), proving the exclusion above is tenant isolation,
		// not a broken/empty history.
		HttpResult<List<LedgerHistoryEntryResponse>> historyA = getLedgerHistory(a.host(), a.studentToken());

		assertThat(historyA.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(historyA.getBody().data()).extracting(LedgerHistoryEntryResponse::paymentId)
			.contains(a.initiation().paymentId())
			.doesNotContain(b.initiation().paymentId());
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
		return new Fixture(tenant, host, adminToken, studentToken, order, initiation);
	}

	private record Fixture(Tenant tenant, String host, String adminToken, String studentToken, OrderResponse order,
			PaymentInitiationResponse initiation) {
	}

}
