package com.lms.usermanagement.student.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.lms.identityaccessservice.domain.Role;
import com.lms.usermanagement.student.StudentManagementTestSupport;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * STU-MGMT-02 create / edit / approve / remove (spec sections 3, 5 and 9): every form
 * field round-trips, phone strings keep leading zeros, blank DOB stays unknown, course
 * selection creates free participation only (no order / payment / ledger / entitlement,
 * ADR-028), existing email conflicts, concurrent creates get unique index numbers,
 * removal keeps history, and other tenants' students are 404.
 */
class StudentRegistrationV2IntegrationTest extends StudentManagementTestSupport {

	private Map<String, Object> fullForm(Fx fx) {
		Map<String, Object> form = form(fx, "Kasun", "Perera");
		form.put("whatsappNumber", "0711234567");
		form.put("dateOfBirth", "2006-03-14");
		form.put("addressLine", "12 Temple Road");
		form.put("addressLine2", "Nugegoda");
		form.put("city", "Colombo");
		form.put("postalCode", "10250");
		form.put("guardianName", "Sunil Perera");
		form.put("guardianPhone", "0112345678");
		form.put("guardianNic", "197512345678");
		form.put("fixedPhone", "0112999888");
		form.put("school", "Royal College");
		form.put("alYear", 2025);
		form.put("stream", "Physical Science");
		// Never read: tenant, status and role are not part of the form.
		form.put("tenantId", UUID.randomUUID().toString());
		form.put("status", "SUSPENDED");
		form.put("role", "TENANT_ADMIN");
		return form;
	}

