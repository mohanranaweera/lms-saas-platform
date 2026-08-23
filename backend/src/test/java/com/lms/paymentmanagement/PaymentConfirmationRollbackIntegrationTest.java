package com.lms.paymentmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.enrollmentmanagement.api.EnrollmentActivationApi;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.paymentmanagement.order.web.dto.OrderResponse;
import com.lms.paymentmanagement.order.web.dto.PaymentInitiationResponse;
import com.lms.tenantmanagement.domain.Tenant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Closes plan §18 Testcontainers item 2's "plus a simulated
 * mid-transaction-failure/rollback test" requirement: {@link
 * com.lms.paymentmanagement.payment.service.PaymentConfirmationService#confirmByGatewayReference}
 * is one {@code @Transactional} method that writes {@code payment.status},
 * a {@code ledger_entry}, and (via {@link EnrollmentActivationApi}) an
 * {@code enrollment} row. This test forces the LAST side effect in that
 * chain to throw, using a real Spring context (real transaction manager,
 * real Testcontainers Postgres) with only {@link EnrollmentActivationApi}
 * replaced by a throwing mock, and proves the entire transaction rolls
 * back: no partial state - the {@code Payment} row is not left {@code
 * CONFIRMED} and no {@code ledger_entry} row survives the rollback either.
 */
class PaymentConfirmationRollbackIntegrationTest extends PaymentManagementTestSupport {

	@MockitoBean
	private EnrollmentActivationApi enrollmentActivationApi;

	@Autowired
	private com.lms.paymentmanagement.payment.repository.PaymentRepository paymentRepositoryForAssertions;

	@Test
	void aFailureInEnrollmentActivationRollsBackTheEntirePaymentConfirmationTransaction() {
		doThrow(new RuntimeException("Simulated mid-transaction failure in enrollment activation")).when(
				enrollmentActivationApi)
			.activateFromConfirmedPayment(any(), any(), any());

		Tenant tenant = seedActiveTenant(uniqueSubdomain("pay-rollback"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug("pay-rollback"), teacher.getId(), CourseStatus.PUBLIC));
		OrderResponse order = createOrderOrFail(host, studentToken, course.id());
		PaymentInitiationResponse initiation = initiatePaymentOrFail(host, studentToken, order.id());

		HttpResult<Void> webhookResult = sendPaymentWebhook(initiation.gatewayReference(), true);

		assertThat(webhookResult.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

		// The payment row must NOT be left CONFIRMED - the whole
		// transaction (status transition + ledger write) must have rolled
		// back together with the failed activation call.
		String status = jdbcTemplate.queryForObject("SELECT status FROM payment WHERE id = ?", String.class,
				initiation.paymentId());
		assertThat(status).isEqualTo("PENDING");

		Long ledgerCount = jdbcTemplate.queryForObject("SELECT count(*) FROM ledger_entry WHERE payment_id = ?",
				Long.class, initiation.paymentId());
		assertThat(ledgerCount).isEqualTo(0L);

		Long enrollmentCount = jdbcTemplate.queryForObject("SELECT count(*) FROM enrollment WHERE tenant_id = ?",
				Long.class, tenant.getId());
		assertThat(enrollmentCount).isEqualTo(0L);

		// Also confirm via the repository/entity layer, not just raw SQL.
		var reloaded = withTenant(tenant.getId(), () -> paymentRepositoryForAssertions.findById(initiation.paymentId()));
		assertThat(reloaded).isPresent();
		assertThat(reloaded.get().getStatus())
			.isEqualTo(com.lms.paymentmanagement.payment.domain.PaymentStatus.PENDING);
		assertThat(reloaded.get().getConfirmedAt()).isNull();
	}

}
