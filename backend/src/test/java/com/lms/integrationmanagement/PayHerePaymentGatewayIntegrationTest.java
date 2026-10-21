package com.lms.integrationmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.enrollmentmanagement.EnrollmentManagementTestSupport;
import com.lms.enrollmentmanagement.api.EnrollmentAccessStateType;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.integrationmanagement.gateway.PayHereSignatures;
import com.lms.integrationmanagement.service.PaymentGatewayAccountView;
import com.lms.integrationmanagement.web.dto.CheckoutPaymentOptionsResponse;
import com.lms.paymentmanagement.order.web.dto.OrderResponse;
import com.lms.paymentmanagement.order.web.dto.PaymentInitiationResponse;
import com.lms.tenantmanagement.domain.Tenant;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * PAY-GW-01 / ADR-017 end to end over HTTP against Testcontainers Postgres:
 * tenant PayHere account management, checkout form signing, verified
 * notifications (confirm, reject, chargeback), cross-tenant isolation and the
 * Platform Admin switch-off. Notifications are synthetic but signed exactly as
 * PayHere documents.
 */
class PayHerePaymentGatewayIntegrationTest extends EnrollmentManagementTestSupport {

	private static final String SECRET = "tenant-merchant-secret-value";

	private record Fixture(Tenant tenant, String host, String adminToken, String studentToken, UUID courseId,
			String merchantId, String secret) {
	}

	// ------------------------------------------------------------------
	// Account management
	// ------------------------------------------------------------------

	@Test
	void tenantAdminSavesAccountAndTheSecretIsEncryptedNeverReturnedAndNeverAudited() {
		Fixture f = fixture("gw-save", "1211149", SECRET);

		MvcResult raw = perform(json(put("/api/v1/payment-gateway-accounts/payhere"), f.host, f.adminToken,
				Map.of("merchantId", f.merchantId, "merchantSecret", f.secret, "mode", "SANDBOX")));
		HttpResult<PaymentGatewayAccountView> saved = parseSingle(raw, PaymentGatewayAccountView.class);

		assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(saved.getBody().data().merchantIdMasked()).isEqualTo("***1149");
		assertThat(saved.getBody().data().secretConfigured()).isTrue();
		// Test profile configures notify/return URLs + key, so checkout is live.
		assertThat(saved.getBody().data().checkoutReady()).isTrue();
		assertThat(raw.getResponse().getContentAsByteArray()).asString().doesNotContain(f.secret);
		String stored = jdbcTemplate.queryForObject(
				"SELECT merchant_secret_ciphertext FROM tenant_payment_gateway_account WHERE id = ?", String.class,
				saved.getBody().data().id());
		assertThat(stored).startsWith("v1:").doesNotContain(f.secret);
		String auditMetadata = jdbcTemplate.queryForObject(
				"SELECT metadata::text FROM audit_log WHERE action = 'payment_gateway.account_saved' AND target_id = ?",
				String.class, saved.getBody().data().id());
		assertThat(auditMetadata).doesNotContain(f.secret);
	}

