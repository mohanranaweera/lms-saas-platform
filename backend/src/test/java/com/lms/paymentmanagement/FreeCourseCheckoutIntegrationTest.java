package com.lms.paymentmanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.common.api.PageResponse;
import com.lms.coursemanagement.course.domain.CoursePricingModel;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.ledgersettlementmanagement.domain.LedgerEntryType;
import com.lms.ledgersettlementmanagement.web.dto.LedgerHistoryEntryResponse;
import com.lms.paymentmanagement.order.web.dto.OrderResponse;
import com.lms.tenantmanagement.domain.Tenant;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Testcontainers/HTTP end-to-end coverage for Wave 2's {@code FREE}-pricing
 * checkout flow (V41/V42, ADR-015), proving the real production path this
 * Mockito-only {@code OrderServiceTest} unit test cannot reach: a {@code $0}
 * {@link com.lms.paymentmanagement.payment.domain.Payment} genuinely reaches
 * {@code CONFIRMED} via its real {@code PENDING -> confirm()} transition, a
 * real {@code $0} {@code PAYMENT_CONFIRMED} {@code ledger_entry} row is
 * written and visible via the actual ledger-derived Payment History/Payment
 * Dashboard read endpoints (V42), and the real (unmocked) {@code
 * EnrollmentActivationService} - reached only through the unchanged {@code
 * PaymentStatusApi#isConfirmedForCurrentTenant} path - produces a genuinely
 * {@code ACTIVE} {@code enrollment} row, with no violation of {@code
 * ck_enrollment_exactly_one_activation_source} (V19). Mirrors {@code
 * SlipApprovalActivationIntegrationTest}'s established technique of
 * asserting directly against the schema via {@code jdbcTemplate}, not just
 * the HTTP response shape.
 */
class FreeCourseCheckoutIntegrationTest extends PaymentManagementTestSupport {

	@Test
	void checkingOutAFreeCourseAutoConfirmsAZeroAmountPaymentAndActivatesEnrollment() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("free-checkout"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser student = seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug("free-checkout"), teacher.getId(), CourseStatus.PUBLIC));
		changePricingModel(host, adminToken, course.id(), CoursePricingModel.FREE);

		OrderResponse order = createOrderOrFail(host, studentToken, course.id());

		assertThat(order.amount()).isEqualByComparingTo("0.00");

		// Payment: created and driven through the real PENDING -> CONFIRMED
		// transition (confirm() throws unless gateway_reference is already
		// present, so a non-null value here proves the real transition ran,
		// not a direct CONFIRMED construction).
		String paymentStatus = jdbcTemplate.queryForObject("SELECT status FROM payment WHERE order_id = ?",
				String.class, order.id());
		assertThat(paymentStatus).isEqualTo("CONFIRMED");
		BigDecimal paymentAmount = jdbcTemplate.queryForObject("SELECT amount FROM payment WHERE order_id = ?",
				BigDecimal.class, order.id());
		assertThat(paymentAmount).isEqualByComparingTo("0.00");
		String gatewayReference = jdbcTemplate.queryForObject(
				"SELECT gateway_reference FROM payment WHERE order_id = ?", String.class, order.id());
		assertThat(gatewayReference).isNotNull();
		UUID paymentId = jdbcTemplate.queryForObject("SELECT id FROM payment WHERE order_id = ?", UUID.class,
				order.id());

		// Enrollment: ACTIVE, activated from this exact payment, via the
		// unchanged EnrollmentActivationApi/PaymentStatusApi path - never a
		// new activation code path, never both activation-source columns set
		// (ck_enrollment_exactly_one_activation_source, V19).
		Long enrollmentCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM enrollment WHERE tenant_id = ? AND student_id = ? AND course_id = ? "
						+ "AND activating_payment_id = ? AND activating_slip_id IS NULL AND status = 'ACTIVE'",
				Long.class, tenant.getId(), student.getId(), course.id(), paymentId);
		assertThat(enrollmentCount).isEqualTo(1L);

		// Fix 2 (Phase E review, ADR-015/V42): a real $0 PAYMENT_CONFIRMED
		// ledger_entry row now exists for this FREE checkout, linked to the
		// correct payment/order/tenant - never silently skipped.
		Long ledgerEntryCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM ledger_entry WHERE tenant_id = ? AND order_id = ? AND payment_id = ? "
						+ "AND entry_type = 'PAYMENT_CONFIRMED' AND amount = 0.00",
				Long.class, tenant.getId(), order.id(), paymentId);
		assertThat(ledgerEntryCount).isEqualTo(1L);

		// ...and it is genuinely visible via the actual ledger-derived
		// Payment History (student) / Payment Dashboard (staff) read paths -
		// per .claude/rules/payments.md §2, "if a screen shows 'paid' but no
		// corresponding ledger entry exists, that is a bug" - the converse
		// (a FREE enrollment invisible on both screens) is exactly the gap
		// this fix closes.
		HttpResult<List<LedgerHistoryEntryResponse>> history = getLedgerHistory(host, studentToken);
		assertThat(history.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(history.getBody().data()).extracting(LedgerHistoryEntryResponse::paymentId).contains(paymentId);
		assertThat(history.getBody().data()).filteredOn(entry -> paymentId.equals(entry.paymentId()))
			.allSatisfy(entry -> {
				assertThat(entry.entryType()).isEqualTo(LedgerEntryType.PAYMENT_CONFIRMED);
				assertThat(entry.amount()).isEqualByComparingTo("0.00");
				assertThat(entry.orderId()).isEqualTo(order.id());
			});

		HttpResult<PageResponse<LedgerHistoryEntryResponse>> dashboard = getLedgerDashboard(host, adminToken);
		assertThat(dashboard.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(dashboard.getBody().data().content()).extracting(LedgerHistoryEntryResponse::paymentId)
			.contains(paymentId);
	}

	@Test
	void freeCourseCheckoutSucceedsWithHttp201AndReturnsAZeroAmountOrder() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("free-checkout-http"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug("free-checkout-http"), teacher.getId(), CourseStatus.PUBLIC));
		changePricingModel(host, adminToken, course.id(), CoursePricingModel.FREE);

		var result = createOrder(host, studentToken, course.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(result.getBody().data().amount()).isEqualByComparingTo("0.00");
		assertThat(result.getBody().data().billingPeriodId()).isNull();
	}

	// ------------------------------------------------------------------
	// Fix 1 (Phase E review, ADR-015): a NON-FREE course resolving to a $0
	// checkout amount must be rejected, never silently free-activated.
	// ------------------------------------------------------------------

	@Test
	void aZeroPricedOneTimeCourseIsRejectedWithConflictAndNeverActivatesEnrollment() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("zero-one-time"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser student = seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");
		// Default pricing model is ONE_TIME (never changed here) - only the
		// price is misconfigured down to $0.
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug("zero-one-time"), teacher.getId(), CourseStatus.PUBLIC));
		changePrice(host, adminToken, course.id(), BigDecimal.ZERO);

		var result = createOrder(host, studentToken, course.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

		Long orderCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM student_order WHERE tenant_id = ? AND course_id = ? AND student_id = ?",
				Long.class, tenant.getId(), course.id(), student.getId());
		assertThat(orderCount).isEqualTo(0L);
		Long paymentCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM payment p JOIN student_order o ON p.order_id = o.id "
						+ "WHERE o.tenant_id = ? AND o.course_id = ?",
				Long.class, tenant.getId(), course.id());
		assertThat(paymentCount).isEqualTo(0L);
		Long enrollmentCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM enrollment WHERE tenant_id = ? AND student_id = ? AND course_id = ?",
				Long.class, tenant.getId(), student.getId(), course.id());
		assertThat(enrollmentCount).isEqualTo(0L);
	}

	@Test
	void aZeroPricedMonthlyBillingPeriodCourseIsRejectedWithConflictAndNeverActivatesEnrollment() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("zero-monthly"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser student = seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug("zero-monthly"), teacher.getId(), CourseStatus.PUBLIC));
		changePricingModel(host, adminToken, course.id(), CoursePricingModel.MONTHLY);
		createOrUpdateBillingConfiguration(host, adminToken, course.id(), null, "USD", false);
		addBillingPeriod(host, adminToken, course.id(), BigDecimal.ZERO, "USD", java.time.Instant.now());

		var result = createOrder(host, studentToken, course.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

		Long enrollmentCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM enrollment WHERE tenant_id = ? AND student_id = ? AND course_id = ?",
				Long.class, tenant.getId(), student.getId(), course.id());
		assertThat(enrollmentCount).isEqualTo(0L);
	}

}
