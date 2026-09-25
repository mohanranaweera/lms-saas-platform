package com.lms.paymentmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.lms.common.api.PageResponse;
import com.lms.coursemanagement.course.domain.CoursePricingModel;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.ledgersettlementmanagement.api.PaymentMethod;
import com.lms.ledgersettlementmanagement.api.PaymentOperationalState;
import com.lms.ledgersettlementmanagement.web.dto.CoursePaymentSummaryResponse;
import com.lms.ledgersettlementmanagement.web.dto.LedgerHistoryEntryResponse;
import com.lms.ledgersettlementmanagement.web.dto.OutstandingOrderResponse;
import com.lms.paymentmanagement.order.web.dto.OrderResponse;
import com.lms.paymentmanagement.order.web.dto.PaymentInitiationResponse;
import com.lms.paymentmanagement.slip.web.dto.PaymentSlipResponse;
import com.lms.tenantmanagement.domain.Tenant;
import com.lms.usermanagement.student.web.dto.StudentCreateRequest;
import com.lms.usermanagement.student.web.dto.StudentResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Wave 6 §4/§8 - the extended-ledger-view/new-endpoint test plan: a fixture
 * spanning all six {@link PaymentOperationalState} values and all four
 * {@link PaymentMethod} values, proving {@code GET /api/v1/ledger/dashboard}'s
 * {@code status}/{@code method} filters return the correct subset,
 * {@code GET /api/v1/ledger/outstanding} returns only non-{@code PAID}/{@code
 * REFUNDED} orders, and {@code GET /api/v1/ledger/courses/{courseId}/summary}
 * aggregates match a hand-computed fixture - plus the cross-tenant negative
 * case for the course-summary endpoint.
 */
class LedgerDashboardExtendedViewsIntegrationTest extends SlipTestSupport {

