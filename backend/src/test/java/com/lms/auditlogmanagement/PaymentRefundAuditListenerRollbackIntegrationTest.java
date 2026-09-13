package com.lms.auditlogmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.paymentmanagement.order.web.dto.OrderResponse;
import com.lms.paymentmanagement.order.web.dto.PaymentInitiationResponse;
import com.lms.tenantmanagement.domain.Tenant;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Mirrors {@code PaymentConfirmationRollbackIntegrationTest}'s exact
 * technique: a real Spring context/transaction manager/Testcontainers
 * Postgres, with only {@link AuditLogApi} replaced by a throwing mock.
 * {@link com.lms.auditlogmanagement.service.AuditLogEventListener
 * #onPaymentRefunded} is a plain {@code @EventListener}, so it runs
 * synchronously inside {@code RefundService}'s own transaction - forcing it
 * to throw must roll back the refund write and the reversing ledger entry
 * too.
 */
class PaymentRefundAuditListenerRollbackIntegrationTest extends AuditLogManagementTestSupport {

	@MockitoBean
	private AuditLogApi auditLogApi;

	@Test
	void aFailureInTheAuditWriteRollsBackTheRefundAndItsReversingLedgerEntry() {
		doThrow(new RuntimeException("Simulated audit log write failure")).when(auditLogApi).record(any());

		Tenant tenant = seedActiveTenant(uniqueSubdomain("audit-refund-rollback"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug("audit-refund-rollback"), teacher.getId(), CourseStatus.PUBLIC));
		OrderResponse order = createOrderOrFail(host, studentToken, course.id());
		PaymentInitiationResponse initiation = initiatePaymentOrFail(host, studentToken, order.id());
		sendPaymentWebhook(initiation.gatewayReference(), true);

		var result = createRefund(host, adminToken, initiation.paymentId(), new BigDecimal("15.00"),
				"Student requested a partial refund");

		// 500 is the expected fallback here, not an unhandled-error smell to
		// "fix" later: the simulated RuntimeException is thrown deep inside
		// the listener, and no handler exists for a raw RuntimeException -
		// GlobalExceptionHandler#handleUnexpected catches it generically.
		// The behavior under test is the transaction rollback asserted below,
		// not the specific status code.
		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

		Long refundRowCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM payment_refund WHERE original_payment_id = ?", Long.class,
				initiation.paymentId());
		assertThat(refundRowCount).isEqualTo(0L);

		Long reversingLedgerCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM ledger_entry WHERE payment_id = ? AND entry_type = 'REFUND'", Long.class,
				initiation.paymentId());
		assertThat(reversingLedgerCount).isEqualTo(0L);

		Long auditRowCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM audit_log WHERE action = 'payment.refunded' AND tenant_id = ?", Long.class,
				tenant.getId());
		assertThat(auditRowCount).isEqualTo(0L);
	}

}
