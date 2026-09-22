package com.lms.paymentmanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.coursemanagement.course.domain.CoursePricingModel;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseBillingPeriodResponse;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.paymentmanagement.order.web.dto.OrderResponse;
import com.lms.paymentmanagement.order.web.dto.PaymentInitiationResponse;
import com.lms.paymentmanagement.payment.web.dto.PaymentResponse;
import com.lms.tenantmanagement.domain.Tenant;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Testcontainers-backed coverage for Wave 2's single most important
 * financial-integrity guarantee (per {@code .claude/rules/payments.md} §4
 * "append-only ledger"/"historical settlement records must remain
 * reproducible" and {@code .claude/rules/backend.md}'s append-only
 * {@code course_billing_period} design): once a {@code MONTHLY}/{@code
 * SESSION} order/payment has snapshotted a resolved {@code
 * course_billing_period}'s amount at creation time, adding a LATER billing
 * period must never change what that already-placed order or its payment
 * shows - neither via the HTTP read APIs nor in the underlying rows.
 *
 * <p>{@link com.lms.coursemanagement.course.service.BillingConfigurationServiceTest}
 * and {@code CourseBillingAndLifecycleIntegrationTest} already prove the
 * "closing a period never mutates its own stored amount" half of this
 * guarantee. This class proves the other, financially-critical half that
 * neither of those covers: a real {@code student_order}/{@code payment} row
 * created against an earlier period stays pinned to that period's amount -
 * at the HTTP layer AND directly in the database - even after a later
 * period is added and even after the earlier period's own history row is
 * independently confirmed unchanged.
 */
class BillingHistoryIntegrityIntegrationTest extends PaymentManagementTestSupport {

	@Test
	void anOrderAndItsPaymentStayPinnedToTheOriginalBillingPeriodsAmountAfterALaterPeriodIsAdded() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("bill-history"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");

		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug("bill-history"), teacher.getId(), CourseStatus.PUBLIC));
		changePricingModel(host, adminToken, course.id(), CoursePricingModel.MONTHLY);
		createOrUpdateBillingConfiguration(host, adminToken, course.id(), null, "USD", false);

		// Period X (the ONLY, thus "current open", period at order-creation
		// time): $30.00.
		HttpResult<CourseBillingPeriodResponse> periodXResult = addBillingPeriod(host, adminToken, course.id(),
				new BigDecimal("30.00"), "USD", Instant.now().minusSeconds(3600));
		assertThat(periodXResult.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		UUID periodXId = periodXResult.getBody().data().id();

		// Order + a REAL, webhook-confirmed payment - both snapshot period X's
		// $30.00 at this instant, per OrderService#createOrder's javadoc
		// ("amount/currency are a snapshot... never re-read from the course
		// later").
		OrderResponse order = createOrderOrFail(host, studentToken, course.id());
		assertThat(order.amount()).isEqualByComparingTo("30.00");
		assertThat(order.billingPeriodId()).isEqualTo(periodXId);

		PaymentInitiationResponse initiation = initiatePaymentOrFail(host, studentToken, order.id());
		HttpResult<Void> webhook = sendPaymentWebhook(initiation.gatewayReference(), true);
		assertThat(webhook.getStatusCode()).isEqualTo(HttpStatus.OK);

		String paymentStatusBeforeNewPeriod = jdbcTemplate.queryForObject("SELECT status FROM payment WHERE id = ?",
				String.class, initiation.paymentId());
		assertThat(paymentStatusBeforeNewPeriod).isEqualTo("CONFIRMED");

		// Add period Y AFTER the order/payment above already exist: $45.00,
		// effective now - this closes period X (effective_to set) and becomes
		// the new "current open" period.
		HttpResult<CourseBillingPeriodResponse> periodYResult = addBillingPeriod(host, adminToken, course.id(),
				new BigDecimal("45.00"), "USD", Instant.now());
		assertThat(periodYResult.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		UUID periodYId = periodYResult.getBody().data().id();
		assertThat(periodYId).isNotEqualTo(periodXId);

		// --- The order created against period X, re-read via HTTP, is
		// unchanged. ---
		HttpResult<OrderResponse> orderAfter = getOrder(host, studentToken, order.id());
		assertThat(orderAfter.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(orderAfter.getBody().data().amount()).isEqualByComparingTo("30.00");
		assertThat(orderAfter.getBody().data().billingPeriodId()).isEqualTo(periodXId);

		// --- The payment created against period X's $30.00, re-read via
		// HTTP, is unchanged - still CONFIRMED, still $30.00, never
		// recomputed against period Y. ---
		HttpResult<PaymentResponse> paymentAfter = getPayment(host, studentToken, initiation.paymentId());
		assertThat(paymentAfter.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(paymentAfter.getBody().data().amount()).isEqualByComparingTo("30.00");
		assertThat(paymentAfter.getBody().data().status().name()).isEqualTo("CONFIRMED");

		// --- Direct database assertions - the append-only rows themselves,
		// not just the read API's shaping of them. ---
		BigDecimal orderAmountInDb = jdbcTemplate.queryForObject("SELECT amount FROM student_order WHERE id = ?",
				BigDecimal.class, order.id());
		assertThat(orderAmountInDb).isEqualByComparingTo("30.00");

		BigDecimal paymentAmountInDb = jdbcTemplate.queryForObject("SELECT amount FROM payment WHERE id = ?",
				BigDecimal.class, initiation.paymentId());
		assertThat(paymentAmountInDb).isEqualByComparingTo("30.00");

		BigDecimal periodXAmountInDb = jdbcTemplate.queryForObject(
				"SELECT amount FROM course_billing_period WHERE id = ?", BigDecimal.class, periodXId);
		assertThat(periodXAmountInDb).isEqualByComparingTo("30.00"); // never mutated

		Instant periodXEffectiveTo = jdbcTemplate.queryForObject(
				"SELECT effective_to FROM course_billing_period WHERE id = ?", Instant.class, periodXId);
		assertThat(periodXEffectiveTo).isNotNull(); // closed by period Y's insertion

		BigDecimal periodYAmountInDb = jdbcTemplate.queryForObject(
				"SELECT amount FROM course_billing_period WHERE id = ?", BigDecimal.class, periodYId);
		assertThat(periodYAmountInDb).isEqualByComparingTo("45.00");

		// --- Forward-looking sanity check: a NEW order placed now correctly
		// resolves period Y's $45.00 - proving the pinning above is genuinely
		// about historical preservation, not a resolution bug that happens to
		// always return $30.00. ---
		seedActiveStudent(tenant.getId(), "student-two@example.test");
		String studentTwoToken = loginAndGetToken(host, "student-two@example.test");
		OrderResponse newOrder = createOrderOrFail(host, studentTwoToken, course.id());
		assertThat(newOrder.amount()).isEqualByComparingTo("45.00");
		assertThat(newOrder.billingPeriodId()).isEqualTo(periodYId);
	}

}
