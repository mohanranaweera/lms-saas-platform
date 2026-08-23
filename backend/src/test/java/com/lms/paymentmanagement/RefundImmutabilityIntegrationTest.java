package com.lms.paymentmanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.paymentmanagement.order.web.dto.OrderResponse;
import com.lms.paymentmanagement.order.web.dto.PaymentInitiationResponse;
import com.lms.paymentmanagement.payment.web.dto.PaymentResponse;
import com.lms.paymentmanagement.payment.web.dto.RefundResponse;
import com.lms.tenantmanagement.domain.Tenant;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Closes plan §18 Testcontainers item 5: a refund creates a new {@code
 * payment_refund} row (and a reversing {@code ledger_entry} via {@code
 * reverses_entry_id}) while the original {@code Payment} row is
 * field-for-field unchanged - a full snapshot comparison of every exposed
 * field before and after, not merely "no update endpoint exists" or a single
 * status-field check (the latter is already covered by {@code
 * PaymentAndLedgerIntegrationTest#refundingAConfirmedPaymentWritesAReversingLedgerEntryAndNeverMutatesPaymentStatus}).
 */
class RefundImmutabilityIntegrationTest extends PaymentManagementTestSupport {

	@Test
	void refundingLeavesEveryFieldOfTheOriginalPaymentRowByteForByteIdenticalBeforeAndAfter() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("pay-refund-snap"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug("pay-refund-snap"), teacher.getId(), CourseStatus.PUBLIC));
		OrderResponse order = createOrderOrFail(host, studentToken, course.id());
		PaymentInitiationResponse initiation = initiatePaymentOrFail(host, studentToken, order.id());
		sendPaymentWebhook(initiation.gatewayReference(), true);

		PaymentResponse before = getPayment(host, studentToken, initiation.paymentId()).getBody().data();

		HttpResult<RefundResponse> refundResult = createRefund(host, adminToken, initiation.paymentId(),
				new BigDecimal("15.00"), "Student requested a partial refund");
		assertThat(refundResult.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		RefundResponse refund = refundResult.getBody().data();
		assertThat(refund.id()).isNotNull();
		assertThat(refund.originalPaymentId()).isEqualTo(initiation.paymentId());
		assertThat(refund.amount()).isEqualByComparingTo(new BigDecimal("15.00"));

		PaymentResponse after = getPayment(host, studentToken, initiation.paymentId()).getBody().data();

		// Field-for-field snapshot equality on the ORIGINAL payment - the
		// refund must never mutate any of these.
		assertThat(after.id()).isEqualTo(before.id());
		assertThat(after.orderId()).isEqualTo(before.orderId());
		assertThat(after.amount()).isEqualByComparingTo(before.amount());
		assertThat(after.currency()).isEqualTo(before.currency());
		assertThat(after.status()).isEqualTo(before.status());
		assertThat(after.gatewayReference()).isEqualTo(before.gatewayReference());
		assertThat(after.confirmedAt()).isEqualTo(before.confirmedAt());
		assertThat(after.createdAt()).isEqualTo(before.createdAt());

		// Cross-check against the raw row too, not just the DTO the read
		// path happens to expose.
		Long paymentRowCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM payment WHERE id = ? AND status = 'CONFIRMED'", Long.class,
				initiation.paymentId());
		assertThat(paymentRowCount).isEqualTo(1L);

		// Exactly one new payment_refund row and one new reversing
		// ledger_entry row were created - the mutation is additive only.
		Long refundRowCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM payment_refund WHERE original_payment_id = ?", Long.class,
				initiation.paymentId());
		assertThat(refundRowCount).isEqualTo(1L);

		Long reversingLedgerCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM ledger_entry WHERE payment_id = ? AND entry_type = 'REFUND' "
						+ "AND reverses_entry_id IS NOT NULL",
				Long.class, initiation.paymentId());
		assertThat(reversingLedgerCount).isEqualTo(1L);
	}

}