	@Test
	void financeStaffCanViewButNotSaveAndAStudentCannotView() {
		Fixture f = fixture("gw-roles", "1211150", SECRET);
		seedTenantUser(f.tenant.getId(), "finance@example.test", RAW_PASSWORD, Role.FINANCE_STAFF);
		String financeToken = loginAndGetToken(f.host, "finance@example.test");
		saveAccountOrFail(f);

		assertThat(listAccounts(f.host, financeToken).getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(perform(json(put("/api/v1/payment-gateway-accounts/payhere"), f.host, financeToken,
				Map.of("merchantId", "9999999", "merchantSecret", "x", "mode", "LIVE")))
			.getResponse()
			.getStatus()).isEqualTo(403);
		assertThat(listAccounts(f.host, f.studentToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void checkoutOptionsOfferOnlinePaymentOnlyWhileTheTenantHasAnActiveAccount() {
		Fixture f = fixture("gw-options", "1211151", SECRET);
		assertThat(paymentOptions(f).onlinePayment()).isNull();

		saveAccountOrFail(f);

		CheckoutPaymentOptionsResponse options = paymentOptions(f);
		assertThat(options.bankSlip()).isTrue();
		assertThat(options.onlinePayment().provider()).isEqualTo("PAYHERE");
		assertThat(options.onlinePayment().mode()).isEqualTo("SANDBOX");
	}

	// ------------------------------------------------------------------
	// Checkout + notifications
	// ------------------------------------------------------------------

	@Test
	void signedSuccessNotificationConfirmsOnceActivatesEnrollmentAndRecordsTenantCollection() {
		Fixture f = fixture("gw-confirm", "1211152", SECRET);
		saveAccountOrFail(f);
		OrderResponse order = createOrderOrFail(f.host, f.studentToken, f.courseId);

		PaymentInitiationResponse initiation = initiatePaymentOrFail(f.host, f.studentToken, order.id());

		assertThat(initiation.formAction()).isEqualTo("https://sandbox.payhere.lk/pay/checkout");
		Map<String, String> fields = initiation.formFields();
		assertThat(fields.get("order_id")).isEqualTo(initiation.gatewayReference());
		assertThat(fields.get("merchant_id")).isEqualTo(f.merchantId);
		assertThat(fields.get("return_url")).startsWith("https://" + f.tenant.getSubdomain() + ".example.test/");
		assertThat(fields.get("hash")).isEqualTo(PayHereSignatures.checkoutHash(f.merchantId,
				initiation.gatewayReference(), order.amount(), order.currency(), f.secret));
		assertThat(fields.values()).doesNotContain(f.secret);

		assertThat(notify(f, initiation.gatewayReference(), order.amount(), order.currency(), "2", null))
			.isEqualTo(200);
		assertThat(notify(f, initiation.gatewayReference(), order.amount(), order.currency(), "2", null))
			.isEqualTo(200);

		assertThat(paymentStatus(initiation.paymentId())).isEqualTo("CONFIRMED");
		assertThat(jdbcTemplate.queryForObject("SELECT collected_by FROM payment WHERE id = ?", String.class,
				initiation.paymentId())).isEqualTo("TENANT");
		assertThat(ledgerCount(initiation.paymentId(), "PAYMENT_CONFIRMED")).isEqualTo(1);
		assertThat(getAccessState(f.host, f.studentToken, f.courseId).getBody().data().state())
			.isEqualTo(EnrollmentAccessStateType.ACTIVE);
	}

	@Test
	void tamperedOrMismatchedNotificationsAreRejectedWithoutAnyStateChange() {
		Fixture f = fixture("gw-tamper", "1211153", SECRET);
		saveAccountOrFail(f);
		OrderResponse order = createOrderOrFail(f.host, f.studentToken, f.courseId);
		PaymentInitiationResponse initiation = initiatePaymentOrFail(f.host, f.studentToken, order.id());

		// Wrong secret -> bad md5sig.
		assertThat(notify(f.merchantId, "not-the-secret", initiation.gatewayReference(), order.amount(),
				order.currency(), "2", null)).isEqualTo(400);
		// Correctly signed, but for a smaller amount than the stored payment.
		assertThat(notify(f, initiation.gatewayReference(), new BigDecimal("1.00"), order.currency(), "2", null))
			.isEqualTo(400);
		// Unknown reference.
		assertThat(notify(f, "PH-" + UUID.randomUUID(), order.amount(), order.currency(), "2", null)).isEqualTo(400);

		assertThat(paymentStatus(initiation.paymentId())).isEqualTo("PENDING");
		assertThat(ledgerCount(initiation.paymentId(), "PAYMENT_CONFIRMED")).isZero();
	}

	@Test
	void failedNotificationRejectsThePendingPayment() {
		Fixture f = fixture("gw-failed", "1211154", SECRET);
		saveAccountOrFail(f);
		OrderResponse order = createOrderOrFail(f.host, f.studentToken, f.courseId);
		PaymentInitiationResponse initiation = initiatePaymentOrFail(f.host, f.studentToken, order.id());

		assertThat(notify(f, initiation.gatewayReference(), order.amount(), order.currency(), "-2", null))
			.isEqualTo(200);

		assertThat(paymentStatus(initiation.paymentId())).isEqualTo("REJECTED");
	}

	@Test
	void chargebackRecordsARefundReversesTheLedgerAndRevokesAccessExactlyOnce() {
		Fixture f = fixture("gw-chargeback", "1211155", SECRET);
		saveAccountOrFail(f);
		OrderResponse order = createOrderOrFail(f.host, f.studentToken, f.courseId);
		PaymentInitiationResponse initiation = initiatePaymentOrFail(f.host, f.studentToken, order.id());
		notify(f, initiation.gatewayReference(), order.amount(), order.currency(), "2", null);

		assertThat(notify(f, initiation.gatewayReference(), order.amount(), order.currency(), "-3", "320025071234"))
			.isEqualTo(200);
		assertThat(notify(f, initiation.gatewayReference(), order.amount(), order.currency(), "-3", "320025071234"))
			.isEqualTo(200);

		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM payment_refund WHERE original_payment_id = ? AND source = 'CHARGEBACK'",
				Integer.class, initiation.paymentId())).isEqualTo(1);
		assertThat(ledgerCount(initiation.paymentId(), "REFUND")).isEqualTo(1);
		assertThat(paymentStatus(initiation.paymentId())).isEqualTo("CONFIRMED");
		assertThat(jdbcTemplate.queryForObject("SELECT revoke_reason FROM enrollment WHERE activating_payment_id = ?",
				String.class, initiation.paymentId())).isEqualTo("PAYHERE_CHARGEBACK");
		assertThat(getAccessState(f.host, f.studentToken, f.courseId).getBody().data().state())
			.isNotEqualTo(EnrollmentAccessStateType.ACTIVE);
	}

	@Test
	void payHereCheckoutSendsTheStudentsOwnAddressAndName() {
		Fixture f = fixture("gw-address", "1211159", SECRET);
		saveAccountOrFail(f);
		OrderResponse order = createOrderOrFail(f.host, f.studentToken, f.courseId);

		Map<String, String> fields = initiatePaymentOrFail(f.host, f.studentToken, order.id()).formFields();

		assertThat(fields).containsEntry("address", "No. 1, Galle Road")
			.containsEntry("city", "Colombo")
			.containsEntry("first_name", "Saman")
			.containsEntry("last_name", "Perera");
	}

	@Test
	void onlinePaymentIsRefusedWithoutABillingAddressAndNoPaymentRowIsCreated() {
		Fixture f = fixture("gw-no-address", "1211160", SECRET, false);
		saveAccountOrFail(f);
		OrderResponse order = createOrderOrFail(f.host, f.studentToken, f.courseId);

		HttpResult<PaymentInitiationResponse> result = initiatePayment(f.host, f.studentToken, order.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM payment WHERE order_id = ?", Integer.class,
				order.id())).isZero();

		// Once the student adds their address, the same order can be paid online.
		assertThat(putBillingAddress(f.host, f.studentToken, "12 Temple Road", "Kandy")).isEqualTo(200);
		assertThat(initiatePayment(f.host, f.studentToken, order.id()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
	}

	@Test
	void aStudentReadsAndUpdatesOnlyTheirOwnBillingAddressAndBlankValuesAreRejected() {
		Fixture f = fixture("gw-address-rw", "1211161", SECRET, false);

		assertThat(perform(authenticated(get("/api/v1/students/me/billing-address"), f.host, f.studentToken))
			.getResponse()
			.getStatus()).isEqualTo(200);
		assertThat(putBillingAddress(f.host, f.studentToken, " ", "Colombo")).isEqualTo(400);
		assertThat(putBillingAddress(f.host, f.studentToken, "5 Lake Road", "Galle")).isEqualTo(200);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT city FROM student_profile WHERE user_id = (SELECT id FROM tenant_user WHERE tenant_id = ? AND email = 'student@example.test')",
				String.class, f.tenant.getId())).isEqualTo("Galle");
		// Staff cannot use the student self-service endpoint.
		assertThat(perform(authenticated(get("/api/v1/students/me/billing-address"), f.host, f.adminToken))
			.getResponse()
			.getStatus()).isEqualTo(403);
	}

	// ------------------------------------------------------------------
	// Tenant isolation
	// ------------------------------------------------------------------

	@Test
	void aNotificationSignedWithAnotherTenantsAccountIsRejectedAndAccountsNeverLeakAcrossTenants() {
		Fixture tenantA = fixture("gw-cross-a", "1211156", SECRET);
		Fixture tenantB = fixture("gw-cross-b", "1211157", SECRET + "-b");
		PaymentGatewayAccountView accountA = saveAccountOrFail(tenantA);
		saveAccountOrFail(tenantB);
		OrderResponse order = createOrderOrFail(tenantA.host, tenantA.studentToken, tenantA.courseId);
		PaymentInitiationResponse initiation = initiatePaymentOrFail(tenantA.host, tenantA.studentToken, order.id());

		// Tenant B's own merchant id + secret, targeting tenant A's payment.
		assertThat(notify(tenantB, initiation.gatewayReference(), order.amount(), order.currency(), "2", null))
			.isEqualTo(400);
		assertThat(paymentStatus(initiation.paymentId())).isEqualTo("PENDING");

		List<PaymentGatewayAccountView> seenByB = listAccounts(tenantB.host, tenantB.adminToken).getBody().data();
		assertThat(seenByB).extracting(PaymentGatewayAccountView::id).doesNotContain(accountA.id());
	}

	// ------------------------------------------------------------------
	// Platform Admin switch-off
	// ------------------------------------------------------------------

	@Test
	void platformAdminSwitchOffHidesOnlinePaymentAndOnlyAPlatformAdminCanSwitchItBackOn() {
		Fixture f = fixture("gw-platform", "1211158", SECRET);
		saveAccountOrFail(f);
		seedPlatformAdmin("gw-platform-admin@platform.test", RAW_PASSWORD);
		String platformToken = platformAdminLogin(null, "gw-platform-admin@platform.test", RAW_PASSWORD).getBody()
			.data()
			.accessToken();
		String base = "/api/v1/platform-admin/tenants/" + f.tenant.getId() + "/payment-gateway";

		assertThat(perform(json(post(base + "/disable"), null, platformToken, Map.of())).getResponse().getStatus())
			.isEqualTo(400);
		assertThat(perform(json(post(base + "/disable"), null, platformToken, Map.of("reason", "Fraud review")))
			.getResponse()
			.getStatus()).isEqualTo(200);
		assertThat(paymentOptions(f).onlinePayment()).isNull();
		assertThat(perform(json(put("/api/v1/payment-gateway-accounts/payhere"), f.host, f.adminToken,
				Map.of("merchantId", f.merchantId, "mode", "SANDBOX")))
			.getResponse()
			.getStatus()).isEqualTo(409);
		// A tenant admin cannot reach the platform route at all (a tenant token is
		// not a platform-admin credential: 401, or 403 if ever authenticated).
		assertThat(perform(authenticated(post(base + "/enable"), f.host, f.adminToken)).getResponse().getStatus())
			.isIn(401, 403);

		assertThat(perform(authenticated(post(base + "/enable"), null, platformToken)).getResponse().getStatus())
			.isEqualTo(200);
		assertThat(paymentOptions(f).onlinePayment()).isNotNull();
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	private Fixture fixture(String label, String merchantId, String secret) {
		return fixture(label, merchantId, secret, true);
	}

	private Fixture fixture(String label, String merchantId, String secret, boolean withBillingAddress) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain(label));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		UUID studentUserId = seedActiveStudent(tenant.getId(), "student@example.test").getId();
		jdbcTemplate.update("INSERT INTO student_profile (id, tenant_id, user_id, name, created_at, updated_at) "
				+ "VALUES (?, ?, ?, 'Saman Perera', now(), now())", UUID.randomUUID(), tenant.getId(), studentUserId);
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String teacherToken = loginAndGetToken(host, "teacher@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");
		if (withBillingAddress) {
			putBillingAddress(host, studentToken, "No. 1, Galle Road", "Colombo");
		}
		CourseResponse course = createCourseOrFail(host, teacherToken, newCourseRequest(uniqueSlug(label), null));
		publish(host, teacherToken, course.id());
		return new Fixture(tenant, host, adminToken, studentToken, course.id(), merchantId, secret);
	}

