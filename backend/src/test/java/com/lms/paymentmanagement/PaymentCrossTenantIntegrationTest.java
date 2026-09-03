package com.lms.paymentmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.common.api.PageResponse;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.integrationmanagement.gateway.WebhookSignatureVerifier;
import com.lms.ledgersettlementmanagement.web.dto.LedgerHistoryEntryResponse;
import com.lms.paymentmanagement.order.web.dto.OrderPaymentStatusResponse;
import com.lms.paymentmanagement.order.web.dto.OrderResponse;
import com.lms.paymentmanagement.order.web.dto.PaymentInitiationResponse;
import com.lms.paymentmanagement.payment.web.dto.PaymentResponse;
import com.lms.paymentmanagement.payment.web.dto.RefundResponse;
import com.lms.tenantmanagement.domain.Tenant;
import com.lms.usermanagement.student.web.dto.StudentCreateRequest;
import com.lms.usermanagement.student.web.dto.StudentResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Closes plan §18's "Mandatory cross-tenant negative tests" list for the
 * payment/refund/ledger-dashboard surfaces not already covered by {@code
 * PaymentAndLedgerIntegrationTest} (which covers order read cross-tenant
 * already): {@code GET /api/v1/payments/{id}}, {@code POST
 * /api/v1/payments/{id}/refunds}, {@code GET /api/v1/payments/{id}/refunds},
 * and {@code GET /api/v1/ledger/dashboard}. Every case proves 403/404 - never
 * a 200 with the other tenant's data, and never a side-effecting row created
 * on a rejected cross-tenant mutation attempt. Also closes item 8 (an
 * incorrectly-signed, present webhook signature is rejected before any state
 * change - {@code PaymentAndLedgerIntegrationTest} already covers a wholly
 * MISSING signature) and the dashboard half of item 9 (ledger-derived, not
 * {@code payment.status}-derived - the Payment History/student half is
 * already covered there too).
 *
 * <p>Also closes the MVP-015 (Tenant Admin Dashboard) plan's mandatory
 * combined-fixture cross-tenant test (plan §18; {@code
 * docs/requirements/open-decisions.md} §19) - see {@link
 * #tenantAdminOverviewComposedStudentCourseAndLedgerCountsNeverIncludeAnotherTenantsRows}.
 */
class PaymentCrossTenantIntegrationTest extends PaymentManagementTestSupport {

	@Test
	void crossTenantPaymentReadReturns404NeverTenantAsData() {
		Fixture a = seedTenantWithConfirmedPayment("pay-xt-payment-a");
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("pay-xt-payment-b"));
		TenantUser studentB = seedActiveStudent(tenantB.getId(), "student-b@example.test");
		String hostB = hostFor(tenantB.getSubdomain());
		String studentTokenB = loginAndGetToken(hostB, "student-b@example.test");

		HttpResult<PaymentResponse> result = getPayment(hostB, studentTokenB, a.initiation.paymentId());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void crossTenantRefundCreationIsRejectedWithZeroSideEffects() {
		Fixture a = seedTenantWithConfirmedPayment("pay-xt-refund-create-a");
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("pay-xt-refund-create-b"));
		seedTenantUser(tenantB.getId(), "admin-b@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String hostB = hostFor(tenantB.getSubdomain());
		String adminTokenB = loginAndGetToken(hostB, "admin-b@example.test");

		HttpResult<RefundResponse> result = createRefund(hostB, adminTokenB, a.initiation.paymentId(),
				new BigDecimal("5.00"), "Attempted cross-tenant refund");

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		Long refundCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM payment_refund WHERE original_payment_id = ?", Long.class,
				a.initiation.paymentId());
		assertThat(refundCount).isEqualTo(0L);
		Long ledgerRefundCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM ledger_entry WHERE payment_id = ? AND entry_type = 'REFUND'", Long.class,
				a.initiation.paymentId());
		assertThat(ledgerRefundCount).isEqualTo(0L);
		// The original payment is still exactly CONFIRMED, untouched.
		String status = jdbcTemplate.queryForObject("SELECT status FROM payment WHERE id = ?", String.class,
				a.initiation.paymentId());
		assertThat(status).isEqualTo("CONFIRMED");
	}

	@Test
	void crossTenantRefundListingReturns404NeverTenantAsData() {
		Fixture a = seedTenantWithConfirmedPayment("pay-xt-refund-list-a");
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("pay-xt-refund-list-b"));
		seedTenantUser(tenantB.getId(), "admin-b@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String hostB = hostFor(tenantB.getSubdomain());
		String adminTokenB = loginAndGetToken(hostB, "admin-b@example.test");
		createRefund(a.host, a.adminToken, a.initiation.paymentId(), new BigDecimal("5.00"), "Legit refund");

		HttpResult<List<RefundResponse>> result = listRefunds(hostB, adminTokenB, a.initiation.paymentId());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void ledgerDashboardNeverLeaksAnotherTenantsEntries() {
		Fixture a = seedTenantWithConfirmedPayment("pay-xt-dashboard-a");
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("pay-xt-dashboard-b"));
		seedTenantUser(tenantB.getId(), "admin-b@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String hostB = hostFor(tenantB.getSubdomain());
		String adminTokenB = loginAndGetToken(hostB, "admin-b@example.test");

		HttpResult<PageResponse<LedgerHistoryEntryResponse>> dashboardB = getLedgerDashboard(hostB, adminTokenB);
		assertThat(dashboardB.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(dashboardB.getBody().data().content()).isEmpty();

		// Sanity: tenant A's own dashboard DOES see its own entry, proving
		// the emptiness above is tenant isolation, not a broken dashboard.
		HttpResult<PageResponse<LedgerHistoryEntryResponse>> dashboardA = getLedgerDashboard(a.host, a.adminToken);
		assertThat(dashboardA.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(dashboardA.getBody().data().content()).extracting(LedgerHistoryEntryResponse::paymentId)
			.contains(a.initiation.paymentId());
	}

	/**
	 * Closes the MVP-015 (Tenant Admin Dashboard) plan's own explicitly
	 * flagged gap (plan §14/§18; {@code docs/requirements/open-decisions.md}
	 * §19): a mandatory cross-tenant negative test proving the *combined*
	 * three-domain shape the Tenant Admin Overview composes client-side
	 * (student count + course total/published counts + ledger-entries-
	 * recorded count) never includes another tenant's rows - not merely each
	 * of the three underlying endpoints' own already-existing independent
	 * cross-tenant tests ({@link #ledgerDashboardNeverLeaksAnotherTenantsEntries}
	 * here, plus {@code StudentManagementIntegrationTest}'s and {@code
	 * CourseManagementIntegrationTest}'s equivalents), since a bug that only
	 * manifests when all three reads are composed together on one screen
	 * would not be caught by any of those alone.
	 */
	@Test
	void tenantAdminOverviewComposedStudentCourseAndLedgerCountsNeverIncludeAnotherTenantsRows() {
		Fixture a = seedTenantWithConfirmedPayment("pay-xt-overview-a");
		// Two API-created students (visible to GET /api/v1/students, unlike
		// the fixture's own seedActiveStudent-only checkout student, which has
		// no student_profile row and never appears in that list) and a second
		// published course in tenant A, so its counts are distinguishably
		// larger than tenant B's below - proving tenant B's smaller numbers
		// are isolation, not coincidental emptiness.
		createStudentOrFail(a.host, a.adminToken, "Overview Student A1", "overview-student-a1@example.test");
		createStudentOrFail(a.host, a.adminToken, "Overview Student A2", "overview-student-a2@example.test");
		TenantUser teacherA2 = seedTenantUser(a.tenant().getId(), "teacher2-a@example.test", RAW_PASSWORD,
				Role.TEACHER);
		createCourseOrFail(a.host, a.adminToken,
				newCourseRequest(uniqueSlug("pay-xt-overview-a-2"), teacherA2.getId(), CourseStatus.PUBLIC));

		Fixture b = seedTenantWithConfirmedPayment("pay-xt-overview-b");
		createStudentOrFail(b.host, b.adminToken, "Overview Student B1", "overview-student-b1@example.test");

		// The exact three-read shape the Tenant Admin Overview
		// (MVP-015/TADASH-1) composes client-side, fired as tenant B's admin.
		HttpResult<List<StudentResponse>> studentsB = listStudents(b.host, b.adminToken);
		HttpResult<PageResponse<CourseResponse>> coursesTotalB = listCourses(b.host, b.adminToken, "size=1");
		HttpResult<PageResponse<CourseResponse>> coursesPublishedB = listCourses(b.host, b.adminToken,
				"status=PUBLIC&size=1");
		HttpResult<PageResponse<LedgerHistoryEntryResponse>> ledgerB = getLedgerDashboard(b.host, b.adminToken);

		assertThat(studentsB.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(studentsB.getBody().data()).extracting(StudentResponse::email)
			.containsExactly("overview-student-b1@example.test");
		assertThat(coursesTotalB.getBody().data().totalElements()).isEqualTo(1L);
		assertThat(coursesPublishedB.getBody().data().totalElements()).isEqualTo(1L);
		assertThat(ledgerB.getBody().data().totalElements()).isEqualTo(1L);
		assertThat(ledgerB.getBody().data().content()).extracting(LedgerHistoryEntryResponse::paymentId)
			.containsExactly(b.initiation.paymentId());

		// Sanity: tenant A's own composed reads DO see its own larger counts,
		// proving tenant B's numbers above are isolation, not a broken read.
		HttpResult<List<StudentResponse>> studentsA = listStudents(a.host, a.adminToken);
		HttpResult<PageResponse<CourseResponse>> coursesTotalA = listCourses(a.host, a.adminToken, "size=1");
		assertThat(studentsA.getBody().data()).hasSize(2);
		assertThat(coursesTotalA.getBody().data().totalElements()).isEqualTo(2L);
	}

	@Test
	void ledgerDashboardIsNeverReportedAsPaidWhenAConfirmedPaymentHasNoLedgerEntry() {
		// Dashboard-side counterpart to
		// PaymentAndLedgerIntegrationTest#ledgerHistoryIsNeverReportedAsPaidWhenAConfirmedPaymentHasNoLedgerEntry
		// (which only covers the student Payment History read path) - plan
		// §18 item 9 names BOTH Dashboard and History explicitly.
		Tenant tenant = seedActiveTenant(uniqueSubdomain("pay-dash-derived"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		seedActiveStudent(tenant.getId(), "student@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug("pay-dash-derived"), teacher.getId(), CourseStatus.PUBLIC));
		OrderResponse order = createOrderOrFail(host, studentToken, course.id());
		UUID paymentId = UUID.randomUUID();
		jdbcTemplate.update(
				"INSERT INTO payment (id, tenant_id, order_id, amount, currency, status, gateway_reference, "
						+ "confirmed_at, created_at, updated_at) VALUES (?, ?, ?, ?, 'USD', 'CONFIRMED', ?, now(), now(), now())",
				paymentId, tenant.getId(), order.id(), order.amount(), "DIRECT-INSERT-DASH-" + paymentId);

		HttpResult<PageResponse<LedgerHistoryEntryResponse>> dashboard = getLedgerDashboard(host, adminToken);

		assertThat(dashboard.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(dashboard.getBody().data().content())
			.noneMatch(entry -> paymentId.equals(entry.paymentId()));
	}

	@Test
	void aWebhookWithATamperedSignatureIsRejectedAndCreatesNoStateChange() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("pay-tampered-sig"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug("pay-tampered-sig"), teacher.getId(), CourseStatus.PUBLIC));
		OrderResponse order = createOrderOrFail(host, studentToken, course.id());
		PaymentInitiationResponse initiation = initiatePaymentOrFail(host, studentToken, order.id());
		String body = webhookBody(initiation.gatewayReference(), true);
		// A well-formed, hex-decodable signature that simply was not
		// computed with the correct secret/body - distinct from a wholly
		// missing signature (already covered elsewhere).
		String wrongSignature = WebhookSignatureVerifier.sign(body, "a-completely-different-secret-value");

		HttpResult<Void> result = sendRawPaymentWebhook(body, wrongSignature);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		HttpResult<PaymentResponse> paymentResult = getPayment(host, studentToken, initiation.paymentId());
		assertThat(paymentResult.getBody().data().status())
			.isEqualTo(com.lms.paymentmanagement.payment.domain.PaymentStatus.PENDING);
		Long ledgerCount = jdbcTemplate.queryForObject("SELECT count(*) FROM ledger_entry WHERE payment_id = ?",
				Long.class, initiation.paymentId());
		assertThat(ledgerCount).isEqualTo(0L);
	}

	@Test
	void creatingAnOrderForACourseIdBelongingToAnotherTenantReturns404AndCreatesNoOrder() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("pay-xt-order-create-a"));
		seedTenantUser(tenantA.getId(), "admin-a@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacherA = seedTenantUser(tenantA.getId(), "teacher-a@example.test", RAW_PASSWORD, Role.TEACHER);
		String hostA = hostFor(tenantA.getSubdomain());
		String adminTokenA = loginAndGetToken(hostA, "admin-a@example.test");
		CourseResponse courseA = createCourseOrFail(hostA, adminTokenA,
				newCourseRequest(uniqueSlug("pay-xt-order-create-a"), teacherA.getId(), CourseStatus.PUBLIC));

		Tenant tenantB = seedActiveTenant(uniqueSubdomain("pay-xt-order-create-b"));
		seedActiveStudent(tenantB.getId(), "student-b@example.test");
		String hostB = hostFor(tenantB.getSubdomain());
		String studentTokenB = loginAndGetToken(hostB, "student-b@example.test");

		HttpResult<OrderResponse> result = createOrder(hostB, studentTokenB, courseA.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		Long orderCountInTenantB = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM student_order WHERE tenant_id = ? AND course_id = ?", Long.class,
				tenantB.getId(), courseA.id());
		assertThat(orderCountInTenantB).isEqualTo(0L);
		Long orderCountAnywhereForCourseA = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM student_order WHERE course_id = ?", Long.class, courseA.id());
		assertThat(orderCountAnywhereForCourseA).isEqualTo(0L);
	}

	@Test
	void crossTenantOrderPaymentStatusReadReturns404NeverTenantAsData() {
		Fixture a = seedTenantWithConfirmedPayment("pay-xt-pstatus-a");
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("pay-xt-pstatus-b"));
		seedActiveStudent(tenantB.getId(), "student-b@example.test");
		String hostB = hostFor(tenantB.getSubdomain());
		String studentTokenB = loginAndGetToken(hostB, "student-b@example.test");

		HttpResult<OrderPaymentStatusResponse> result = getOrderPaymentStatus(hostB, studentTokenB, a.order().id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	/**
	 * {@code GET /api/v1/students} - not exposed by any shared support class
	 * this class already extends, so declared locally, mirroring {@code
	 * StudentManagementIntegrationTest}'s identically-shaped private helper.
	 */
	private HttpResult<List<StudentResponse>> listStudents(String host, String token) {
		MockHttpServletRequestBuilder builder = get("/api/v1/students");
		return parseList(perform(authenticated(builder, host, token)), StudentResponse.class);
	}

	/**
	 * {@code POST /api/v1/students} - unlike {@link #seedActiveStudent}
	 * (a direct {@code tenant_user}-only DB insert this class's fixtures use
	 * purely to obtain a checkout-capable student login), this goes through
	 * the real endpoint so the resulting row also has a {@code
	 * student_profile} and therefore actually appears in {@code GET
	 * /api/v1/students} - required for the student-count assertions in
	 * {@link #tenantAdminOverviewComposedStudentCourseAndLedgerCountsNeverIncludeAnotherTenantsRows}.
	 */
	private StudentResponse createStudentOrFail(String host, String token, String name, String email) {
		MockHttpServletRequestBuilder builder = post("/api/v1/students").contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(new StudentCreateRequest(name, email, RAW_PASSWORD)));
		HttpResult<StudentResponse> result = parseSingle(perform(authenticated(builder, host, token)),
				StudentResponse.class);
		if (result.getStatusCode() != HttpStatus.CREATED) {
			throw new IllegalStateException("Student creation failed: " + result.getStatusCode() + " " + result.getBody());
		}
		return result.getBody().data();
	}

	// ------------------------------------------------------------------

	private Fixture seedTenantWithConfirmedPayment(String prefix) {
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
