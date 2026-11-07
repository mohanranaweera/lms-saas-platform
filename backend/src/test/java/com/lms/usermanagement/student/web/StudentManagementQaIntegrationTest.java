package com.lms.usermanagement.student.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.identityaccessservice.domain.Role;
import com.lms.usermanagement.student.StudentManagementTestSupport;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * STU-MGMT-02 independent QA gap coverage (acceptance matrix): removal keeps payment /
 * ledger / participation / audit history and leaves a same-email/mobile student of
 * another tenant untouched; every new per-student endpoint is 404 to another tenant's
 * admin with no side effect; role boundaries; add-only course selection on edit; remarks
 * stored verbatim; and a 409 on an existing email leaves that account's password intact.
 */
class StudentManagementQaIntegrationTest extends StudentManagementTestSupport {

	private static byte[] png(int size) {
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setColor(Color.GREEN);
		g.fillRect(0, 0, size, size);
		g.dispose();
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ImageIO.write(image, "png", out);
			return out.toByteArray();
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	private static MockMultipartFile photoFile() {
		return new MockMultipartFile("file", "p.png", "image/png", png(120));
	}

	private String csv(Fx fx, String query) {
		MvcResult result = getAs(fx.host(), fx.adminToken(), "/api/v1/students/directory/export.csv" + query);
		assertThat(status(result)).isEqualTo(200);
		return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
	}

	private long directoryTotal(Fx fx, String query) {
		MvcResult result = getAs(fx.host(), fx.adminToken(), "/api/v1/students/directory" + query);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(200);
		return data(result).path("totalElements").asLong();
	}

	/** A confirmed payment with its ledger entry for the student (append-only history). */
	private void seedPaidHistory(Fx fx, UUID studentUserId) {
		UUID orderId = UUID.randomUUID();
		UUID paymentId = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO student_order (id, tenant_id, student_id, course_id, amount, currency, status, "
				+ "created_at, updated_at, subtotal_amount, facility_fee_amount, discount_amount) "
				+ "VALUES (?, ?, ?, ?, 2500.00, 'LKR', 'PLACED', now(), now(), 2500.00, 0, 0)", orderId,
				fx.tenant().getId(), studentUserId, fx.courseId());
		jdbcTemplate.update("INSERT INTO payment (id, tenant_id, order_id, amount, currency, status, gateway_reference, "
				+ "confirmed_at, created_at, updated_at) VALUES (?, ?, ?, 2500.00, 'LKR', 'CONFIRMED', ?, now(), now(), "
				+ "now())", paymentId, fx.tenant().getId(), orderId, "qa-" + paymentId);
		jdbcTemplate.update("INSERT INTO ledger_entry (id, tenant_id, order_id, payment_id, entry_type, amount, "
				+ "created_at) VALUES (?, ?, ?, ?, 'PAYMENT_CONFIRMED', ?, now())", UUID.randomUUID(),
				fx.tenant().getId(), orderId, paymentId, new BigDecimal("2500.00"));
	}

	private long financialRows(UUID tenantId, UUID studentUserId) {
		return count("SELECT count(*) FROM student_order WHERE tenant_id = ? AND student_id = ?", tenantId,
				studentUserId)
				+ count("SELECT count(*) FROM payment p JOIN student_order o ON o.id = p.order_id "
						+ "WHERE p.tenant_id = ? AND o.student_id = ?", tenantId, studentUserId)
				+ count("SELECT count(*) FROM ledger_entry l JOIN student_order o ON o.id = l.order_id "
						+ "WHERE l.tenant_id = ? AND o.student_id = ?", tenantId, studentUserId);
	}

	// ---- Item 1 --------------------------------------------------------------------

	@Test
	void removalKeepsHistoryHidesTheStudentEverywhereAndLeavesTheOtherTenantsTwinUntouched() {
		Fx fx = fixture("qa-rm");
		Fx other = fixture("qa-rm-other");
		Map<String, Object> form = form(fx, "Gone", "Student");
		UUID id = UUID.fromString(createStudent(fx, form).path("id").asString());
		UUID kept = createStudentId(fx, "Kept", "Student");
		// Same email and mobile in another tenant.
		Map<String, Object> twinForm = form(other, "Twin", "Student");
		twinForm.put("email", form.get("email"));
		twinForm.put("mobile", form.get("mobile"));
		UUID twin = UUID.fromString(createStudent(other, twinForm).path("id").asString());
		UUID userId = userIdOf(id);
		UUID twinUser = userIdOf(twin);
		String studentToken = studentToken(fx, id);
		String twinToken = studentToken(other, twin);
		seedPaidHistory(fx, userId);
		send(fx.host(), fx.adminToken(), post("/api/v1/students/{id}/remarks", id), Map.of("body", "History"));
		jdbcTemplate.update("UPDATE student_profile SET pending_approval = true WHERE id = ?", id);
		assertThat(directoryTotal(fx, "?tab=PENDING")).isEqualTo(1);

		long participation = count("SELECT count(*) FROM course_participation WHERE student_id = ?", userId);
		long audit = count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND target_id = ?",
				fx.tenant().getId(), id);
		long allAudit = count("SELECT count(*) FROM audit_log WHERE tenant_id = ?", fx.tenant().getId());
		long financial = financialRows(fx.tenant().getId(), userId);
		assertThat(participation).isEqualTo(1);
		assertThat(audit).isGreaterThanOrEqualTo(1);
		assertThat(financial).isEqualTo(3);
		String twinHash = jdbcTemplate.queryForObject("SELECT password_hash FROM tenant_user WHERE id = ?",
				String.class, twinUser);

		MvcResult removed = send(fx.host(), fx.adminToken(), delete("/api/v1/students/{id}", id),
				Map.of("reason", "QA removal"));
		assertThat(status(removed)).as(rawContent(removed)).isEqualTo(204);

		// History intact: participation, all earlier audit rows (+1 removal), order/payment/ledger, remarks.
		assertThat(count("SELECT count(*) FROM course_participation WHERE student_id = ?", userId))
			.isEqualTo(participation);
		assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND target_id = ?", fx.tenant().getId(),
				id)).isEqualTo(audit + 1);
		assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ?", fx.tenant().getId()))
			.isEqualTo(allAudit + 1);
		assertThat(financialRows(fx.tenant().getId(), userId)).isEqualTo(financial);
		assertThat(count("SELECT count(*) FROM student_remark WHERE student_profile_id = ?", id)).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM student_profile WHERE id = ?", id)).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM tenant_user WHERE id = ?", userId)).isEqualTo(1);

		// Gone from ACTIVE, PENDING, search and CSV; the kept student is still listed.
		assertThat(directoryTotal(fx, "?tab=PENDING")).isZero();
		assertThat(directoryTotal(fx, "?tab=ACTIVE")).isEqualTo(1);
		assertThat(directoryTotal(fx, "?field=EMAIL&q=" + form.get("email"))).isZero();
		assertThat(directoryTotal(fx, "?tab=PENDING&field=EMAIL&q=" + form.get("email"))).isZero();
		String export = csv(fx, "");
		assertThat(export).doesNotContain((String) form.get("email")).contains(emailOf(kept));
		assertThat(csv(fx, "?tab=PENDING").lines()).hasSize(1);

		// Sessions revoked; cannot sign in again.
		assertThat(status(getAs(fx.host(), studentToken, "/api/v1/students/me"))).isEqualTo(401);
		assertThat(count("SELECT count(*) FROM device_session WHERE user_id = ? AND status = 'active'", userId))
			.isZero();
		assertThat(login(fx.host(), (String) form.get("email"), RAW_PASSWORD).getStatusCode().value())
			.isNotEqualTo(200);

		// The other tenant's twin (same email + mobile) is unaffected.
		assertThat(status(getAs(other.host(), twinToken, "/api/v1/students/me"))).isEqualTo(200);
		assertThat(jdbcTemplate.queryForObject("SELECT status FROM tenant_user WHERE id = ?", String.class, twinUser))
			.isEqualTo("active");
		assertThat(jdbcTemplate.queryForObject("SELECT password_hash FROM tenant_user WHERE id = ?", String.class,
				twinUser)).isEqualTo(twinHash);
		assertThat(count("SELECT count(*) FROM student_profile WHERE id = ? AND removed_at IS NULL", twin))
			.isEqualTo(1);
		assertThat(count("SELECT count(*) FROM device_session WHERE user_id = ? AND status = 'active'", twinUser))
			.isGreaterThanOrEqualTo(1);
		assertThat(status(getAs(other.host(), other.adminToken(), "/api/v1/students/{id}/detail", twin)))
			.isEqualTo(200);
		assertThat(directoryTotal(other, "?field=EMAIL&q=" + form.get("email"))).isEqualTo(1);
		assertThat(login(other.host(), (String) form.get("email"), RAW_PASSWORD).getStatusCode().value())
			.isEqualTo(200);
	}

	// ---- Item 2 --------------------------------------------------------------------

	@Test
	void everyPerStudentEndpointIs404ToAnotherTenantsAdminWithNoSideEffect() {
		Fx fx = fixture("qa-xt");
		Fx attacker = fixture("qa-xt-att");
		UUID id = createStudentId(fx, "Victim", "Student");
		assertThat(status(upload(fx.host(), fx.adminToken(), "/api/v1/students/{id}/photo", photoFile(), id)))
			.isEqualTo(200);
		send(fx.host(), fx.adminToken(), post("/api/v1/students/{id}/remarks", id), Map.of("body", "Private note"));
		// Pending, so a leaking approve would visibly succeed.
		jdbcTemplate.update("UPDATE student_profile SET pending_approval = true WHERE id = ?", id);
		String qrBefore = jdbcTemplate.queryForObject("SELECT qr_token FROM student_profile WHERE id = ?", String.class,
				id);
		UUID photoBefore = jdbcTemplate.queryForObject("SELECT profile_media_file_id FROM student_profile WHERE id = ?",
				UUID.class, id);
		String nameBefore = jdbcTemplate.queryForObject("SELECT name FROM student_profile WHERE id = ?", String.class,
				id);
		long auditBefore = count("SELECT count(*) FROM audit_log WHERE tenant_id = ?", fx.tenant().getId());

		String h = attacker.host();
		String t = attacker.adminToken();
		Map<String, Supplier<MvcResult>> calls = new LinkedHashMap<>();
		calls.put("GET detail", () -> getAs(h, t, "/api/v1/students/{id}/detail", id));
		calls.put("PUT detail", () -> send(h, t, put("/api/v1/students/{id}/detail", id),
				form(attacker, "Hijack", "Attempt")));
		calls.put("POST approve", () -> send(h, t, post("/api/v1/students/{id}/approve", id), null));
		calls.put("DELETE student", () -> send(h, t, delete("/api/v1/students/{id}", id),
				Map.of("reason", "cross tenant")));
		calls.put("GET photo", () -> getAs(h, t, "/api/v1/students/{id}/photo", id));
		calls.put("POST photo", () -> upload(h, t, "/api/v1/students/{id}/photo", photoFile(), id));
		calls.put("DELETE photo", () -> send(h, t, delete("/api/v1/students/{id}/photo", id), null));
		calls.put("GET remarks", () -> getAs(h, t, "/api/v1/students/{id}/remarks", id));
		calls.put("POST remarks", () -> send(h, t, post("/api/v1/students/{id}/remarks", id),
				Map.of("body", "cross tenant")));
		calls.put("GET qr", () -> getAs(h, t, "/api/v1/students/{id}/qr", id));
		calls.put("GET qr.png", () -> getAs(h, t, "/api/v1/students/{id}/qr.png", id));
		calls.put("POST qr/rotate", () -> send(h, t, post("/api/v1/students/{id}/qr/rotate", id), null));
		calls.put("POST impersonation", () -> send(h, t, post("/api/v1/students/{id}/impersonation", id),
				Map.of("reason", "cross tenant attempt")));

		for (Map.Entry<String, Supplier<MvcResult>> call : calls.entrySet()) {
			MvcResult result = call.getValue().get();
			assertThat(status(result)).as(call.getKey() + " -> " + rawContent(result)).isEqualTo(404);
			assertThat(rawContent(result)).as(call.getKey()).doesNotContain("Victim").doesNotContain("Private note");
		}

		// Nothing changed in the victim tenant.
		assertThat(jdbcTemplate.queryForObject("SELECT qr_token FROM student_profile WHERE id = ?", String.class, id))
			.isEqualTo(qrBefore);
		assertThat(jdbcTemplate.queryForObject("SELECT profile_media_file_id FROM student_profile WHERE id = ?",
				UUID.class, id)).isEqualTo(photoBefore);
		assertThat(jdbcTemplate.queryForObject("SELECT name FROM student_profile WHERE id = ?", String.class, id))
			.isEqualTo(nameBefore);
		assertThat(count("SELECT count(*) FROM student_profile WHERE id = ? AND pending_approval = true "
				+ "AND removed_at IS NULL", id)).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM student_remark WHERE student_profile_id = ?", id)).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM impersonation_session WHERE tenant_id IN (?, ?)", fx.tenant().getId(),
				attacker.tenant().getId())).isZero();
		assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ?", fx.tenant().getId()))
			.isEqualTo(auditBefore);
		assertThat(count("SELECT count(*) FROM media_file WHERE tenant_id = ?", attacker.tenant().getId())).isZero();
	}

	// ---- Item 3 --------------------------------------------------------------------

	@Test
	void rolesWithoutStudentsPermissionAndStudentsAreForbidden() {
		Fx fx = fixture("qa-role");
		UUID me = createStudentId(fx, "Self", "Kid");
		UUID otherKid = createStudentId(fx, "Other", "Kid");
		assertThat(status(upload(fx.host(), fx.adminToken(), "/api/v1/students/{id}/photo", photoFile(), otherKid)))
			.isEqualTo(200);
		send(fx.host(), fx.adminToken(), post("/api/v1/students/{id}/remarks", otherKid), Map.of("body", "Note"));

		String teacher = loginAndGetToken(fx.host(), "teacher@example.test");
		// TEACHER_ASSISTANT has no STUDENTS grant in the RBAC matrix (every staff sub-role has VIEW).
		String assistant = staffToken(fx, Role.TEACHER_ASSISTANT);
		for (Map.Entry<String, String> who : Map.of("TEACHER", teacher, "TEACHER_ASSISTANT", assistant).entrySet()) {
			String tok = who.getValue();
			Map<String, MvcResult> results = new LinkedHashMap<>();
			results.put("directory", getAs(fx.host(), tok, "/api/v1/students/directory"));
			results.put("csv", getAs(fx.host(), tok, "/api/v1/students/directory/export.csv"));
			results.put("detail", getAs(fx.host(), tok, "/api/v1/students/{id}/detail", otherKid));
			results.put("remarks GET", getAs(fx.host(), tok, "/api/v1/students/{id}/remarks", otherKid));
			results.put("remarks POST", send(fx.host(), tok, post("/api/v1/students/{id}/remarks", otherKid),
					Map.of("body", "nope")));
			results.put("photo GET", getAs(fx.host(), tok, "/api/v1/students/{id}/photo", otherKid));
			results.put("qr", getAs(fx.host(), tok, "/api/v1/students/{id}/qr", otherKid));
			results.forEach((name, result) -> {
				assertThat(status(result)).as(who.getKey() + " " + name + " " + rawContent(result)).isEqualTo(403);
				assertThat(rawContent(result)).as(who.getKey() + " " + name).doesNotContain("Note");
			});
		}

		// STUDENT_SUPPORT has STUDENTS VIEW/CREATE_EDIT but not STUDENT_IMPERSONATION.
		String support = staffToken(fx, Role.STUDENT_SUPPORT);
		assertThat(status(getAs(fx.host(), support, "/api/v1/students/directory"))).isEqualTo(200);
		assertThat(status(send(fx.host(), support, post("/api/v1/students/{id}/impersonation", otherKid),
				Map.of("reason", "support attempt")))).isEqualTo(403);
		assertThat(count("SELECT count(*) FROM impersonation_session WHERE tenant_id = ?", fx.tenant().getId()))
			.isZero();

		// A student cannot read another student's photo or remarks.
		String studentToken = studentToken(fx, me);
		MvcResult photo = getAs(fx.host(), studentToken, "/api/v1/students/{id}/photo", otherKid);
		assertThat(status(photo)).isEqualTo(403);
		assertThat(rawContent(photo)).doesNotContain("test-object-storage");
		MvcResult remarks = getAs(fx.host(), studentToken, "/api/v1/students/{id}/remarks", otherKid);
		assertThat(status(remarks)).isEqualTo(403);
		assertThat(rawContent(remarks)).doesNotContain("Note");
	}

	// ---- Item 4 --------------------------------------------------------------------

	@Test
	void editCourseSelectionIsAddOnlyAndNeverCreatesFinancialRows() {
		Fx fx = fixture("qa-add");
		Map<String, Object> form = form(fx, "Add", "Only");
		UUID id = UUID.fromString(createStudent(fx, form).path("id").asString());
		UUID userId = userIdOf(id);
		UUID secondCourse = createCourseOrFail(fx.host(), fx.adminToken(),
				newCourseRequest(uniqueSlug("qa2"), fx.teacher().getId(), CourseStatus.PUBLIC)).id();
		UUID originalParticipation = jdbcTemplate.queryForObject(
				"SELECT id FROM course_participation WHERE student_id = ? AND course_id = ?", UUID.class, userId,
				fx.courseId());

		Map<String, Object> edit = new LinkedHashMap<>(form);
		edit.remove("password");
		edit.put("courseIds", List.of(secondCourse.toString()));
		for (int i = 0; i < 2; i++) {
			MvcResult result = send(fx.host(), fx.adminToken(), put("/api/v1/students/{id}/detail", id), edit);
			assertThat(status(result)).as(rawContent(result)).isEqualTo(200);
			assertThat(data(result).path("enrolledCourseIds")).hasSize(2);
		}

		// The omitted course is still enrolled (same row); the added one exists exactly once.
		assertThat(jdbcTemplate.queryForObject(
				"SELECT id FROM course_participation WHERE student_id = ? AND course_id = ?", UUID.class, userId,
				fx.courseId())).isEqualTo(originalParticipation);
		assertThat(count("SELECT count(*) FROM course_participation WHERE student_id = ? AND course_id = ?", userId,
				secondCourse)).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM course_participation WHERE student_id = ?", userId)).isEqualTo(2);
		for (String table : List.of("student_order", "payment", "ledger_entry", "enrollment", "content_entitlement")) {
			assertThat(count("SELECT count(*) FROM " + table + " WHERE tenant_id = ?", fx.tenant().getId()))
				.as(table)
				.isZero();
		}
		// An empty selection on edit removes nothing either.
		edit.put("courseIds", List.of());
		MvcResult empty = send(fx.host(), fx.adminToken(), put("/api/v1/students/{id}/detail", id), edit);
		assertThat(status(empty)).as(rawContent(empty)).isIn(200, 400);
		assertThat(count("SELECT count(*) FROM course_participation WHERE student_id = ?", userId)).isEqualTo(2);
	}

	// ---- Item 5 --------------------------------------------------------------------

	@Test
	void remarkMarkupIsStoredAndReturnedVerbatimAsJsonText() {
		Fx fx = fixture("qa-xss");
		UUID id = createStudentId(fx, "Xss", "Target");
		String body = "<script>alert('x')</script><img src=x onerror=alert(1)> & \"quoted\"";
		MvcResult added = send(fx.host(), fx.adminToken(), post("/api/v1/students/{id}/remarks", id),
				Map.of("body", body));
		assertThat(status(added)).as(rawContent(added)).isEqualTo(201);
		assertThat(added.getResponse().getContentType()).startsWith("application/json");
		assertThat(data(added).path("body").asString()).isEqualTo(body);
		assertThat(jdbcTemplate.queryForObject("SELECT body FROM student_remark WHERE student_profile_id = ?",
				String.class, id)).isEqualTo(body);
		MvcResult listed = getAs(fx.host(), fx.adminToken(), "/api/v1/students/{id}/remarks", id);
		assertThat(listed.getResponse().getContentType()).startsWith("application/json");
		assertThat(data(listed).path("content").get(0).path("body").asString()).isEqualTo(body);
	}

	// ---- Item 6 --------------------------------------------------------------------

	@Test
	void existingEmailConflictLeavesTheExistingAccountUntouched() {
		Fx fx = fixture("qa-dup");
		Map<String, Object> original = form(fx, "Original", "Owner");
		UUID id = UUID.fromString(createStudent(fx, original).path("id").asString());
		UUID userId = userIdOf(id);
		Map<String, Object> before = jdbcTemplate.queryForMap(
				"SELECT password_hash, status, role, login_mobile, must_change_password FROM tenant_user WHERE id = ?",
				userId);
		long users = count("SELECT count(*) FROM tenant_user WHERE tenant_id = ?", fx.tenant().getId());
		long profiles = count("SELECT count(*) FROM student_profile WHERE tenant_id = ?", fx.tenant().getId());

		String email = (String) original.get("email");
		for (String variant : List.of(email)) {
			Map<String, Object> dup = form(fx, "Dup", "Attempt");
			dup.put("email", variant);
			dup.put("password", "A-different-password-999");
			MvcResult conflict = send(fx.host(), fx.adminToken(), post("/api/v1/students/v2"), dup);
			assertThat(status(conflict)).as(variant + " " + rawContent(conflict)).isEqualTo(409);
		}
		// The admin's own email is also an existing account.
		Map<String, Object> admin = form(fx, "Admin", "Clash");
		admin.put("email", "admin@example.test");
		assertThat(status(send(fx.host(), fx.adminToken(), post("/api/v1/students/v2"), admin))).isEqualTo(409);

		assertThat(jdbcTemplate.queryForMap(
				"SELECT password_hash, status, role, login_mobile, must_change_password FROM tenant_user WHERE id = ?",
				userId)).isEqualTo(before);
		assertThat(count("SELECT count(*) FROM tenant_user WHERE tenant_id = ?", fx.tenant().getId())).isEqualTo(users);
		assertThat(count("SELECT count(*) FROM student_profile WHERE tenant_id = ?", fx.tenant().getId()))
			.isEqualTo(profiles);
		assertThat(login(fx.host(), email, RAW_PASSWORD).getStatusCode().value()).isEqualTo(200);
		assertThat(login(fx.host(), email, "A-different-password-999").getStatusCode().value()).isNotEqualTo(200);

		// A taken mobile is also a conflict, with no orphan account left behind.
		Map<String, Object> mobileDup = form(fx, "Mobile", "Dup");
		mobileDup.put("mobile", original.get("mobile"));
		MvcResult mobileConflict = send(fx.host(), fx.adminToken(), post("/api/v1/students/v2"), mobileDup);
		assertThat(status(mobileConflict)).as(rawContent(mobileConflict)).isEqualTo(409);
		assertThat(count("SELECT count(*) FROM tenant_user WHERE tenant_id = ? AND email = ?", fx.tenant().getId(),
				mobileDup.get("email"))).isZero();
		assertThat(count("SELECT count(*) FROM tenant_user WHERE tenant_id = ?", fx.tenant().getId())).isEqualTo(users);
	}

	/**
	 * QA finding (app bug, not fixed here): the create path compares emails case-sensitively
	 * ({@code UserProvisioningService.existsByEmail} -> exact {@code findByEmail}; DB
	 * {@code UNIQUE (tenant_id, email)} is case-sensitive), so an upper-case variant of an
	 * existing student's email creates a second account for the same mailbox, while
	 * {@code PasswordResetService} lower-cases before lookup. Enable once emails are
	 * normalised on create.
	 */
	@Test
	void upperCaseVariantOfAnExistingEmailIsAConflict() {
		Fx fx = fixture("qa-dup-case");
		Map<String, Object> original = form(fx, "Original", "Owner");
		createStudent(fx, original);
		Map<String, Object> dup = form(fx, "Dup", "Attempt");
		dup.put("email", ((String) original.get("email")).toUpperCase());
		MvcResult conflict = send(fx.host(), fx.adminToken(), post("/api/v1/students/v2"), dup);
		assertThat(status(conflict)).as(rawContent(conflict)).isEqualTo(409);
	}

}