	@Test
	void createAndEditRoundTripEveryFieldAndCreateOnlyFreeParticipation() {
		Fx fx = fixture("stu-v2-rt");
		Map<String, Object> form = fullForm(fx);
		String mobile = (String) form.get("mobile");
		JsonNode created = createStudent(fx, form);
		UUID id = UUID.fromString(created.path("id").asString());

		assertThat(created.path("indexNumber").asString()).matches("[0-9]{4,}");
		assertThat(created.path("firstName").asString()).isEqualTo("Kasun");
		assertThat(created.path("lastName").asString()).isEqualTo("Perera");
		assertThat(created.path("fullName").asString()).isEqualTo("Kasun Perera");
		assertThat(created.path("email").asString()).isEqualTo(form.get("email"));
		assertThat(created.path("mobile").asString()).isEqualTo(mobile).startsWith("0");
		assertThat(created.path("whatsappNumber").asString()).isEqualTo("0711234567");
		assertThat(created.path("dateOfBirth").asString()).isEqualTo("2006-03-14");
		assertThat(created.path("addressLine").asString()).isEqualTo("12 Temple Road");
		assertThat(created.path("addressLine2").asString()).isEqualTo("Nugegoda");
		assertThat(created.path("city").asString()).isEqualTo("Colombo");
		assertThat(created.path("postalCode").asString()).isEqualTo("10250");
		assertThat(created.path("guardianName").asString()).isEqualTo("Sunil Perera");
		assertThat(created.path("guardianPhone").asString()).isEqualTo("0112345678");
		assertThat(created.path("guardianNic").asString()).isEqualTo("197512345678");
		assertThat(created.path("fixedPhone").asString()).isEqualTo("0112999888");
		assertThat(created.path("school").asString()).isEqualTo("Royal College");
		assertThat(created.path("alYear").asInt()).isEqualTo(2025);
		assertThat(created.path("stream").asString()).isEqualTo("Physical Science");
		assertThat(created.path("status").asString()).isEqualTo("ACTIVE");
		assertThat(created.path("pending").asBoolean()).isFalse();
		assertThat(created.path("registeredAt").isNull()).isFalse();
		assertThat(created.path("enrolledCourseIds").get(0).asString()).isEqualTo(fx.courseId().toString());

		UUID userId = userIdOf(id);
		// Stored in the tenant resolved from the host, not the body's tenantId; role STUDENT.
		assertThat(jdbcTemplate.queryForObject("SELECT tenant_id FROM student_profile WHERE id = ?", UUID.class, id))
			.isEqualTo(fx.tenant().getId());
		assertThat(jdbcTemplate.queryForObject("SELECT role FROM tenant_user WHERE id = ?", String.class, userId))
			.isEqualToIgnoringCase("STUDENT");
		// The sign-in mobile is the normalised number (ADR-031).
		assertThat(jdbcTemplate.queryForObject("SELECT login_mobile FROM tenant_user WHERE id = ?", String.class,
				userId)).isEqualTo("+94" + mobile.substring(1));
		// Free participation only - never an order, payment, ledger entry or entitlement.
		assertThat(count("SELECT count(*) FROM course_participation WHERE tenant_id = ? AND student_id = ? "
				+ "AND source = 'STAFF_GRANT'", fx.tenant().getId(), userId)).isEqualTo(1);
		assertNoFinancialRows(fx);

		// Edit every field; DOB blank -> unknown; add-only course selection.
		UUID secondCourse = createCourseOrFail(fx.host(), fx.adminToken(), newCourseRequest(uniqueSlug("stu2"),
				fx.teacher().getId(), com.lms.coursemanagement.course.domain.CourseStatus.PUBLIC)).id();
		Map<String, Object> edit = new LinkedHashMap<>();
		edit.put("email", form.get("email"));
		edit.put("firstName", "Kasun Chathura");
		edit.put("lastName", "Fernando");
		edit.put("mobile", mobile);
		edit.put("whatsappNumber", "0779999999");
		edit.put("dateOfBirth", "");
		edit.put("addressLine", "1 Galle Road");
		edit.put("guardianNic", "200012345678");
		edit.put("alYear", 2026);
		edit.put("stream", "Bio Science");
		edit.put("password", "ignored-on-edit-123");
		edit.put("courseIds", List.of(secondCourse.toString()));
		String passwordHashBefore = jdbcTemplate.queryForObject("SELECT password_hash FROM tenant_user WHERE id = ?",
				String.class, userId);
		String indexBefore = created.path("indexNumber").asString();
		MvcResult updated = send(fx.host(), fx.adminToken(), put("/api/v1/students/{id}/detail", id), edit);
		assertThat(status(updated)).as(rawContent(updated)).isEqualTo(200);
		JsonNode detail = data(updated);
		assertThat(detail.path("firstName").asString()).isEqualTo("Kasun Chathura");
		assertThat(detail.path("lastName").asString()).isEqualTo("Fernando");
		assertThat(detail.path("fullName").asString()).isEqualTo("Kasun Chathura Fernando");
		assertThat(detail.path("dateOfBirth").isNull()).isTrue();
		assertThat(detail.path("whatsappNumber").asString()).isEqualTo("0779999999");
		assertThat(detail.path("addressLine2").isNull()).isTrue();
		assertThat(detail.path("guardianNic").asString()).isEqualTo("200012345678");
		assertThat(detail.path("alYear").asInt()).isEqualTo(2026);
		assertThat(detail.path("indexNumber").asString()).isEqualTo(indexBefore);
		assertThat(detail.path("enrolledCourseIds")).hasSize(2);
		assertThat(jdbcTemplate.queryForObject("SELECT name FROM student_profile WHERE id = ?", String.class, id))
			.isEqualTo("Kasun Chathura Fernando");
		assertThat(jdbcTemplate.queryForObject("SELECT date_of_birth FROM student_profile WHERE id = ?",
				LocalDate.class, id)).isNull();
		assertThat(jdbcTemplate.queryForObject("SELECT password_hash FROM tenant_user WHERE id = ?", String.class,
				userId)).isEqualTo(passwordHashBefore);
		assertNoFinancialRows(fx);

		// GET detail round-trips the same values.
		MvcResult fetched = getAs(fx.host(), fx.adminToken(), "/api/v1/students/{id}/detail", id);
		assertThat(status(fetched)).isEqualTo(200);
		assertThat(data(fetched).path("guardianNic").asString()).isEqualTo("200012345678");
		assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'student.updated' "
				+ "AND target_id = ?", fx.tenant().getId(), id)).isEqualTo(1);
	}

	private void assertNoFinancialRows(Fx fx) {
		for (String table : List.of("student_order", "payment", "ledger_entry", "enrollment", "content_entitlement")) {
			assertThat(count("SELECT count(*) FROM " + table + " WHERE tenant_id = ?", fx.tenant().getId()))
				.as(table)
				.isZero();
		}
	}

	@Test
	void validationRejectsFutureOrAncientBirthDatesBadPhonesAndMissingCourses() {
		Fx fx = fixture("stu-v2-val");
		Map<String, Object> future = form(fx, "Future", "Born");
		future.put("dateOfBirth", LocalDate.now().plusDays(2).toString());
		MvcResult rejected = send(fx.host(), fx.adminToken(), post("/api/v1/students/v2"), future);
		assertThat(status(rejected)).as(rawContent(rejected)).isEqualTo(400);
		assertThat(rawContent(rejected)).contains("dateOfBirth");

		Map<String, Object> ancient = form(fx, "Ancient", "Born");
		ancient.put("dateOfBirth", "1899-12-31");
		assertThat(status(send(fx.host(), fx.adminToken(), post("/api/v1/students/v2"), ancient))).isEqualTo(400);

		Map<String, Object> phone = form(fx, "Bad", "Phone");
		phone.put("whatsappNumber", "07x<script>");
		assertThat(status(send(fx.host(), fx.adminToken(), post("/api/v1/students/v2"), phone))).isEqualTo(400);

		Map<String, Object> noCourse = form(fx, "No", "Course");
		noCourse.put("courseIds", List.of());
		MvcResult missing = send(fx.host(), fx.adminToken(), post("/api/v1/students/v2"), noCourse);
		assertThat(status(missing)).isEqualTo(400);
		assertThat(rawContent(missing)).contains("courseIds");

		// Another tenant's course is not selectable.
		Fx other = fixture("stu-v2-val-other");
		Map<String, Object> foreignCourse = form(fx, "Foreign", "Course");
		foreignCourse.put("courseIds", List.of(other.courseId().toString()));
		assertThat(status(send(fx.host(), fx.adminToken(), post("/api/v1/students/v2"), foreignCourse)))
			.isEqualTo(400);

		// Blank DOB is accepted and stored as unknown.
		Map<String, Object> blank = form(fx, "Blank", "Dob");
		blank.put("dateOfBirth", "  ");
		JsonNode created = createStudent(fx, blank);
		assertThat(created.path("dateOfBirth").isNull()).isTrue();
		assertThat(count("SELECT count(*) FROM student_profile WHERE tenant_id = ?", fx.tenant().getId()))
			.isEqualTo(1);
	}

	@Test
	void blankPasswordGeneratesATemporaryPasswordReturnedOnceAndExistingEmailConflicts() {
		Fx fx = fixture("stu-v2-pw");
		Map<String, Object> form = form(fx, "Temp", "Password");
		form.put("password", "");
		MvcResult result = send(fx.host(), fx.adminToken(), post("/api/v1/students/v2"), form);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(201);
		String temporary = data(result).path("temporaryPassword").asString();
		assertThat(temporary).hasSizeGreaterThanOrEqualTo(12);
		UUID id = UUID.fromString(data(result).path("student").path("id").asString());
		assertThat(jdbcTemplate.queryForObject("SELECT must_change_password FROM tenant_user WHERE id = ?",
				Boolean.class, userIdOf(id))).isTrue();
		// The temporary password signs in.
		assertThat(login(fx.host(), (String) form.get("email"), temporary).getStatusCode().value()).isEqualTo(200);
		// It is never logged into the audit trail.
		assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND metadata::text LIKE ?",
				fx.tenant().getId(), "%" + temporary + "%")).isZero();

		// A provided password returns no temporary password.
		MvcResult provided = send(fx.host(), fx.adminToken(), post("/api/v1/students/v2"), form(fx, "Given", "Pw"));
		assertThat(data(provided).has("temporaryPassword")).isFalse();

		Map<String, Object> duplicate = form(fx, "Dup", "Email");
		duplicate.put("email", form.get("email"));
		MvcResult conflict = send(fx.host(), fx.adminToken(), post("/api/v1/students/v2"), duplicate);
		assertThat(status(conflict)).isEqualTo(409);

		// Staff without CREATE_EDIT and students cannot create.
		String auditor = staffToken(fx, Role.READ_ONLY_AUDITOR);
		assertThat(status(send(fx.host(), auditor, post("/api/v1/students/v2"), form(fx, "No", "Perm"))))
			.isEqualTo(403);
	}

	@Test
	void selfRegistrationAwaitingApprovalIsListedOnThePendingTabUntilApproved() {
		Fx fx = fixture("stu-v2-pending");
		MvcResult config = send(fx.host(), fx.adminToken(), put("/api/v1/tenant-config/{domain}", "STUDENT"),
				Map.of("approval_required", true));
		assertThat(status(config)).as(rawContent(config)).isEqualTo(200);
		Map<String, Object> registration = new LinkedHashMap<>();
		registration.put("name", "Pending Self");
		registration.put("email", email("self"));
		registration.put("password", "password123");
		MvcResult registered = send(fx.host(), null, post("/api/v1/students/register"), registration);
		assertThat(status(registered)).as(rawContent(registered)).isEqualTo(201);

		JsonNode pending = data(getAs(fx.host(), fx.adminToken(), "/api/v1/students/directory?tab=PENDING"));
		assertThat(pending.path("totalElements").asLong()).isEqualTo(1);
		JsonNode row = pending.path("content").get(0);
		assertThat(row.path("firstName").asString()).isEqualTo("Pending");
		assertThat(row.path("lastName").asString()).isEqualTo("Self");
		assertThat(row.path("status").asString()).isEqualTo("SUSPENDED");
		assertThat(data(getAs(fx.host(), fx.adminToken(), "/api/v1/students/directory")).path("totalElements")
			.asLong()).isZero();
		UUID id = UUID.fromString(row.path("id").asString());
		// Not a support-session target while pending.
		assertThat(status(send(fx.host(), fx.adminToken(), post("/api/v1/students/{id}/impersonation", id),
				Map.of("reason", "pending check")))).isEqualTo(409);

		MvcResult approved = send(fx.host(), fx.adminToken(), post("/api/v1/students/{id}/approve", id), null);
		assertThat(status(approved)).as(rawContent(approved)).isEqualTo(200);
		assertThat(data(getAs(fx.host(), fx.adminToken(), "/api/v1/students/directory?tab=PENDING"))
			.path("totalElements").asLong()).isZero();
		assertThat(data(getAs(fx.host(), fx.adminToken(), "/api/v1/students/directory")).path("content").get(0)
			.path("status").asString()).isEqualTo("ACTIVE");
		assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'student.approved' "
				+ "AND target_id = ?", fx.tenant().getId(), id)).isEqualTo(1);
	}

	@Test
	void concurrentCreatesGetUniqueIndexNumbers() throws Exception {
		Fx fx = fixture("stu-v2-conc");
		int n = 6;
		ExecutorService pool = Executors.newFixedThreadPool(n);
		try {
			List<Callable<MvcResult>> calls = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				Map<String, Object> form = form(fx, "Conc" + i, "Student");
				calls.add(() -> send(fx.host(), fx.adminToken(), post("/api/v1/students/v2"), form));
			}
			List<String> indexes = new ArrayList<>();
			for (Future<MvcResult> future : pool.invokeAll(calls)) {
				MvcResult result = future.get();
				assertThat(status(result)).as(rawContent(result)).isEqualTo(201);
				indexes.add(data(result).path("student").path("indexNumber").asString());
			}
			assertThat(new HashSet<>(indexes)).hasSize(n);
		}
		finally {
			pool.shutdownNow();
		}
	}

	@Test
	void approveAndRemoveKeepHistoryAndOtherTenantsGet404() {
		Fx fx = fixture("stu-v2-life");
		UUID id = createStudentId(fx, "Life", "Cycle");
		UUID userId = userIdOf(id);
		String studentToken = studentToken(fx, id);

		// Approve is only for pending students.
		assertThat(status(send(fx.host(), fx.adminToken(), post("/api/v1/students/{id}/approve", id), null)))
			.isEqualTo(409);
		jdbcTemplate.update("UPDATE student_profile SET pending_approval = true WHERE id = ?", id);
		suspendTenantUser(userId);
		MvcResult pendingList = getAs(fx.host(), fx.adminToken(), "/api/v1/students/directory?tab=PENDING");
		assertThat(data(pendingList).path("content")).hasSize(1);
		MvcResult approved = send(fx.host(), fx.adminToken(), post("/api/v1/students/{id}/approve", id), null);
		assertThat(status(approved)).as(rawContent(approved)).isEqualTo(200);
		assertThat(data(approved).path("pending").asBoolean()).isFalse();
		assertThat(data(approved).path("status").asString()).isEqualTo("ACTIVE");

		// Another tenant cannot approve, read or remove it.
		Fx other = fixture("stu-v2-life-other");
		assertThat(status(send(other.host(), other.adminToken(), post("/api/v1/students/{id}/approve", id), null)))
			.isEqualTo(404);
		assertThat(status(getAs(other.host(), other.adminToken(), "/api/v1/students/{id}/detail", id))).isEqualTo(404);
		assertThat(status(send(other.host(), other.adminToken(), put("/api/v1/students/{id}/detail", id),
				form(other, "Hijack", "Attempt")))).isEqualTo(404);
		assertThat(status(send(other.host(), other.adminToken(), delete("/api/v1/students/{id}", id),
				Map.of("reason", "cross tenant")))).isEqualTo(404);

		// Remove: needs DELETE (Student Support only has CREATE_EDIT) and a reason.
		String support = staffToken(fx, Role.STUDENT_SUPPORT);
		assertThat(status(send(fx.host(), support, delete("/api/v1/students/{id}", id), Map.of("reason", "x"))))
			.isEqualTo(403);
		assertThat(status(send(fx.host(), fx.adminToken(), delete("/api/v1/students/{id}", id), Map.of("reason", ""))))
			.isEqualTo(400);
		long participationBefore = count("SELECT count(*) FROM course_participation WHERE student_id = ?", userId);
		MvcResult removed = send(fx.host(), fx.adminToken(), delete("/api/v1/students/{id}", id),
				Map.of("reason", "Left the institute"));
		assertThat(status(removed)).as(rawContent(removed)).isEqualTo(204);

		// Soft: the rows stay; the account is suspended and its sessions revoked.
		assertThat(count("SELECT count(*) FROM student_profile WHERE id = ? AND removed_at IS NOT NULL "
				+ "AND removed_by = ? AND removal_reason = 'Left the institute'", id, fx.admin().getId())).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("SELECT status FROM tenant_user WHERE id = ?", String.class, userId))
			.isEqualTo("suspended");
		assertThat(count("SELECT count(*) FROM device_session WHERE user_id = ? AND status = 'active'", userId))
			.isZero();
		assertThat(count("SELECT count(*) FROM course_participation WHERE student_id = ?", userId))
			.isEqualTo(participationBefore);
		assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'student.removed' "
				+ "AND target_id = ? AND reason = 'Left the institute'", fx.tenant().getId(), id)).isEqualTo(1);
		// The student's own token stops working; the student is hidden and 404 to staff.
		assertThat(status(getAs(fx.host(), studentToken, "/api/v1/students/me"))).isEqualTo(401);
		assertThat(data(getAs(fx.host(), fx.adminToken(), "/api/v1/students/directory")).path("totalElements")
			.asLong()).isZero();
		assertThat(status(getAs(fx.host(), fx.adminToken(), "/api/v1/students/{id}/detail", id))).isEqualTo(404);
	}

}
