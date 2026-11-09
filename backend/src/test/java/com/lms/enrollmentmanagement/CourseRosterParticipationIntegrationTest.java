package com.lms.enrollmentmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.lms.contentmanagement.ContentManagementTestSupport;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.tenantmanagement.domain.Tenant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Course roster membership is free course participation (ADR-028, plan O-4
 * resolved 2026-11-09), never the legacy paid-enrollment window. Each row
 * carries {@code accessStatus}: whether the student has paid access to the
 * course right now (legacy window, a MONTHLY entitlement for the CURRENT
 * month, or an active MODULE/LESSON entitlement).
 */
class CourseRosterParticipationIntegrationTest extends ContentManagementTestSupport {

	private record Fx(Tenant tenant, String host, String adminToken, CourseResponse course) {

	}

	private Fx seed(String prefix) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain(prefix));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug(prefix), teacher.getId(), CourseStatus.PUBLIC));
		return new Fx(tenant, host, adminToken, course);
	}

	/** A STUDENT tenant user plus the {@code student_profile} the roster reads names from. */
	private void seedStudent(Fx fx, String email) {
		TenantUser user = seedActiveStudent(fx.tenant().getId(), email);
		jdbcTemplate.update("INSERT INTO student_profile (id, tenant_id, user_id, name, created_at, updated_at) "
				+ "VALUES (?, ?, ?, ?, now(), now())", UUID.randomUUID(), fx.tenant().getId(), user.getId(), email);
	}

	private String enrolledStudentToken(Fx fx, String email) {
		seedStudent(fx, email);
		String token = loginAndGetToken(fx.host(), email);
		MvcResult enroll = perform(authenticated(post("/api/v1/enrollments/my/courses/{courseId}", fx.course().id()),
				fx.host(), token));
		assertThat(enroll.getResponse().getStatus()).as(rawContent(enroll)).isEqualTo(200);
		return token;
	}

	private UUID userId(Fx fx, String email) {
		return jdbcTemplate.queryForObject("SELECT id FROM tenant_user WHERE tenant_id = ? AND email = ?", UUID.class,
				fx.tenant().getId(), email);
	}

	private Map<UUID, String> rosterStatuses(String host, String token, UUID courseId) {
		MvcResult result = perform(authenticated(get("/api/v1/courses/{courseId}/roster", courseId), host, token));
		assertThat(result.getResponse().getStatus()).as(rawContent(result)).isEqualTo(200);
		Map<UUID, String> statuses = new HashMap<>();
		for (JsonNode row : objectMapper.readTree(rawContent(result)).path("data")) {
			statuses.put(UUID.fromString(row.path("userId").asString()), row.path("accessStatus").asString());
		}
		return statuses;
	}

	/** A confirmed MONTHLY entitlement for {@code month}, still within its {@code valid_until}. */
	private void seedMonthlyEntitlement(Fx fx, UUID studentId, YearMonth month) {
		UUID tenantId = fx.tenant().getId();
		UUID courseId = fx.course().id();
		String key = "MONTHLY:" + courseId + ":" + month;
		UUID orderId = UUID.randomUUID();
		UUID lineId = UUID.randomUUID();
		UUID paymentId = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO student_order (id, tenant_id, student_id, course_id, amount, currency, status, "
				+ "created_at, updated_at, subtotal_amount, facility_fee_amount, discount_amount) "
				+ "VALUES (?, ?, ?, ?, 3500.00, 'LKR', 'PENDING', now(), now(), 3500.00, 0, 0)", orderId, tenantId,
				studentId, courseId);
		jdbcTemplate.update("INSERT INTO student_order_line (id, tenant_id, order_id, line_no, scope, course_id, "
				+ "period_year, period_month, label, unit_amount, facility_fee_amount, discount_amount, line_total, "
				+ "currency, coverage_key, created_at) VALUES (?, ?, ?, 1, 'MONTHLY', ?, ?, ?, 'Month', "
				+ "3500.00, 0, 0, 3500.00, 'LKR', ?, now())", lineId, tenantId, orderId, courseId, month.getYear(),
				month.getMonthValue(), key);
		jdbcTemplate.update("INSERT INTO payment (id, tenant_id, order_id, amount, currency, status, created_at, "
				+ "updated_at, confirmed_at, gateway_reference) VALUES (?, ?, ?, 3500.00, 'LKR', 'CONFIRMED', now(), "
				+ "now(), now(), ?)", paymentId, tenantId, orderId, "roster-ref-" + paymentId);
		jdbcTemplate.update("INSERT INTO content_entitlement (id, tenant_id, student_id, scope, course_id, period_year, "
				+ "period_month, coverage_key, order_id, order_line_id, source_payment_id, granted_at, valid_until) "
				+ "VALUES (?, ?, ?, 'MONTHLY', ?, ?, ?, ?, ?, ?, ?, now(), now() + interval '30 days')",
				UUID.randomUUID(), tenantId, studentId, courseId, month.getYear(), month.getMonthValue(), key, orderId,
				lineId, paymentId);
	}

	@Test
	void freeParticipantWithoutPaymentIsListedAsNotPaid() {
		Fx fx = seed("roster-free");
		enrolledStudentToken(fx, "free@example.test");

		Map<UUID, String> roster = rosterStatuses(fx.host(), fx.adminToken(), fx.course().id());

		assertThat(roster).containsEntry(userId(fx, "free@example.test"), "NOT_PAID");
	}

	@Test
	void currentMonthEntitlementIsPaidButLastMonthsStillValidEntitlementIsNot() {
		Fx fx = seed("roster-monthly");
		enrolledStudentToken(fx, "current@example.test");
		enrolledStudentToken(fx, "previous@example.test");
		YearMonth thisMonth = YearMonth.now(ZoneOffset.UTC);
		seedMonthlyEntitlement(fx, userId(fx, "current@example.test"), thisMonth);
		seedMonthlyEntitlement(fx, userId(fx, "previous@example.test"), thisMonth.minusMonths(1));

		Map<UUID, String> roster = rosterStatuses(fx.host(), fx.adminToken(), fx.course().id());

		assertThat(roster).containsEntry(userId(fx, "current@example.test"), "PAID")
			.containsEntry(userId(fx, "previous@example.test"), "NOT_PAID");
	}

	@Test
	void legacyPaidEnrollmentIsPaidWhileOpenAndStillListedAfterItExpires() {
		Fx fx = seed("roster-legacy");
		seedStudent(fx, "active@example.test");
		seedStudent(fx, "expired@example.test");
		enrollStudentOrFail(fx.host(), loginAndGetToken(fx.host(), "active@example.test"), fx.course().id());
		enrollStudentOrFail(fx.host(), loginAndGetToken(fx.host(), "expired@example.test"), fx.course().id());
		jdbcTemplate.update("UPDATE enrollment SET access_expires_at = now() - interval '1 day' "
				+ "WHERE tenant_id = ? AND student_id = ? AND course_id = ?", fx.tenant().getId(),
				userId(fx, "expired@example.test"), fx.course().id());

		Map<UUID, String> roster = rosterStatuses(fx.host(), fx.adminToken(), fx.course().id());

		assertThat(roster).containsEntry(userId(fx, "active@example.test"), "PAID")
			.containsEntry(userId(fx, "expired@example.test"), "NOT_PAID");
	}

	@Test
	void freePricedCourseShowsFreeCourse() {
		Fx fx = seed("roster-freecourse");
		jdbcTemplate.update("UPDATE course SET pricing_model = 'FREE' WHERE id = ?", fx.course().id());
		enrolledStudentToken(fx, "student@example.test");

		Map<UUID, String> roster = rosterStatuses(fx.host(), fx.adminToken(), fx.course().id());

		assertThat(roster).containsEntry(userId(fx, "student@example.test"), "FREE_COURSE");
	}

	@Test
	@Tag("cross-tenant")
	void tenantBAdminRequestingTenantAsRosterGets404() {
		Fx fxA = seed("roster-x-a");
		enrolledStudentToken(fxA, "student@example.test");
		Fx fxB = seed("roster-x-b");

		MvcResult cross = perform(authenticated(get("/api/v1/courses/{courseId}/roster", fxA.course().id()),
				fxB.host(), fxB.adminToken()));

		assertThat(cross.getResponse().getStatus()).isEqualTo(404);
	}

}
