package com.lms.paymentmanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.paymentmanagement.order.web.dto.OrderResponse;
import com.lms.paymentmanagement.order.web.dto.PaymentInitiationResponse;
import com.lms.paymentmanagement.payment.web.dto.PaymentResponse;
import com.lms.tenantmanagement.domain.Tenant;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Closes plan §18 Testcontainers item 6: a fractional-cent {@code
 * BigDecimal} amount must round-trip exactly through {@code student_order}/
 * {@code payment}'s real Postgres {@code NUMERIC(12,2)} columns - no float
 * coercion, no scale drift - both via the JPA/Hibernate read path (JSON
 * response) and via a raw JDBC read of the same row.
 */
class PaymentMoneyPrecisionIntegrationTest extends PaymentManagementTestSupport {

	/**
	 * Chosen because {@code 133.33} has no exact binary floating-point
	 * representation - if any layer of the stack ever coerced this value
	 * through a {@code double}/{@code float} instead of staying a {@code
	 * BigDecimal} all the way to/from the {@code NUMERIC} column, this value
	 * is the kind of canary that would reveal it as drift after round-trip.
	 */
	private static final BigDecimal TRICKY_PRICE = new BigDecimal("133.33");

	@Test
	void aFractionalCentAmountRoundTripsExactlyThroughRealPostgresNumericColumns() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("pay-precision"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug("pay-precision"), teacher.getId(), CourseStatus.PUBLIC));
		var priceChange = changePrice(host, adminToken, course.id(), TRICKY_PRICE);
		if (priceChange.getStatusCode() != HttpStatus.OK) {
			throw new IllegalStateException("Price change failed: " + priceChange.getStatusCode());
		}
		assertThat(priceChange.getBody().data().price()).isEqualByComparingTo(TRICKY_PRICE);

		OrderResponse order = createOrderOrFail(host, studentToken, course.id());
		assertThat(order.amount()).isEqualByComparingTo(TRICKY_PRICE);

		PaymentInitiationResponse initiation = initiatePaymentOrFail(host, studentToken, order.id());
		sendPaymentWebhook(initiation.gatewayReference(), true);

		// Re-fetch via the authenticated read path (JPA/Hibernate round trip).
		var paymentResult = getPayment(host, studentToken, initiation.paymentId());
		assertThat(paymentResult.getStatusCode()).isEqualTo(HttpStatus.OK);
		PaymentResponse payment = paymentResult.getBody().data();
		assertThat(payment.amount()).isEqualByComparingTo(TRICKY_PRICE);
		assertThat(payment.amount().scale()).isEqualTo(2);

		// Re-fetch via a completely separate, raw JDBC read of the same row -
		// proves the stored value itself carries no drift, independent of
		// any application-layer (de)serialization.
		BigDecimal orderAmountInDb = jdbcTemplate.queryForObject("SELECT amount FROM student_order WHERE id = ?",
				BigDecimal.class, order.id());
		assertThat(orderAmountInDb).isEqualByComparingTo(TRICKY_PRICE);

		BigDecimal paymentAmountInDb = jdbcTemplate.queryForObject("SELECT amount FROM payment WHERE id = ?",
				BigDecimal.class, initiation.paymentId());
		assertThat(paymentAmountInDb).isEqualByComparingTo(TRICKY_PRICE);

		Long ledgerCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM ledger_entry WHERE payment_id = ? AND amount = ?", Long.class,
				initiation.paymentId(), TRICKY_PRICE);
		assertThat(ledgerCount).isEqualTo(1L);
	}

}