	private int putBillingAddress(String host, String studentToken, String addressLine, String city) {
		return perform(json(put("/api/v1/students/me/billing-address"), host, studentToken,
				Map.of("addressLine", addressLine, "city", city))).getResponse().getStatus();
	}

	private PaymentGatewayAccountView saveAccountOrFail(Fixture f) {
		HttpResult<PaymentGatewayAccountView> result = parseSingle(
				perform(json(put("/api/v1/payment-gateway-accounts/payhere"), f.host, f.adminToken,
						Map.of("merchantId", f.merchantId, "merchantSecret", f.secret, "mode", "SANDBOX"))),
				PaymentGatewayAccountView.class);
		if (result.getStatusCode() != HttpStatus.OK) {
			throw new IllegalStateException("Saving gateway account failed: " + result.getStatusCode());
		}
		return result.getBody().data();
	}

	private HttpResult<List<PaymentGatewayAccountView>> listAccounts(String host, String token) {
		return parseList(perform(authenticated(get("/api/v1/payment-gateway-accounts"), host, token)),
				PaymentGatewayAccountView.class);
	}

	private CheckoutPaymentOptionsResponse paymentOptions(Fixture f) {
		return parseSingle(perform(authenticated(get("/api/v1/checkout/payment-options"), f.host, f.studentToken)),
				CheckoutPaymentOptionsResponse.class)
			.getBody()
			.data();
	}