	@Test
	void dashboardStatusAndMethodFiltersReturnTheCorrectSubsetAcrossAllSixStatesAndFourMethods() {
		Fixture fixture = buildComprehensiveFixture("ledger-ext-views");

		// UNPAID/PENDING/UNDER_REVIEW/REJECTED orders have NO ledger_entry
		// row at all (only a CONFIRMED payment writes one) - the
		// ledger-entry-row-based dashboard structurally cannot surface
		// them regardless of the status filter; that is exactly why the
		// new GET /api/v1/ledger/outstanding endpoint exists (see the
		// dedicated outstanding test below), not a gap in this filter.
		assertDashboardFilteredTo(fixture, PaymentOperationalState.UNPAID, null);
		assertDashboardFilteredTo(fixture, PaymentOperationalState.PENDING, null);
		assertDashboardFilteredTo(fixture, PaymentOperationalState.UNDER_REVIEW, null);
		assertDashboardFilteredTo(fixture, PaymentOperationalState.REJECTED, null);
		// REFUNDED has TWO ledger rows for the one order (the original
		// PAYMENT_CONFIRMED entry plus the reversing REFUND entry) - both
		// resolve to the same order-level REFUNDED operationalState.
		assertDashboardFilteredTo(fixture, PaymentOperationalState.REFUNDED, null, fixture.refundedOrder().id(),
				fixture.refundedOrder().id());

		// PAID spans 4 different orders (one per method) - assert the SET,
		// not a single id.
		HttpResult<PageResponse<LedgerHistoryEntryResponse>> paidResult = getLedgerDashboardFiltered(fixture.host(),
				fixture.adminToken(), PaymentOperationalState.PAID, null);
		assertThat(paidResult.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(paidResult.getBody().data().content()).extracting(LedgerHistoryEntryResponse::orderId)
			.containsExactlyInAnyOrder(fixture.gatewayOrder().id(), fixture.freeOrder().id(),
					fixture.staffGrantedOrder().id(), fixture.slipOrder().id());

		assertDashboardFilteredTo(fixture, null, PaymentMethod.FREE, fixture.freeOrder().id());
		assertDashboardFilteredTo(fixture, null, PaymentMethod.STAFF_GRANTED, fixture.staffGrantedOrder().id());
		assertDashboardFilteredTo(fixture, null, PaymentMethod.MANUAL_SLIP, fixture.slipOrder().id());

		// GATEWAY spans both the PAID order (1 ledger row) and the
		// REFUNDED order (2 ledger rows: PAYMENT_CONFIRMED + REFUND) - 3
		// rows total across 2 distinct orders. containsOnly (set
		// membership, ignoring duplicate cardinality) is the correct
		// assertion here, not containsExactlyInAnyOrder.
		HttpResult<PageResponse<LedgerHistoryEntryResponse>> gatewayResult = getLedgerDashboardFiltered(
				fixture.host(), fixture.adminToken(), null, PaymentMethod.GATEWAY);
		assertThat(gatewayResult.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(gatewayResult.getBody().data().content()).extracting(LedgerHistoryEntryResponse::orderId)
			.containsOnly(fixture.gatewayOrder().id(), fixture.refundedOrder().id());

		// Combined status+method filter (AND-combined).
		HttpResult<PageResponse<LedgerHistoryEntryResponse>> combined = getLedgerDashboardFiltered(fixture.host(),
				fixture.adminToken(), PaymentOperationalState.PAID, PaymentMethod.GATEWAY);
		assertThat(combined.getBody().data().content()).extracting(LedgerHistoryEntryResponse::orderId)
			.containsExactly(fixture.gatewayOrder().id());
	}

	@Test
	void dashboardExtendedFieldsAreCorrectlyResolvedForAConfirmedGatewayPayment() {
		Fixture fixture = buildComprehensiveFixture("ledger-ext-fields");

		HttpResult<PageResponse<LedgerHistoryEntryResponse>> result = getLedgerDashboardFiltered(fixture.host(),
				fixture.adminToken(), PaymentOperationalState.PAID, PaymentMethod.GATEWAY);

		LedgerHistoryEntryResponse row = result.getBody().data().content().get(0);
		assertThat(row.courseId()).isEqualTo(fixture.gatewayOrder().courseId());
		assertThat(row.courseTitle()).isNotBlank();
		assertThat(row.operationalState()).isEqualTo(PaymentOperationalState.PAID);
		assertThat(row.method()).isEqualTo(PaymentMethod.GATEWAY);
		assertThat(row.reference()).isNotBlank();
	}

	@Test
	void outstandingReturnsOnlyNonPaidNonRefundedOrders() {
		Fixture fixture = buildComprehensiveFixture("ledger-ext-outstanding");

		HttpResult<PageResponse<OutstandingOrderResponse>> result = getOutstanding(fixture.host(),
				fixture.adminToken());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(result.getBody().data().content()).extracting(OutstandingOrderResponse::orderId)
			.containsExactlyInAnyOrder(fixture.unpaidOrder().id(), fixture.pendingOrder().id(),
					fixture.underReviewOrder().id(), fixture.rejectedOrder().id())
			.doesNotContain(fixture.gatewayOrder().id(), fixture.freeOrder().id(), fixture.staffGrantedOrder().id(),
					fixture.slipOrder().id(), fixture.refundedOrder().id());
	}

	@Test
	void courseSummaryAggregateCountsMatchTheHandComputedFixture() {
		Fixture fixture = buildComprehensiveFixture("ledger-ext-summary");

		HttpResult<CoursePaymentSummaryResponse> result = getCourseSummary(fixture.host(), fixture.adminToken(),
				fixture.gatewayOrder().courseId());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		CoursePaymentSummaryResponse summary = result.getBody().data();
		assertThat(summary.courseId()).isEqualTo(fixture.gatewayOrder().courseId());
		assertThat(summary.totalOrders()).isEqualTo(1L);
		assertThat(summary.byState()).hasSize(1);
		assertThat(summary.byState().get(0).state()).isEqualTo(PaymentOperationalState.PAID);
		assertThat(summary.byState().get(0).count()).isEqualTo(1L);
		assertThat(summary.byState().get(0).totalAmount()).isEqualByComparingTo(fixture.gatewayOrder().amount());
	}

	/**
	 * Tenant isolation (plan §7): a tenant-A staff member requesting
	 * tenant-B's course summary must never receive tenant-B's real numbers -
	 * an empty/all-zero summary, mirroring {@code CourseLookupApi}'s
	 * established anti-enumeration "not-found vs. cross-tenant"
	 * non-distinction.
	 */
	@Test
	void courseSummaryForAnotherTenantsCourseIdReturnsEmptyNeverTheOtherTenantsRealData() {
		Fixture tenantA = buildComprehensiveFixture("ledger-ext-cross-a");
		Fixture tenantB = buildComprehensiveFixture("ledger-ext-cross-b");

		HttpResult<CoursePaymentSummaryResponse> result = getCourseSummary(tenantA.host(), tenantA.adminToken(),
				tenantB.gatewayOrder().courseId());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		CoursePaymentSummaryResponse summary = result.getBody().data();
		assertThat(summary.totalOrders()).isEqualTo(0L);
		assertThat(summary.byState()).isEmpty();
		// courseTitle is null (never leaks tenant B's real course title).
		assertThat(summary.courseTitle()).isNull();
	}

	/** Tenant isolation for the outstanding endpoint - never another tenant's orders. */
	@Test
	void outstandingNeverLeaksAnotherTenantsOrders() {
		Fixture tenantA = buildComprehensiveFixture("ledger-ext-out-cross-a");
		Fixture tenantB = buildComprehensiveFixture("ledger-ext-out-cross-b");

		HttpResult<PageResponse<OutstandingOrderResponse>> resultA = getOutstanding(tenantA.host(),
				tenantA.adminToken());

		assertThat(resultA.getStatusCode()).isEqualTo(HttpStatus.OK);
		List<UUID> orderIdsA = resultA.getBody().data().content().stream().map(OutstandingOrderResponse::orderId).toList();
		assertThat(orderIdsA).doesNotContain(tenantB.unpaidOrder().id(), tenantB.pendingOrder().id(),
				tenantB.underReviewOrder().id(), tenantB.rejectedOrder().id());
	}

	// ------------------------------------------------------------------
	// Role-based negative coverage (completion review finding #3): both new
	// Wave 6 §4 endpoints rely entirely on LedgerQueryService's
	// PAYMENTS_SLIPS/VIEW permission check - STUDENT/TEACHER are structurally
	// absent from the grant matrix, so both must 403. No prior test called
	// either endpoint with a STUDENT/TEACHER token at all.
	// ------------------------------------------------------------------

	@Test
	void outstandingRejectsStudentAndTeacherCallersWith403() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("ledger-ext-outstanding-rbac"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String teacherToken = loginAndGetToken(host, "teacher@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");

		HttpResult<PageResponse<OutstandingOrderResponse>> studentResult = getOutstanding(host, studentToken);
		HttpResult<PageResponse<OutstandingOrderResponse>> teacherResult = getOutstanding(host, teacherToken);

		assertThat(studentResult.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(teacherResult.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void courseSummaryRejectsStudentAndTeacherCallersWith403() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("ledger-ext-summary-rbac"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String teacherToken = loginAndGetToken(host, "teacher@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug("ledger-ext-summary-rbac"), teacher.getId(), CourseStatus.PUBLIC));

		HttpResult<CoursePaymentSummaryResponse> studentResult = getCourseSummary(host, studentToken, course.id());
		HttpResult<CoursePaymentSummaryResponse> teacherResult = getCourseSummary(host, teacherToken, course.id());

		assertThat(studentResult.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(teacherResult.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	// ------------------------------------------------------------------
	// Helpers.
	// ------------------------------------------------------------------

	private void assertDashboardFilteredTo(Fixture fixture, PaymentOperationalState status, PaymentMethod method,
			UUID... expectedOrderIds) {
		HttpResult<PageResponse<LedgerHistoryEntryResponse>> result = getLedgerDashboardFiltered(fixture.host(),
				fixture.adminToken(), status, method);
		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(result.getBody().data().content()).extracting(LedgerHistoryEntryResponse::orderId)
			.containsExactlyInAnyOrder(expectedOrderIds);
	}

	private HttpResult<PageResponse<LedgerHistoryEntryResponse>> getLedgerDashboardFiltered(String host,
			String token, PaymentOperationalState status, PaymentMethod method) {
		StringBuilder query = new StringBuilder("/api/v1/ledger/dashboard?size=50");
		if (status != null) {
			query.append("&status=").append(status.name());
		}
		if (method != null) {
			query.append("&method=").append(method.name());
		}
		MockHttpServletRequestBuilder builder = get(query.toString());
		return parsePage(perform(authenticated(builder, host, token)), LedgerHistoryEntryResponse.class);
	}

	private HttpResult<PageResponse<OutstandingOrderResponse>> getOutstanding(String host, String token) {
		MockHttpServletRequestBuilder builder = get("/api/v1/ledger/outstanding?size=50");
		return parsePage(perform(authenticated(builder, host, token)), OutstandingOrderResponse.class);
	}

	private HttpResult<CoursePaymentSummaryResponse> getCourseSummary(String host, String token, UUID courseId) {
		MockHttpServletRequestBuilder builder = get("/api/v1/ledger/courses/{courseId}/summary", courseId);
		return parseSingle(perform(authenticated(builder, host, token)), CoursePaymentSummaryResponse.class);
	}

	/**
	 * Builds one tenant with nine orders - one per {@code
	 * PaymentOperationalState}/{@code PaymentMethod} combination this wave's
	 * test plan requires - each against its OWN course (so an "already
	 * enrolled" conflict from one order's activation can never affect
	 * another order's setup).
	 */
	private Fixture buildComprehensiveFixture(String prefix) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain(prefix));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		seedTenantUser(tenant.getId(), "finance@example.test", RAW_PASSWORD, Role.FINANCE_STAFF);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser student = seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String financeToken = loginAndGetToken(host, "finance@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");

		// PAID / GATEWAY.
		CourseResponse gatewayCourse = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug(prefix + "-gw"), teacher.getId(), CourseStatus.PUBLIC));
		OrderResponse gatewayOrder = createOrderOrFail(host, studentToken, gatewayCourse.id());
		PaymentInitiationResponse gatewayInitiation = initiatePaymentOrFail(host, studentToken, gatewayOrder.id());
		sendPaymentWebhook(gatewayInitiation.gatewayReference(), true);

		// PAID / FREE.
		CourseResponse freeCourse = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug(prefix + "-free"), teacher.getId(), CourseStatus.PUBLIC));
		changePricingModel(host, adminToken, freeCourse.id(), CoursePricingModel.FREE);
		OrderResponse freeOrder = createOrderOrFail(host, studentToken, freeCourse.id());

		// PAID / STAFF_GRANTED.
		CourseResponse staffGrantedCourse = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug(prefix + "-staff"), teacher.getId(), CourseStatus.PUBLIC));
		StudentResponse staffGrantedStudent = createStudentProfileOrFail(host, adminToken,
				uniqueEmail(prefix + "-staff"));
		OrderResponse staffGrantedOrder = manualEnrollOrFail(host, adminToken, tenant.getId(),
				staffGrantedStudent.id(), staffGrantedCourse.id(), "scholarship grant");

		// PAID / MANUAL_SLIP.
		CourseResponse slipCourse = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug(prefix + "-slip"), teacher.getId(), CourseStatus.PUBLIC));
		OrderResponse slipOrder = createOrderOrFail(host, studentToken, slipCourse.id());
		PaymentSlipResponse slip = uploadSlipOrFail(host, studentToken, slipOrder.id(), "REF-" + prefix + "-SLIP",
				pdfFile("slip.pdf"));
		HttpResult<PaymentSlipResponse> approved = approveSlip(host, financeToken, slip.id(), null);
		if (approved.getStatusCode() != HttpStatus.OK) {
			throw new IllegalStateException("Slip approval failed: " + approved.getStatusCode());
		}

