package com.lms.usermanagement.student.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.identityaccessservice.domain.Role;
import com.lms.usermanagement.student.StudentManagementTestSupport;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * STU-MGMT-02 directory and CSV export (spec sections 5 and 9): server-side search per
 * field, pagination over the whole tenant, one row per student even with several
 * enrollments, tabs, last login from device sessions, cross-tenant isolation, and CSV
 * formula-injection escaping over the full filtered scope without sensitive columns.
 */
class StudentDirectoryIntegrationTest extends StudentManagementTestSupport {

	private JsonNode directory(Fx fx, String query) {
		MvcResult result = getAs(fx.host(), fx.adminToken(), "/api/v1/students/directory" + query);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(200);
		return data(result);
	}

	private List<String> firstNames(JsonNode page) {
		List<String> names = new ArrayList<>();
		page.path("content").forEach(row -> names.add(row.path("firstName").asString()));
		return names;
	}

	@Test
	void searchesEveryFieldPaginatesSortsByIndexAndReturnsOneRowPerStudent() {
		Fx fx = fixture("stu-dir");
		UUID secondCourse = createCourseOrFail(fx.host(), fx.adminToken(), newCourseRequest(uniqueSlug("dir2"),
				fx.teacher().getId(), com.lms.coursemanagement.course.domain.CourseStatus.PUBLIC)).id();
		List<UUID> ids = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			Map<String, Object> form = form(fx, "Amal" + i, "Silva" + i);
			form.put("courseIds", List.of(fx.courseId().toString(), secondCourse.toString()));
			ids.add(UUID.fromString(createStudent(fx, form).path("id").asString()));
		}
		Map<String, Object> special = form(fx, "Nimali", "Jayasuriya");
		special.put("email", "nimali.special-" + UUID.randomUUID().toString().substring(0, 6) + "@example.test");
		special.put("mobile", "0761234500");
		UUID nimali = UUID.fromString(createStudent(fx, special).path("id").asString());

		JsonNode all = directory(fx, "?size=100");
		assertThat(all.path("totalElements").asLong()).isEqualTo(6);
		assertThat(all.path("content")).hasSize(6);
		// Sorted by index number.
		List<String> indexes = new ArrayList<>();
		all.path("content").forEach(row -> indexes.add(row.path("indexNumber").asString()));
		assertThat(indexes).isSorted();
		JsonNode first = all.path("content").get(0);
		assertThat(first.path("email").asString()).contains("@example.test");
		assertThat(first.path("status").asString()).isEqualTo("ACTIVE");
		assertThat(first.path("hasPhoto").asBoolean()).isFalse();

		// Pagination across the whole tenant.
		JsonNode page0 = directory(fx, "?size=4&page=0");
		JsonNode page1 = directory(fx, "?size=4&page=1");
		assertThat(page0.path("content")).hasSize(4);
		assertThat(page1.path("content")).hasSize(2);
		assertThat(page0.path("totalPages").asInt()).isEqualTo(2);
		// size is capped at 100.
		assertThat(directory(fx, "?size=1000").path("size").asInt()).isEqualTo(100);

		// Field searches.
		assertThat(firstNames(directory(fx, "?field=FIRST_NAME&q=nima"))).containsExactly("Nimali");
		assertThat(firstNames(directory(fx, "?field=LAST_NAME&q=jayas"))).containsExactly("Nimali");
		assertThat(firstNames(directory(fx, "?field=LAST_NAME&q=nima"))).isEmpty();
		assertThat(firstNames(directory(fx, "?field=EMAIL&q=nimali.special"))).containsExactly("Nimali");
		assertThat(firstNames(directory(fx, "?field=MOBILE&q=0761234500"))).containsExactly("Nimali");
		String nimaliIndex = jdbcTemplate.queryForObject("SELECT index_number FROM student_profile WHERE id = ?",
				String.class, nimali);
		assertThat(firstNames(directory(fx, "?field=INDEX&q=" + nimaliIndex))).containsExactly("Nimali");
		assertThat(firstNames(directory(fx, "?q=JAYASURIYA"))).containsExactly("Nimali");
		assertThat(directory(fx, "?q=silva").path("totalElements").asLong()).isEqualTo(5);
		// LIKE wildcards are literal.
		assertThat(directory(fx, "?q=%25").path("totalElements").asLong()).isZero();
		// Unknown tab / field are 400.
		assertThat(status(getAs(fx.host(), fx.adminToken(), "/api/v1/students/directory?tab=BLACKLIST"))).isEqualTo(400);

		// Pending tab lists only pending students.
		jdbcTemplate.update("UPDATE student_profile SET pending_approval = true WHERE id = ?", ids.get(0));
		assertThat(firstNames(directory(fx, "?tab=PENDING"))).containsExactly("Amal0");
		assertThat(directory(fx, "?tab=ACTIVE").path("totalElements").asLong()).isEqualTo(5);

