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
 * Course roster (owner, 2026-11-10): the enrolled (participating) students who
 * have access right now - paid (open legacy window, a MONTHLY entitlement for the
 * CURRENT month, an active MODULE/LESSON entitlement), an effective Free Card for
 * the current month, or a FREE-priced course. Unpaid participants are not listed.
 * That view needs View Course Payment Status (Teacher: only once granted);
 * without it the roster lists every enrolled student with no status.
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
			JsonNode status = row.path("accessStatus");
			statuses.put(UUID.fromString(row.path("userId").asString()), status.isNull() ? "" : status.asString());
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

	/** An effective Free Card of this course covering the current month (ADR-030). */
	private void seedCurrentMonthFreeCard(Fx fx, UUID studentId) {
		YearMonth thisMonth = YearMonth.now(ZoneOffset.UTC);
		java.time.LocalDate from = thisMonth.atDay(1);
		java.time.LocalDate to = thisMonth.atEndOfMonth();
		UUID adminId = userId(fx, "admin@example.test");
		jdbcTemplate.update("INSERT INTO free_card (id, tenant_id, student_id, course_id, card_type, valid_from, "
				+ "valid_to, timezone, effective_from, effective_until, reason, currency, issued_by, issued_at, "
				+ "idempotency_key, enrollment_created) VALUES (?, ?, ?, ?, 'FULL', ?, ?, 'UTC', ?, ?, 'Scholarship', "
				+ "'LKR', ?, now(), ?, false)", UUID.randomUUID(), fx.tenant().getId(), studentId, fx.course().id(),
				from, to, java.sql.Timestamp.from(from.atStartOfDay(ZoneOffset.UTC).toInstant()),
				java.sql.Timestamp.from(to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()), adminId,
				UUID.randomUUID());
	}

	@Test
	void rosterListsOnlyStudentsPaidForTheCurrentMonthOrHoldingAFreeCard() {
		Fx fx = seed("roster-monthly");
		enrolledStudentToken(fx, "current@example.test");
		enrolledStudentToken(fx, "previous@example.test");
		enrolledStudentToken(fx, "card@example.test");
		enrolledStudentToken(fx, "unpaid@example.test");
		YearMonth thisMonth = YearMonth.now(ZoneOffset.UTC);
		seedMonthlyEntitlement(fx, userId(fx, "current@example.test"), thisMonth);
		seedMonthlyEntitlement(fx, userId(fx, "previous@example.test"), thisMonth.minusMonths(1));
		seedCurrentMonthFreeCard(fx, userId(fx, "card@example.test"));

		Map<UUID, String> roster = rosterStatuses(fx.host(), fx.adminToken(), fx.course().id());

		assertThat(roster).containsOnly(Map.entry(userId(fx, "current@example.test"), "PAID"),
				Map.entry(userId(fx, "card@example.test"), "FREE_CARD"));
	}

	@Test
	void legacyPaidEnrollmentIsListedWhileOpenAndDroppedAfterItExpires() {
		Fx fx = seed("roster-legacy");
		seedStudent(fx, "active@example.test");
		seedStudent(fx, "expired@example.test");
		enrollStudentOrFail(fx.host(), loginAndGetToken(fx.host(), "active@example.test"), fx.course().id());
		enrollStudentOrFail(fx.host(), loginAndGetToken(fx.host(), "expired@example.test"), fx.course().id());
		jdbcTemplate.update("UPDATE enrollment SET access_expires_at = now() - interval '1 day' "
				+ "WHERE tenant_id = ? AND student_id = ? AND course_id = ?", fx.tenant().getId(),
				userId(fx, "expired@example.test"), fx.course().id());

		Map<UUID, String> roster = rosterStatuses(fx.host(), fx.adminToken(), fx.course().id());

		assertThat(roster).containsOnly(Map.entry(userId(fx, "active@example.test"), "PAID"));
	}

	@Test
	void freePricedCourseShowsFreeCourse() {
		Fx fx = seed("roster-freecourse");
		jdbcTemplate.update("UPDATE course SET pricing_model = 'FREE' WHERE id = ?", fx.course().id());
		enrolledStudentToken(fx, "student@example.test");

		Map<UUID, String> roster = rosterStatuses(fx.host(), fx.adminToken(), fx.course().id());

		assertThat(roster).containsEntry(userId(fx, "student@example.test"), "FREE_COURSE");
	}

	private Map<UUID, String> rosterAsTeacher(Fx fx) {
		return rosterStatuses(fx.host(), loginAndGetToken(fx.host(), "teacher@example.test"), fx.course().id());
	}

	@Test
	void aTeacherWithoutViewCoursePaymentStatusSeesEveryEnrolledStudentWithNoStatus() {
		Fx fx = seed("roster-teacher-nocap");
		enrolledStudentToken(fx, "paid@example.test");
		enrolledStudentToken(fx, "unpaid@example.test");
		seedMonthlyEntitlement(fx, userId(fx, "paid@example.test"), YearMonth.now(ZoneOffset.UTC));

		Map<UUID, String> roster = rosterAsTeacher(fx);

		// Nothing to see or infer: unpaid students are listed and no row carries a status.
		assertThat(roster).containsOnlyKeys(userId(fx, "paid@example.test"), userId(fx, "unpaid@example.test"));
		assertThat(roster.values()).containsOnly("");
	}

	@Test
	void aTeacherGrantedViewCoursePaymentStatusSeesOnlyActiveStudentsWithStatus() {
		Fx fx = seed("roster-teacher-cap");
		enrolledStudentToken(fx, "paid@example.test");
		enrolledStudentToken(fx, "unpaid@example.test");
		seedMonthlyEntitlement(fx, userId(fx, "paid@example.test"), YearMonth.now(ZoneOffset.UTC));
		jdbcTemplate.update("INSERT INTO tenant_role_capability_grant (id, tenant_id, role_code, capability_key, "
				+ "granted) VALUES (?, ?, 'TEACHER', 'COURSES_VIEW_PAYMENT_STATUS', true)", UUID.randomUUID(),
				fx.tenant().getId());

		Map<UUID, String> roster = rosterAsTeacher(fx);

		assertThat(roster).containsOnly(Map.entry(userId(fx, "paid@example.test"), "PAID"));
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