		// UNPAID.
		CourseResponse unpaidCourse = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug(prefix + "-unpaid"), teacher.getId(), CourseStatus.PUBLIC));
		OrderResponse unpaidOrder = createOrderOrFail(host, studentToken, unpaidCourse.id());

		// PENDING.
		CourseResponse pendingCourse = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug(prefix + "-pending"), teacher.getId(), CourseStatus.PUBLIC));
		OrderResponse pendingOrder = createOrderOrFail(host, studentToken, pendingCourse.id());
		initiatePaymentOrFail(host, studentToken, pendingOrder.id());

		// UNDER_REVIEW.
		CourseResponse underReviewCourse = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug(prefix + "-review"), teacher.getId(), CourseStatus.PUBLIC));
		OrderResponse underReviewOrder = createOrderOrFail(host, studentToken, underReviewCourse.id());
		uploadSlipOrFail(host, studentToken, underReviewOrder.id(), "REF-" + prefix + "-REVIEW",
				pdfFile("under-review.pdf", distinctPdfBytes(prefix + "-review")));

		// REJECTED.
		CourseResponse rejectedCourse = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug(prefix + "-rejected"), teacher.getId(), CourseStatus.PUBLIC));
		OrderResponse rejectedOrder = createOrderOrFail(host, studentToken, rejectedCourse.id());
		PaymentInitiationResponse rejectedInitiation = initiatePaymentOrFail(host, studentToken, rejectedOrder.id());
		sendPaymentWebhook(rejectedInitiation.gatewayReference(), false);

		// REFUNDED / GATEWAY.
		CourseResponse refundedCourse = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug(prefix + "-refunded"), teacher.getId(), CourseStatus.PUBLIC));
		OrderResponse refundedOrder = createOrderOrFail(host, studentToken, refundedCourse.id());
		PaymentInitiationResponse refundedInitiation = initiatePaymentOrFail(host, studentToken, refundedOrder.id());
		sendPaymentWebhook(refundedInitiation.gatewayReference(), true);
		createRefund(host, adminToken, refundedInitiation.paymentId(), refundedOrder.amount(), "Full refund");

		return new Fixture(tenant, host, adminToken, financeToken, studentToken, gatewayOrder, freeOrder,
				staffGrantedOrder, slipOrder, unpaidOrder, pendingOrder, underReviewOrder, rejectedOrder,
				refundedOrder);
	}

	private StudentResponse createStudentProfileOrFail(String host, String token, String email) {
		MockHttpServletRequestBuilder builder = post("/api/v1/students").contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(new StudentCreateRequest("Test Student", email, RAW_PASSWORD)));
		HttpResult<StudentResponse> result = parseSingle(perform(authenticated(builder, host, token)),
				StudentResponse.class);
		if (result.getStatusCode() != HttpStatus.CREATED) {
			throw new IllegalStateException("Student creation failed: " + result.getStatusCode() + " " + result.getBody());
		}
		return result.getBody().data();
	}

	private OrderResponse manualEnrollOrFail(String host, String token, UUID tenantId, UUID studentProfileId,
			UUID courseId, String reason) {
		String body = "{\"courseId\":\"" + courseId + "\",\"reason\":\"" + reason + "\"}";
		MockHttpServletRequestBuilder builder = post("/api/v1/students/{id}/enroll", studentProfileId)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body);
		HttpResult<Void> result = parseSingle(perform(authenticated(builder, host, token)), Void.class);
		if (!result.getStatusCode().is2xxSuccessful()) {
			throw new IllegalStateException("Manual enroll failed: " + result.getStatusCode() + " " + result.getBody());
		}
		// The manual-enroll endpoint returns no order body - resolve it via
		// student_order directly (one order per course/student, per this
		// fixture's construction, so the query is unambiguous).
		UUID orderId = jdbcTemplate.queryForObject(
				"SELECT id FROM student_order WHERE tenant_id = ? AND course_id = ?", UUID.class, tenantId,
				courseId);
		HttpResult<OrderResponse> orderResult = getOrder(host, token, orderId);
		if (orderResult.getStatusCode() != HttpStatus.OK) {
			throw new IllegalStateException("Order lookup failed: " + orderResult.getStatusCode());
		}
		return orderResult.getBody().data();
	}

	private static String uniqueEmail(String prefix) {
		return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
	}

	private record Fixture(Tenant tenant, String host, String adminToken, String financeToken, String studentToken,
			OrderResponse gatewayOrder, OrderResponse freeOrder, OrderResponse staffGrantedOrder,
			OrderResponse slipOrder, OrderResponse unpaidOrder, OrderResponse pendingOrder,
			OrderResponse underReviewOrder, OrderResponse rejectedOrder, OrderResponse refundedOrder) {
	}

}