		// Last login = latest device_session.issued_at of the account.
		String studentToken = studentToken(fx, nimali);
		assertThat(studentToken).isNotBlank();
		UUID nimaliUser = userIdOf(nimali);
		Instant later = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
		jdbcTemplate.update("INSERT INTO device_session (id, tenant_id, user_id, refresh_token_hash, "
				+ "device_identifier_hash, issued_at, expires_at, status) VALUES (?, ?, ?, ?, ?, ?, ?, 'expired')",
				UUID.randomUUID(), fx.tenant().getId(), nimaliUser, "hash-" + UUID.randomUUID(), "dev", Timestamp.from(later),
				Timestamp.from(later.plus(1, ChronoUnit.DAYS)));
		JsonNode nimaliRow = directory(fx, "?field=FIRST_NAME&q=nimali").path("content").get(0);
		assertThat(Instant.parse(nimaliRow.path("lastLoginAt").asString())).isEqualTo(later);
		assertThat(directory(fx, "?field=FIRST_NAME&q=amal1").path("content").get(0).path("lastLoginAt").isNull())
			.isTrue();

		// Other tenants see none of these students; students and teachers cannot list.
		Fx other = fixture("stu-dir-other");
		assertThat(directory(other, "?q=nimali").path("totalElements").asLong()).isZero();
		assertThat(directory(other, "").path("totalElements").asLong()).isZero();
		assertThat(status(getAs(fx.host(), studentToken, "/api/v1/students/directory"))).isEqualTo(403);
		String teacherToken = loginAndGetToken(fx.host(), "teacher@example.test");
		assertThat(status(getAs(fx.host(), teacherToken, "/api/v1/students/directory"))).isEqualTo(403);
		assertThat(status(getAs(fx.host(), null, "/api/v1/students/directory"))).isEqualTo(401);
		// A read-only staff role may view.
		assertThat(status(getAs(fx.host(), staffToken(fx, Role.READ_ONLY_AUDITOR), "/api/v1/students/directory")))
			.isEqualTo(200);
	}

	@Test
	void csvExportEscapesFormulasCoversTheFullFilteredScopeAndOmitsSensitiveColumns() {
		Fx fx = fixture("stu-csv");
		Map<String, Object> evil = form(fx, "=HYPERLINK(\"http://x\")", "+SUM(1,2)");
		evil.put("email", email("evil"));
		evil.put("guardianNic", "199912345678");
		evil.put("guardianName", "Secret Guardian");
		createStudent(fx, evil);
		Map<String, Object> at = form(fx, "@cmd", "-minus");
		at.put("email", email("at"));
		createStudent(fx, at);
		for (int i = 0; i < 3; i++) {
			createStudent(fx, form(fx, "Plain" + i, "Row"));
		}

		MvcResult result = getAs(fx.host(), fx.adminToken(), "/api/v1/students/directory/export.csv");
		assertThat(status(result)).isEqualTo(200);
		assertThat(result.getResponse().getContentType()).startsWith("text/csv");
		assertThat(result.getResponse().getHeader("Content-Disposition")).contains("attachment");
		String csv = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
		List<String> lines = csv.lines().toList();
		assertThat(lines.get(0)).isEqualTo("﻿Index Number,First Name,Last Name,Email,Mobile,Last Login,Status");
		assertThat(lines).hasSize(6);
		assertThat(csv).contains("\"'=HYPERLINK(\"\"http://x\"\")\"");
		assertThat(csv).contains("\"'+SUM(1,2)\"");
		assertThat(csv).contains(",'@cmd,'-minus,");
		assertThat(csv).doesNotContain("199912345678").doesNotContain("Secret Guardian");
		assertThat(csv).doesNotContain(",=").doesNotContain(",+").doesNotContain(",@");

		// Same filters as the directory.
		MvcResult filtered = getAs(fx.host(), fx.adminToken(), "/api/v1/students/directory/export.csv?q=plain");
		assertThat(new String(filtered.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8).lines())
			.hasSize(4);

		// Another tenant's export contains none of these rows.
		Fx other = fixture("stu-csv-other");
		MvcResult foreign = getAs(other.host(), other.adminToken(), "/api/v1/students/directory/export.csv");
		assertThat(new String(foreign.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8).lines())
			.hasSize(1);
		assertThat(status(getAs(fx.host(), null, "/api/v1/students/directory/export.csv"))).isEqualTo(401);
	}

	@Test
	void csvCellEscaping() {
		assertThat(com.lms.usermanagement.student.service.StudentDirectoryServiceTestAccess.cell("=1+1")).isEqualTo("'=1+1");
		assertThat(com.lms.usermanagement.student.service.StudentDirectoryServiceTestAccess.cell("\tx")).isEqualTo("'\tx");
		assertThat(com.lms.usermanagement.student.service.StudentDirectoryServiceTestAccess.cell("\rx"))
			.isEqualTo("\"'\rx\"");
		assertThat(com.lms.usermanagement.student.service.StudentDirectoryServiceTestAccess.cell("a,b")).isEqualTo("\"a,b\"");
		assertThat(com.lms.usermanagement.student.service.StudentDirectoryServiceTestAccess.cell("0771234567"))
			.isEqualTo("0771234567");
		assertThat(com.lms.usermanagement.student.service.StudentDirectoryServiceTestAccess.cell(null)).isEmpty();
	}

}
