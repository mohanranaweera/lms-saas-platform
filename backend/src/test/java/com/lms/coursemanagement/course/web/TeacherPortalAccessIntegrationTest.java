package com.lms.coursemanagement.course.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.lms.coursemanagement.CourseManagementTestSupport;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.tenantmanagement.domain.Tenant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * Teacher Portal (TP-01/TP-02/TP-03): "My Courses" and the course view are
 * driven by {@code course_teacher_assignment} (primary or co-teacher) for the
 * authenticated caller only; a co-teacher may view but not manage; removing an
 * assignment takes effect on the next request; a staff caller asking for
 * {@code assignedToMe} sees only its own assignments; other tenants' ids are
 * 404. My Profile reads/writes only the caller's own record and only the
 * self-editable allowlist.
 */
class TeacherPortalAccessIntegrationTest extends CourseManagementTestSupport {

	private record Ctx(Tenant tenant, String host, String adminToken, String leadToken, String coToken,
			String otherToken, TenantUser lead, TenantUser co, TenantUser other) {

	}

	private Ctx seed(String prefix) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain(prefix));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser lead = seedTenantUser(tenant.getId(), "lead@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser co = seedTenantUser(tenant.getId(), "co@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser other = seedTenantUser(tenant.getId(), "other@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		return new Ctx(tenant, host, loginAndGetToken(host, "admin@example.test"),
				loginAndGetToken(host, "lead@example.test"), loginAndGetToken(host, "co@example.test"),
				loginAndGetToken(host, "other@example.test"), lead, co, other);
	}

	private MvcResult send(String host, String token, MockHttpServletRequestBuilder builder, Object body) {
		if (body != null) {
			builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
		}
		return perform(authenticated(builder, host, token));
	}

	private static Map<String, Object> courseBody(String name, List<UUID> teacherIds) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("name", name);
		body.put("teacherIds", teacherIds);
		body.put("pricingModel", "MONTHLY");
		body.put("price", "4000.00");
		return body;
	}

	private String createCourse(Ctx ctx, String name, List<UUID> teacherIds) {
		MvcResult created = send(ctx.host(), ctx.adminToken(), post("/api/v1/courses"), courseBody(name, teacherIds));
		assertThat(created.getResponse().getStatus()).as(rawContent(created)).isEqualTo(201);
		return objectMapper.readTree(rawContent(created)).path("data").path("id").asString();
	}

	private JsonNode data(MvcResult result) {
		return objectMapper.readTree(rawContent(result)).path("data");
	}

	private List<String> listIds(Ctx ctx, String token, String query) {
		MvcResult result = send(ctx.host(), token, get("/api/v1/courses?" + query), null);
		assertThat(result.getResponse().getStatus()).as(rawContent(result)).isEqualTo(200);
		List<String> ids = new ArrayList<>();
		data(result).path("content").forEach(node -> ids.add(node.path("id").asString()));
		return ids;
	}

	private int status(Ctx ctx, String token, MockHttpServletRequestBuilder builder) {
		return send(ctx.host(), token, builder, null).getResponse().getStatus();
	}

	@Test
	void myCoursesListsOnlyTheCallersAssignmentsBeforeSearchCountsAndPaging() {
		Ctx ctx = seed("tp01-list");
		String shared = createCourse(ctx, "Biology Joint", List.of(ctx.lead().getId(), ctx.co().getId()));
		String leadOnly = createCourse(ctx, "Biology Solo", List.of(ctx.lead().getId()));
		String othersCourse = createCourse(ctx, "Biology Other", List.of(ctx.other().getId()));

		// Co-teacher: the jointly assigned course only - even though they did not create it.
		assertThat(listIds(ctx, ctx.coToken(), "assignedToMe=true")).containsExactly(shared);
		assertThat(listIds(ctx, ctx.coToken(), "assignedToMe=true&search=Biology")).containsExactly(shared);
		// A Teacher cannot widen with a client teacherId; the assignment scope always wins.
		assertThat(listIds(ctx, ctx.coToken(), "teacherId=" + ctx.other().getId())).containsExactly(shared);

		// Lead: both of theirs; counts and pages are computed on the restricted set.
		assertThat(listIds(ctx, ctx.leadToken(), "assignedToMe=true")).containsExactlyInAnyOrder(shared, leadOnly);
		MvcResult paged = send(ctx.host(), ctx.leadToken(), get("/api/v1/courses?assignedToMe=true&size=1&page=1"),
				null);
		assertThat(data(paged).path("totalElements").asLong()).isEqualTo(2);
		assertThat(data(paged).path("totalPages").asInt()).isEqualTo(2);
		assertThat(data(paged).path("content").size()).isEqualTo(1);
		assertThat(listIds(ctx, ctx.leadToken(), "search=Other")).isEmpty();

		// Tenant Admin keeps full course management; "assigned to me" narrows to its own (none).
		assertThat(listIds(ctx, ctx.adminToken(), "")).contains(shared, leadOnly, othersCourse);
		MvcResult adminMine = send(ctx.host(), ctx.adminToken(), get("/api/v1/courses?assignedToMe=true"), null);
		assertThat(adminMine.getResponse().getStatus()).isEqualTo(200);
		assertThat(data(adminMine).path("totalElements").asLong()).isZero();
	}

	@Test
	void coTeacherMayOpenTheCourseViewButNotManageIt_andRemovalRevokesOnTheNextRequest() {
		Ctx ctx = seed("tp01-access");
		String shared = createCourse(ctx, "Chemistry", List.of(ctx.lead().getId(), ctx.co().getId()));
		String othersCourse = createCourse(ctx, "Physics", List.of(ctx.other().getId()));
		UUID moduleId = createModuleOrFail(ctx.host(), ctx.adminToken(), UUID.fromString(shared), "Week 1", 1).id();
		createLessonOrFail(ctx.host(), ctx.adminToken(), UUID.fromString(shared), moduleId, "Atoms", 1);

		assertThat(status(ctx, ctx.coToken(), get("/api/v1/courses/" + shared))).isEqualTo(200);
		assertThat(status(ctx, ctx.coToken(), get("/api/v1/courses/" + shared + "/modules"))).isEqualTo(200);
		MvcResult content = send(ctx.host(), ctx.coToken(), get("/api/v1/courses/" + shared + "/content"), null);
		assertThat(content.getResponse().getStatus()).as(rawContent(content)).isEqualTo(200);
		JsonNode lesson = data(content).path("periods").get(0).path("modules").get(0).path("lessons").get(0);
		assertThat(lesson.path("title").asString()).isEqualTo("Atoms");
		assertThat(lesson.path("access").path("status").asString()).isEqualTo("ALLOWED");
		assertThat(lesson.path("access").path("requiredPurchase").isNull()).isTrue();
		// The teacher preview never launches (student-only) - so no progress write.
		assertThat(status(ctx, ctx.coToken(),
				post("/api/v1/courses/" + shared + "/lessons/" + lesson.path("lessonId").asString() + "/launch")))
			.isEqualTo(403);

		// No new edit rights for a co-teacher.
		MvcResult edit = send(ctx.host(), ctx.coToken(), patch("/api/v1/courses/" + shared),
				Map.of("description", "changed"));
		assertThat(edit.getResponse().getStatus()).isEqualTo(403);

		// Unassigned course in the same tenant: denied for the direct link and its content.
		assertThat(status(ctx, ctx.coToken(), get("/api/v1/courses/" + othersCourse))).isEqualTo(403);
		assertThat(status(ctx, ctx.coToken(), get("/api/v1/courses/" + othersCourse + "/content"))).isEqualTo(403);
		assertThat(status(ctx, ctx.coToken(), get("/api/v1/courses/" + othersCourse + "/modules"))).isEqualTo(403);

		// Admin removes the co-teacher: the very next request is denied.
		MvcResult removed = send(ctx.host(), ctx.adminToken(), patch("/api/v1/courses/" + shared),
				courseBody("Chemistry", List.of(ctx.lead().getId())));
		assertThat(removed.getResponse().getStatus()).as(rawContent(removed)).isEqualTo(200);
		assertThat(status(ctx, ctx.coToken(), get("/api/v1/courses/" + shared))).isEqualTo(403);
		assertThat(status(ctx, ctx.coToken(), get("/api/v1/courses/" + shared + "/content"))).isEqualTo(403);
		assertThat(listIds(ctx, ctx.coToken(), "assignedToMe=true")).isEmpty();
		// The lead teacher is unaffected.
		assertThat(status(ctx, ctx.leadToken(), get("/api/v1/courses/" + shared + "/content"))).isEqualTo(200);
	}

	@Test
	void anotherTenantsTeacherGets404ForCourseAndContentLinks() {
		Ctx a = seed("tp01-tenant-a");
		Ctx b = seed("tp01-tenant-b");
		String courseA = createCourse(a, "Tenant A course", List.of(a.lead().getId()));

		assertThat(status(b, b.leadToken(), get("/api/v1/courses/" + courseA))).isEqualTo(404);
		assertThat(status(b, b.leadToken(), get("/api/v1/courses/" + courseA + "/content"))).isEqualTo(404);
		assertThat(listIds(b, b.leadToken(), "assignedToMe=true")).isEmpty();
	}

	// ------------------------------------------------------------------
	// TP-03 My Profile.
	// ------------------------------------------------------------------

	private record Teacher(UUID profileId, String token) {

	}

	private Teacher createApprovedTeacher(Ctx ctx, String email) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("name", "Profile " + email);
		body.put("email", email);
		body.put("password", "password123");
		MvcResult created = send(ctx.host(), ctx.adminToken(), post("/api/v1/teachers"), body);
		assertThat(created.getResponse().getStatus()).as(rawContent(created)).isEqualTo(201);
		UUID id = UUID.fromString(data(created).path("id").asString());
		MvcResult approved = send(ctx.host(), ctx.adminToken(), post("/api/v1/teachers/" + id + "/approve"), null);
		assertThat(approved.getResponse().getStatus()).as(rawContent(approved)).isIn(200, 409);
		// Fixture only: skip the first-login password change for the admin-created account.
		jdbcTemplate.update("UPDATE tenant_user SET must_change_password = false WHERE tenant_id = ? AND email = ?",
				ctx.tenant().getId(), email);
		String token = login(ctx.host(), email, "password123").getBody().data().accessToken();
		return new Teacher(id, token);
	}

	@Test
	void myProfileReadsAndUpdatesOnlyTheCallersOwnAllowlistedFields() {
		Ctx ctx = seed("tp03-profile");
		Teacher one = createApprovedTeacher(ctx, "one@example.test");
		Teacher two = createApprovedTeacher(ctx, "two@example.test");

		MvcResult mine = send(ctx.host(), one.token(), get("/api/v1/teachers/me"), null);
		assertThat(mine.getResponse().getStatus()).as(rawContent(mine)).isEqualTo(200);
		assertThat(data(mine).path("id").asString()).isEqualTo(one.profileId().toString());
		assertThat(data(mine).path("email").asString()).isEqualTo("one@example.test");
		assertThat(data(mine).has("instituteCommissionPercent")).isFalse();
		assertThat(data(mine).path("editableFields").toString()).contains("qualifications", "description");

		Map<String, Object> update = new LinkedHashMap<>();
		update.put("qualifications", "BSc (Hons) Chemistry");
		update.put("description", "Ten years teaching A/L chemistry.");
		// Not on the allowlist - must be ignored, never written.
		update.put("firstName", "Hacked");
		update.put("contactNumber", "0000");
		update.put("instituteCommissionPercent", "0.00");
		update.put("id", two.profileId().toString());
		MvcResult saved = send(ctx.host(), one.token(), patch("/api/v1/teachers/me"), update);
		assertThat(saved.getResponse().getStatus()).as(rawContent(saved)).isEqualTo(200);

		MvcResult reloaded = send(ctx.host(), one.token(), get("/api/v1/teachers/me"), null);
		assertThat(data(reloaded).path("qualifications").asString()).isEqualTo("BSc (Hons) Chemistry");
		assertThat(data(reloaded).path("description").asString()).isEqualTo("Ten years teaching A/L chemistry.");
		assertThat(data(reloaded).path("name").asString()).isEqualTo("Profile one@example.test");
		assertThat(data(reloaded).path("firstName").asString()).isNotEqualTo("Hacked");
		assertThat(data(reloaded).path("contactNumber").asString()).isNotEqualTo("0000");

		// The other teacher's record is untouched, and a Teacher cannot use the admin endpoints.
		MvcResult otherAsAdmin = send(ctx.host(), ctx.adminToken(), get("/api/v1/teachers/" + two.profileId()), null);
		assertThat(data(otherAsAdmin).path("qualifications").isNull()).isTrue();
		assertThat(status(ctx, one.token(), get("/api/v1/teachers/" + two.profileId()))).isEqualTo(403);
		MvcResult adminPatch = send(ctx.host(), one.token(), patch("/api/v1/teachers/" + two.profileId()),
				Map.of("firstName", "X", "lastName", "Y", "contactNumber", "1"));
		assertThat(adminPatch.getResponse().getStatus()).isEqualTo(403);

		// Validation: over-length biography is a 400.
		MvcResult tooLong = send(ctx.host(), one.token(), patch("/api/v1/teachers/me"),
				Map.of("description", "x".repeat(5001)));
		assertThat(tooLong.getResponse().getStatus()).isEqualTo(400);

		// Non-teachers cannot use /me; a teacher user with no profile record gets 404.
		assertThat(status(ctx, ctx.adminToken(), get("/api/v1/teachers/me"))).isEqualTo(403);
		assertThat(status(ctx, ctx.leadToken(), get("/api/v1/teachers/me"))).isEqualTo(404);
	}

}
