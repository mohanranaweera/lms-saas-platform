package com.lms.coursemanagement.course.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.lms.coursemanagement.CourseManagementTestSupport;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.tenantmanagement.domain.Tenant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * KLASS-T8b security review fixes on the course read paths (Testcontainers/MockMvc):
 * <ul>
 * <li>SEC M1 - a non-participant student gets no invite links and no PRIVATE
 * linked course on {@code GET /api/v1/courses/{id}}; a participant (directly
 * or via the parent) and staff see everything.</li>
 * <li>SEC L4 - a student who may not see a non-PUBLIC course gets 404, not
 * 403.</li>
 * <li>SEC L5 - a teacher previewing their own course does not see a linked
 * source course's lessons unless they own the source; a tenant admin does.</li>
 * <li>QA contract - {@code PATCH /api/v1/courses/{id}} accepts a partial body
 * (no name), so a self link is the link rule's 400 {@code
 * COURSE_LINK_INVALID}.</li>
 * </ul>
 */
@Tag("cross-tenant")
class CourseStudentVisibilityIntegrationTest extends CourseManagementTestSupport {

	private record Fx(Tenant tenant, String host, String adminToken, String teacherAToken, String studentToken,
			String outsiderToken, UUID parent, UUID privateSource, UUID publicSource, UUID draft, UUID parentLesson,
			UUID sourceLesson) {

	}