	private int notify(Fixture signer, String reference, BigDecimal amount, String currency, String statusCode,
			String payHerePaymentId) {
		return notify(signer.merchantId, signer.secret, reference, amount, currency, statusCode, payHerePaymentId);
	}

	private int notify(String merchantId, String secret, String reference, BigDecimal amount, String currency,
			String statusCode, String payHerePaymentId) {
		String formattedAmount = PayHereSignatures.formatAmount(amount);
		MockHttpServletRequestBuilder request = post("/api/v1/integrations/webhooks/payhere")
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.param("merchant_id", merchantId)
			.param("order_id", reference)
			.param("payhere_amount", formattedAmount)
			.param("payhere_currency", currency)
			.param("status_code", statusCode)
			.param("md5sig", PayHereSignatures.notificationSignature(merchantId, reference, formattedAmount, currency,
					statusCode, secret));
		if (payHerePaymentId != null) {
			request.param("payment_id", payHerePaymentId);
		}
		return perform(request).getResponse().getStatus();
	}

	private String paymentStatus(UUID paymentId) {
		return jdbcTemplate.queryForObject("SELECT status FROM payment WHERE id = ?", String.class, paymentId);
	}

	private int ledgerCount(UUID paymentId, String entryType) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM ledger_entry WHERE payment_id = ? AND entry_type = ?",
				Integer.class, paymentId, entryType);
	}

	private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, String host, String token,
			Map<String, ?> body) {
		try {
			return authenticated(builder, host, token).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(body));
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

}
