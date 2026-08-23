package com.lms.paymentmanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.paymentmanagement.order.web.dto.OrderPaymentStatusResponse;
import com.lms.paymentmanagement.order.web.dto.OrderResponse;
import com.lms.paymentmanagement.order.web.dto.PaymentInitiationResponse;
import com.lms.paymentmanagement.payment.domain.PaymentStatus;
import com.lms.paymentmanagement.payment.web.dto.PaymentResponse;
import com.lms.tenantmanagement.domain.Tenant;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Testcontainers-backed coverage of MVP-010's core end-to-end flow (plan
 * §18 Testcontainers item 2): create order -> initiate payment -> simulate a
 * signed webhook call -> assert {@code Payment.status == CONFIRMED}, exactly
 * one {@code ledger_entry} row, exactly one {@code enrollment} row with
 * {@code activatingPaymentId} set. Modeled on {@code
 * CourseManagementIntegrationTest}'s base-class/setup pattern.
 */
class PaymentAndLedgerIntegrationTest extends PaymentManagementTestSupport {

	@Test
	void createOrderInitiatePaymentAndAConfirmingWebhookActivatesEnrollmentInOneAtomicFlow() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("pay-e2e"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser student = seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug("pay-e2e"), teacher.getId(), CourseStatus.PUBLIC));

		OrderResponse order = createOrderOrFail(host, studentToken, course.id());
		assertThat(order.amount()).isEqualByComparingTo(course.price());
		assertThat(order.studentId()).isEqualTo(student.getId());

		PaymentInitiationResponse initiation = initiatePaymentOrFail(host, studentToken, order.id());
		assertThat(initiation.gatewayReference()).isNotBlank();
		assertThat(initiation.status()).isEqualTo(PaymentStatus.PENDING);

		HttpResult<Void> webhookResult = sendPaymentWebhook(initiation.gatewayReference(), true);
		assertThat(webhookResult.getStatusCode()).isEqualTo(HttpStatus.OK);

		HttpResult<PaymentResponse> paymentResult = getPayment(host, studentToken, initiation.paymentId());
		assertThat(paymentResult.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(paymentResult.getBody().data().status()).isEqualTo(PaymentStatus.CONFIRMED);
		assertThat(paymentResult.getBody().data().confirmedAt()).isNotNull();

		HttpResult<OrderPaymentStatusResponse> statusResult = getOrderPaymentStatus(host, studentToken, order.id());
		assertThat(statusResult.getBody().data().status()).isEqualTo(PaymentStatus.CONFIRMED);

		Long ledgerCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM ledger_entry WHERE tenant_id = ? AND payment_id = ? AND entry_type = 'PAYMENT_CONFIRMED'",
				Long.class, tenant.getId(), initiation.paymentId());
		assertThat(ledgerCount).isEqualTo(1L);

		Long enrollmentCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM enrollment WHERE tenant_id = ? AND student_id = ? AND course_id = ? "
						+ "AND activating_payment_id = ? AND status = 'ACTIVE'",
				Long.class, tenant.getId(), student.getId(), course.id(), initiation.paymentId());
		assertThat(enrollmentCount).isEqualTo(1L);
	}

	@Test
	void aDuplicateWebhookDeliveryForTheSameGatewayReferenceIsIdempotent() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("pay-idem"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser student = seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug("pay-idem"), teacher.getId(), CourseStatus.PUBLIC));
		OrderResponse order = createOrderOrFail(host, studentToken, course.id());
		PaymentInitiationResponse initiation = initiatePaymentOrFail(host, studentToken, order.id());

		sendPaymentWebhook(initiation.gatewayReference(), true);
		HttpResult<Void> secondDelivery = sendPaymentWebhook(initiation.gatewayReference(), true);

		assertThat(secondDelivery.getStatusCode()).isEqualTo(HttpStatus.OK);
		Long ledgerCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM ledger_entry WHERE tenant_id = ? AND payment_id = ?", Long.class, tenant.getId(),
				initiation.paymentId());
		assertThat(ledgerCount).isEqualTo(1L);
		Long enrollmentCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM enrollment WHERE tenant_id = ? AND student_id = ? AND course_id = ?", Long.class,
				tenant.getId(), student.getId(), course.id());
		assertThat(enrollmentCount).isEqualTo(1L);
	}

	@Test
	void aRejectingWebhookLeavesThePaymentRejectedAndNeverActivatesEnrollment() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("pay-reject"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug("pay-reject"), teacher.getId(), CourseStatus.PUBLIC));
		OrderResponse order = createOrderOrFail(host, studentToken, course.id());
		PaymentInitiationResponse initiation = initiatePaymentOrFail(host, studentToken, order.id());

		sendPaymentWebhook(initiation.gatewayReference(), false);

		HttpResult<PaymentResponse> paymentResult = getPayment(host, studentToken, initiation.paymentId());
		assertThat(paymentResult.getBody().data().status()).isEqualTo(PaymentStatus.REJECTED);
		Long enrollmentCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM enrollment WHERE tenant_id = ?", Long.class, tenant.getId());
		assertThat(enrollmentCount).isEqualTo(0L);
	}

	@Test
	void anUnsignedWebhookIsRejectedAndCreatesNoStateChange() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("pay-unsigned"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug("pay-unsigned"), teacher.getId(), CourseStatus.PUBLIC));
		OrderResponse order = createOrderOrFail(host, studentToken, course.id());
		PaymentInitiationResponse initiation = initiatePaymentOrFail(host, studentToken, order.id());

		HttpResult<Void> unsignedResult = sendRawPaymentWebhook(webhookBody(initiation.gatewayReference(), true), null);

		assertThat(unsignedResult.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		HttpResult<PaymentResponse> paymentResult = getPayment(host, studentToken, initiation.paymentId());
		assertThat(paymentResult.getBody().data().status()).isEqualTo(PaymentStatus.PENDING);
	}

	@Test
	void refundingAConfirmedPaymentWritesAReversingLedgerEntryAndNeverMutatesPaymentStatus() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("pay-refund"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug("pay-refund"), teacher.getId(), CourseStatus.PUBLIC));
		OrderResponse order = createOrderOrFail(host, studentToken, course.id());
		PaymentInitiationResponse initiation = initiatePaymentOrFail(host, studentToken, order.id());
		sendPaymentWebhook(initiation.gatewayReference(), true);

		HttpResult<com.lms.paymentmanagement.payment.web.dto.RefundResponse> refundResult = createRefund(host,
				adminToken, initiation.paymentId(), new BigDecimal("10.00"), "Partial refund requested by student");

		assertThat(refundResult.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		HttpResult<PaymentResponse> paymentAfterRefund = getPayment(host, studentToken, initiation.paymentId());
		assertThat(paymentAfterRefund.getBody().data().status()).isEqualTo(PaymentStatus.CONFIRMED);
		Long refundLedgerCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM ledger_entry WHERE tenant_id = ? AND payment_id = ? AND entry_type = 'REFUND' "
						+ "AND reverses_entry_id IS NOT NULL",
				Long.class, tenant.getId(), initiation.paymentId());
		assertThat(refundLedgerCount).isEqualTo(1L);
	}

	@Test
	void studentCallingTheRefundEndpointOnTheirOwnPaymentIsRejected() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("pay-refund-student"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug("pay-refund-student"), teacher.getId(),
						CourseStatus.PUBLIC));
		OrderResponse order = createOrderOrFail(host, studentToken, course.id());
		PaymentInitiationResponse initiation = initiatePaymentOrFail(host, studentToken, order.id());
		sendPaymentWebhook(initiation.gatewayReference(), true);

		HttpResult<com.lms.paymentmanagement.payment.web.dto.RefundResponse> refundResult = createRefund(host,
				studentToken, initiation.paymentId(), new BigDecimal("10.00"), "I want my money back");

		assertThat(refundResult.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void crossTenantOrderReadReturns404NeverTenantAsData() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("pay-cross-a"));
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("pay-cross-b"));
		seedTenantUser(tenantA.getId(), "admin-a@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacherA = seedTenantUser(tenantA.getId(), "teacher-a@example.test", RAW_PASSWORD, Role.TEACHER);
		seedActiveStudent(tenantA.getId(), "student-a@example.test");
		seedActiveStudent(tenantB.getId(), "student-b@example.test");
		String hostA = hostFor(tenantA.getSubdomain());
		String hostB = hostFor(tenantB.getSubdomain());
		String adminTokenA = loginAndGetToken(hostA, "admin-a@example.test");
		String studentTokenA = loginAndGetToken(hostA, "student-a@example.test");
		String studentTokenB = loginAndGetToken(hostB, "student-b@example.test");
		CourseResponse courseA = createCourseOrFail(hostA, adminTokenA,
				newCourseRequest(uniqueSlug("pay-cross"), teacherA.getId(),
						CourseStatus.PUBLIC));
		OrderResponse orderA = createOrderOrFail(hostA, studentTokenA, courseA.id());

		HttpResult<OrderResponse> crossResult = getOrder(hostB, studentTokenB, orderA.id());

		assertThat(crossResult.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void anotherStudentInTheSameTenantCannotReadSomeoneElsesOrder() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("pay-same-tenant"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		seedActiveStudent(tenant.getId(), "student-owner@example.test");
		seedActiveStudent(tenant.getId(), "student-other@example.test");
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String ownerToken = loginAndGetToken(host, "student-owner@example.test");
		String otherToken = loginAndGetToken(host, "student-other@example.test");
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug("pay-same-tenant"), teacher.getId(),
						CourseStatus.PUBLIC));
		OrderResponse order = createOrderOrFail(host, ownerToken, course.id());

		HttpResult<OrderResponse> result = getOrder(host, otherToken, order.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void financeStaffCanViewAnyOrderInTheirTenant() {
		// Renamed from financeStaffCanViewAnyOrderInTheirTenantButAStudentSupportRoleCanOnlyView
		// (per MVP-010 review item 8) - the previous name promised Student
		// Support coverage that the body never actually asserted; the real
		// Student Support/Read-only Auditor refund-rejection assertions now
		// live in the dedicated tests below.
		Tenant tenant = seedActiveTenant(uniqueSubdomain("pay-staff-view"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		seedActiveStudent(tenant.getId(), "student@example.test");
		seedTenantUser(tenant.getId(), "finance@example.test", RAW_PASSWORD, Role.FINANCE_STAFF);
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");
		String financeToken = loginAndGetToken(host, "finance@example.test");
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug("pay-staff-view"), teacher.getId(),
						CourseStatus.PUBLIC));
		OrderResponse order = createOrderOrFail(host, studentToken, course.id());

		HttpResult<OrderResponse> financeResult = getOrder(host, financeToken, order.id());

		assertThat(financeResult.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void studentSupportHoldsViewOnlyOnPaymentsSlipsAndIsRejectedFromCreatingARefund() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("pay-refund-support"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		seedActiveStudent(tenant.getId(), "student@example.test");
		seedTenantUser(tenant.getId(), "support@example.test", RAW_PASSWORD, Role.STUDENT_SUPPORT);
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");
		String supportToken = loginAndGetToken(host, "support@example.test");
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug("pay-refund-support"), teacher.getId(), CourseStatus.PUBLIC));
		OrderResponse order = createOrderOrFail(host, studentToken, course.id());
		PaymentInitiationResponse initiation = initiatePaymentOrFail(host, studentToken, order.id());
		sendPaymentWebhook(initiation.gatewayReference(), true);

		// Sanity: Student Support genuinely holds VIEW (can read the payment)
		// - the refund rejection below is specifically about APPROVE, not a
		// blanket permission-denial.
		HttpResult<PaymentResponse> viewResult = getPayment(host, supportToken, initiation.paymentId());
		assertThat(viewResult.getStatusCode()).isEqualTo(HttpStatus.OK);

		HttpResult<com.lms.paymentmanagement.payment.web.dto.RefundResponse> refundResult = createRefund(host,
				supportToken, initiation.paymentId(), new BigDecimal("10.00"), "Student Support attempting a refund");

		assertThat(refundResult.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		Long refundCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM payment_refund WHERE original_payment_id = ?", Long.class,
				initiation.paymentId());
		assertThat(refundCount).isEqualTo(0L);
	}

	@Test
	void readOnlyAuditorHoldsViewOnlyOnPaymentsSlipsAndIsRejectedFromCreatingARefund() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("pay-refund-auditor"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		seedActiveStudent(tenant.getId(), "student@example.test");
		seedTenantUser(tenant.getId(), "auditor@example.test", RAW_PASSWORD, Role.READ_ONLY_AUDITOR);
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");
		String auditorToken = loginAndGetToken(host, "auditor@example.test");
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug("pay-refund-auditor"), teacher.getId(), CourseStatus.PUBLIC));
		OrderResponse order = createOrderOrFail(host, studentToken, course.id());
		PaymentInitiationResponse initiation = initiatePaymentOrFail(host, studentToken, order.id());
		sendPaymentWebhook(initiation.gatewayReference(), true);

		HttpResult<PaymentResponse> viewResult = getPayment(host, auditorToken, initiation.paymentId());
		assertThat(viewResult.getStatusCode()).isEqualTo(HttpStatus.OK);

		HttpResult<com.lms.paymentmanagement.payment.web.dto.RefundResponse> refundResult = createRefund(host,
				auditorToken, initiation.paymentId(), new BigDecimal("10.00"), "Auditor attempting a refund");

		assertThat(refundResult.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		Long refundCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM payment_refund WHERE original_payment_id = ?", Long.class,
				initiation.paymentId());
		assertThat(refundCount).isEqualTo(0L);
	}

	@Test
	void aRefundRequestExceedingTheRemainingRefundableBalanceIsRejectedWithZeroSideEffects() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("pay-refund-exceed"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug("pay-refund-exceed"), teacher.getId(), CourseStatus.PUBLIC));
		OrderResponse order = createOrderOrFail(host, studentToken, course.id());
		PaymentInitiationResponse initiation = initiatePaymentOrFail(host, studentToken, order.id());
		sendPaymentWebhook(initiation.gatewayReference(), true);
		// Course price is 99.99 (CourseManagementTestSupport#newCourseRequest) -
		// a first, partial refund leaves an 89.99 remainder.
		HttpResult<com.lms.paymentmanagement.payment.web.dto.RefundResponse> firstRefund = createRefund(host,
				adminToken, initiation.paymentId(), new BigDecimal("10.00"), "First, legitimate partial refund");
		assertThat(firstRefund.getStatusCode()).isEqualTo(HttpStatus.CREATED);

		HttpResult<com.lms.paymentmanagement.payment.web.dto.RefundResponse> secondRefund = createRefund(host,
				adminToken, initiation.paymentId(), new BigDecimal("90.00"),
				"Second refund attempting to exceed the remainder");

		assertThat(secondRefund.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		Long refundCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM payment_refund WHERE original_payment_id = ?", Long.class,
				initiation.paymentId());
		assertThat(refundCount).isEqualTo(1L);
		Long refundLedgerCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM ledger_entry WHERE payment_id = ? AND entry_type = 'REFUND'", Long.class,
				initiation.paymentId());
		assertThat(refundLedgerCount).isEqualTo(1L);
	}

	@Test
	void resubmittingARefundWithTheSameIdempotencyKeyReturnsTheOriginalRefundNotADuplicate() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("pay-refund-idem"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug("pay-refund-idem"), teacher.getId(), CourseStatus.PUBLIC));
		OrderResponse order = createOrderOrFail(host, studentToken, course.id());
		PaymentInitiationResponse initiation = initiatePaymentOrFail(host, studentToken, order.id());
		sendPaymentWebhook(initiation.gatewayReference(), true);
		UUID idempotencyKey = UUID.randomUUID();

		HttpResult<com.lms.paymentmanagement.payment.web.dto.RefundResponse> first = createRefund(host, adminToken,
				initiation.paymentId(), new BigDecimal("15.00"), "Student requested a partial refund", idempotencyKey);
		assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		UUID firstRefundId = first.getBody().data().id();

		HttpResult<com.lms.paymentmanagement.payment.web.dto.RefundResponse> second = createRefund(host, adminToken,
				initiation.paymentId(), new BigDecimal("15.00"), "Student requested a partial refund", idempotencyKey);

		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(second.getBody().data().id()).isEqualTo(firstRefundId);
		Long refundRowCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM payment_refund WHERE original_payment_id = ?", Long.class,
				initiation.paymentId());
		assertThat(refundRowCount).isEqualTo(1L);
		Long refundLedgerCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM ledger_entry WHERE payment_id = ? AND entry_type = 'REFUND'", Long.class,
				initiation.paymentId());
		assertThat(refundLedgerCount).isEqualTo(1L);
	}

	@Test
	void spoofedPriceAndTenantIdFieldsInTheRawOrderCreateRequestBodyAreSilentlyDroppedNeverTrusted() {
		// HTTP-level counterpart to OrderCreateRequestValidationTest (which
		// only proves via reflection that the DTO has no price/tenantId
		// field) - posts a raw JSON body carrying extra price/amount/tenantId
		// fields a malicious client might add, and asserts the created
		// order's real amount/tenant match the server-resolved course
		// price/session tenant, never the spoofed values.
		Tenant tenant = seedActiveTenant(uniqueSubdomain("pay-spoof-http"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug("pay-spoof-http"), teacher.getId(), CourseStatus.PUBLIC));
		UUID spoofedForeignTenantId = UUID.randomUUID();
		String rawBody = "{\"courseId\":\"" + course.id() + "\",\"price\":0.01,\"amount\":0.01,\"tenantId\":\""
				+ spoofedForeignTenantId + "\",\"studentId\":\"" + UUID.randomUUID() + "\"}";

		HttpResult<OrderResponse> result = createOrderWithRawBody(host, studentToken, rawBody);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		OrderResponse created = result.getBody().data();
		assertThat(created.amount()).isEqualByComparingTo(course.price());
		assertThat(created.amount()).isNotEqualByComparingTo(new BigDecimal("0.01"));
		Long orderRowInRealTenant = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM student_order WHERE id = ? AND tenant_id = ?", Long.class, created.id(),
				tenant.getId());
		assertThat(orderRowInRealTenant).isEqualTo(1L);
		Long orderRowInSpoofedTenant = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM student_order WHERE id = ? AND tenant_id = ?", Long.class, created.id(),
				spoofedForeignTenantId);
		assertThat(orderRowInSpoofedTenant).isEqualTo(0L);
	}

	@Test
	void ledgerHistoryIsNeverReportedAsPaidWhenAConfirmedPaymentHasNoLedgerEntry() {
		// Structural regression guard for plan §18 test 9: seeds a CONFIRMED
		// payment directly (bypassing the normal confirm flow that always
		// writes a ledger entry in the same transaction) and asserts the
		// student's ledger-derived history correctly reports it as empty -
		// proving the read path is genuinely ledger-derived, not
		// payment.status-derived.
		Tenant tenant = seedActiveTenant(uniqueSubdomain("pay-ledger-derived"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser student = seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug("pay-ledger-derived"), teacher.getId(),
						CourseStatus.PUBLIC));
		OrderResponse order = createOrderOrFail(host, studentToken, course.id());
		UUID paymentId = UUID.randomUUID();
		jdbcTemplate.update(
				"INSERT INTO payment (id, tenant_id, order_id, amount, currency, status, gateway_reference, "
						+ "confirmed_at, created_at, updated_at) VALUES (?, ?, ?, ?, 'USD', 'CONFIRMED', ?, now(), now(), now())",
				paymentId, tenant.getId(), order.id(), order.amount(), "DIRECT-INSERT-" + paymentId);

		HttpResult<java.util.List<com.lms.ledgersettlementmanagement.web.dto.LedgerHistoryEntryResponse>> historyResult = getLedgerHistory(
				host, studentToken);

		assertThat(historyResult.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(historyResult.getBody().data()).isEmpty();
	}

}