	private Fx seed(String prefix) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain(prefix));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacherA = seedTenantUser(tenant.getId(), "teacher-a@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser teacherB = seedTenantUser(tenant.getId(), "teacher-b@example.test", RAW_PASSWORD, Role.TEACHER);
		seedActiveStudent(tenant.getId(), "student@example.test");
		seedActiveStudent(tenant.getId(), "outsider@example.test");
		String host = hostFor(tenant.getSubdomain());
		String admin = loginAndGetToken(host, "admin@example.test");

		UUID privateSource = create(host, admin, body("Private source", teacherB.getId(), "PRIVATE"));
		UUID publicSource = create(host, admin, body("Public source", teacherB.getId(), "PUBLIC"));
		UUID draft = create(host, admin, body("Draft course", teacherB.getId(), "DRAFT"));
		Map<String, Object> parentBody = body("Parent", teacherA.getId(), "PUBLIC");
		parentBody.put("whatsappInviteUrl", "https://chat.whatsapp.com/t8b-invite");
		parentBody.put("telegramInviteUrl", "https://t.me/t8b-invite");
		parentBody.put("linkedCourseIds", List.of(privateSource, publicSource));
		UUID parent = create(host, admin, parentBody);

		UUID parentModule = createModuleOrFail(host, admin, parent, "Parent module", 1).id();
		UUID parentLesson = createLessonOrFail(host, admin, parent, parentModule, "Parent lesson", 1).id();
		UUID sourceModule = createModuleOrFail(host, admin, privateSource, "Source module", 1).id();
		UUID sourceLesson = createLessonOrFail(host, admin, privateSource, sourceModule, "Source lesson", 1).id();

		String student = loginAndGetToken(host, "student@example.test");
		MvcResult enrolled = call(host, student, post("/api/v1/enrollments/my/courses/{c}", parent), null);
		assertThat(status(enrolled)).as(rawContent(enrolled)).isEqualTo(200);
		return new Fx(tenant, host, admin, loginAndGetToken(host, "teacher-a@example.test"), student,
				loginAndGetToken(host, "outsider@example.test"), parent, privateSource, publicSource, draft,
				parentLesson, sourceLesson);
	}

	private static Map<String, Object> body(String name, UUID teacherId, String status) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("name", name);
		body.put("teacherIds", List.of(teacherId));
		body.put("pricingModel", "FREE");
		body.put("status", status);
		return body;
	}

	private UUID create(String host, String token, Map<String, Object> body) {
		MvcResult result = call(host, token, post("/api/v1/courses"), body);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(201);
		return UUID.fromString(json(result).path("data").path("id").asString());
	}

	private MvcResult call(String host, String token, MockHttpServletRequestBuilder builder, Object body) {
		if (body != null) {
			builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
		}
		return perform(authenticated(builder, host, token));
	}

	private JsonNode json(MvcResult result) {
		return objectMapper.readTree(rawContent(result));
	}

	private static int status(MvcResult result) {
		return result.getResponse().getStatus();
	}

	private JsonNode course(Fx fx, String token, UUID courseId) {
		MvcResult result = call(fx.host(), token, get("/api/v1/courses/{id}", courseId), null);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(200);
		return json(result).path("data");
	}

	private static Set<String> linkedIds(JsonNode course) {
		Set<String> ids = new HashSet<>();
		course.path("linkedCourses").forEach(link -> ids.add(link.path("id").asString()));
		return ids;
	}

	@Test
	void nonParticipantStudentGetsNoInviteLinksAndNoPrivateLinkedCourse() {
		Fx fx = seed("t8b-m1");

		JsonNode outsider = course(fx, fx.outsiderToken(), fx.parent());
		assertThat(outsider.path("whatsappInviteUrl").isNull()).isTrue();
		assertThat(outsider.path("telegramInviteUrl").isNull()).isTrue();
		assertThat(linkedIds(outsider)).containsExactly(fx.publicSource().toString());
		assertThat(outsider.path("teachers")).isNotEmpty();

		JsonNode participant = course(fx, fx.studentToken(), fx.parent());
		assertThat(participant.path("whatsappInviteUrl").asString()).isEqualTo("https://chat.whatsapp.com/t8b-invite");
		assertThat(participant.path("telegramInviteUrl").asString()).isEqualTo("https://t.me/t8b-invite");
		assertThat(linkedIds(participant)).containsExactlyInAnyOrder(fx.publicSource().toString(),
				fx.privateSource().toString());

		JsonNode admin = course(fx, fx.adminToken(), fx.parent());
		assertThat(admin.path("whatsappInviteUrl").asString()).isEqualTo("https://chat.whatsapp.com/t8b-invite");
		assertThat(linkedIds(admin)).hasSize(2);
	}

	@Test
	void studentWhoCannotSeeANonPublicCourseGets404() {
		Fx fx = seed("t8b-l4");

		assertThat(status(call(fx.host(), fx.outsiderToken(), get("/api/v1/courses/{id}", fx.privateSource()), null)))
			.isEqualTo(404);
		assertThat(status(call(fx.host(), fx.outsiderToken(), get("/api/v1/courses/{id}", fx.draft()), null)))
			.isEqualTo(404);
		assertThat(status(call(fx.host(), fx.studentToken(), get("/api/v1/courses/{id}", fx.draft()), null)))
			.isEqualTo(404);
		assertThat(status(call(fx.host(), fx.outsiderToken(),
				get("/api/v1/courses/{id}/modules", fx.privateSource()), null)))
			.isEqualTo(404);
		// Participation via the parent link still reveals the linked PRIVATE source.
		assertThat(status(call(fx.host(), fx.studentToken(), get("/api/v1/courses/{id}", fx.privateSource()), null)))
			.isEqualTo(200);
		// A PUBLIC course is still readable by anyone in the tenant.
		assertThat(status(call(fx.host(), fx.outsiderToken(), get("/api/v1/courses/{id}", fx.publicSource()), null)))
			.isEqualTo(200);
	}

	@Test
	void teacherPreviewOmitsLinkedSourceLessonsOfAnotherTeachersCourse() {
		Fx fx = seed("t8b-l5");

		Set<String> teacherLessons = contentLessonIds(fx, fx.teacherAToken(), fx.parent());
		assertThat(teacherLessons).contains(fx.parentLesson().toString())
			.doesNotContain(fx.sourceLesson().toString());

		Set<String> adminLessons = contentLessonIds(fx, fx.adminToken(), fx.parent());
		assertThat(adminLessons).contains(fx.parentLesson().toString(), fx.sourceLesson().toString());
	}

	private Set<String> contentLessonIds(Fx fx, String token, UUID courseId) {
		MvcResult result = call(fx.host(), token, get("/api/v1/courses/{id}/content", courseId), null);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(200);
		Set<String> ids = new HashSet<>();
		json(result).path("data").path("periods").forEach(period -> period.path("modules")
			.forEach(module -> module.path("lessons").forEach(lesson -> ids.add(lesson.path("lessonId").asString()))));
		return ids;
	}

	@Test
	void patchAcceptsAPartialBodyAndSelfLinkIsCourseLinkInvalid() {
		Fx fx = seed("t8b-patch");

		MvcResult selfLink = call(fx.host(), fx.adminToken(), patch("/api/v1/courses/{id}", fx.parent()),
				Map.of("linkedCourseIds", List.of(fx.parent())));
		assertThat(status(selfLink)).as(rawContent(selfLink)).isEqualTo(400);
		assertThat(json(selfLink).path("error").path("code").asString()).isEqualTo("COURSE_LINK_INVALID");

		MvcResult partial = call(fx.host(), fx.adminToken(), patch("/api/v1/courses/{id}", fx.parent()),
				Map.of("description", "Updated description"));
		assertThat(status(partial)).as(rawContent(partial)).isEqualTo(200);
		JsonNode updated = json(partial).path("data");
		assertThat(updated.path("name").asString()).isEqualTo("Parent");
		assertThat(updated.path("description").asString()).isEqualTo("Updated description");
		assertThat(updated.path("whatsappInviteUrl").asString()).isEqualTo("https://chat.whatsapp.com/t8b-invite");
		assertThat(linkedIds(updated)).hasSize(2);

		MvcResult blankName = call(fx.host(), fx.adminToken(), patch("/api/v1/courses/{id}", fx.parent()),
				Map.of("name", "   "));
		assertThat(status(blankName)).as(rawContent(blankName)).isEqualTo(400);
	}

}
