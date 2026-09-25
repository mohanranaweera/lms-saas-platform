package com.lms.ledgersettlementmanagement.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.ledgersettlementmanagement.api.PaymentOperationalState;
import com.lms.paymentmanagement.PaymentManagementTestSupport;
import com.lms.paymentmanagement.order.web.dto.OrderResponse;
import com.lms.paymentmanagement.order.web.dto.PaymentInitiationResponse;
import com.lms.tenantmanagement.domain.Tenant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Real-Spring-context/Testcontainers wiring proof for {@link
 * PaymentOperationalStateService} - the pure per-case resolution logic is
 * already exhaustively covered by {@link PaymentOperationalStateResolverTest};
 * this proves the cross-module batched reads ({@code
 * PaymentStatusApi#findOrderPaymentDetails}, {@code
 * SlipStatusApi#findOrderSlipDetails}, this module's own {@code
 * ledger_entry} rows) are actually assembled correctly end-to-end, and that
 * the projection never leaks another tenant's order into the result for an
 * id it was never asked to resolve in the first place (tenant scoping is
 * structural here - see {@code PaymentStatusApiImpl}/{@code
 * SlipStatusApiImpl}, both built on {@code TenantAwareRepository}).
 */
class PaymentOperationalStateServiceIntegrationTest extends PaymentManagementTestSupport {

	@Autowired
	private PaymentOperationalStateService paymentOperationalStateService;

	@Test
	void aConfirmedGatewayPaymentResolvesToPaid() {
		Fixture fixture = seedTenantWithConfirmedOrder("pos-paid");

		Map<UUID, PaymentOperationalState> states = withTenant(fixture.tenant().getId(),
				() -> paymentOperationalStateService.resolveForOrders(List.of(fixture.order().id())));

		assertThat(states).containsEntry(fixture.order().id(), PaymentOperationalState.PAID);
	}

	@Test
	void anOrderWithNoPaymentAttemptResolvesToUnpaid() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("pos-unpaid"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug("pos-unpaid"), teacher.getId(), CourseStatus.PUBLIC));
		OrderResponse order = createOrderOrFail(host, studentToken, course.id());

		Map<UUID, PaymentOperationalState> states = withTenant(tenant.getId(),
				() -> paymentOperationalStateService.resolveForOrders(List.of(order.id())));

		assertThat(states).containsEntry(order.id(), PaymentOperationalState.UNPAID);
	}

	@Test
	void resolvingForATenantBOrderIdWhileScopedToTenantANeverReturnsTenantBsState() {
		Fixture a = seedTenantWithConfirmedOrder("pos-cross-a");
		Fixture b = seedTenantWithConfirmedOrder("pos-cross-b");

		Map<UUID, PaymentOperationalState> statesForA = withTenant(a.tenant().getId(),
				() -> paymentOperationalStateService.resolveForOrders(List.of(a.order().id(), b.order().id())));

		// Tenant B's order id, requested while scoped to tenant A, is simply
		// absent from the result - never tenant B's real PAID state leaking
		// through, and never an error that would confirm the id exists.
		assertThat(statesForA).containsKey(a.order().id());
		assertThat(statesForA).doesNotContainKey(b.order().id());
	}

	// ------------------------------------------------------------------
	// Fixtures.
	// ------------------------------------------------------------------

	private Fixture seedTenantWithConfirmedOrder(String prefix) {
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
		return new Fixture(tenant, order);
	}

	private record Fixture(Tenant tenant, OrderResponse order) {
	}

}
