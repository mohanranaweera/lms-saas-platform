package com.lms.coursemanagement.course.web;

import static org.assertj.core.api.Assertions.assertThat;
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
import tools.jackson.databind.JsonNode;

/**
 * R2-D7 (Klass gaps round 2): editing a course's teacher list validates only
 * newly added teachers (and a new primary), so an already-assigned co-teacher
 * who has since been suspended does not block an unrelated edit. Adding a
 * suspended teacher is still refused; primary changes stay Tenant-Admin-only
 * and a Teacher still cannot change the list.
 */
class CourseTeacherListEditIntegrationTest extends CourseManagementTestSupport {

	private record Ctx(Tenant tenant, String host, String adminToken, String coordinatorToken, String teacherToken,
			TenantUser teacher, TenantUser coTeacher, TenantUser newTeacher, TenantUser suspendedNewcomer) {

	}

	private Ctx seed(String prefix) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain(prefix));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		seedTenantUser(tenant.getId(), "coordinator@example.test", RAW_PASSWORD, Role.COURSE_COORDINATOR);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser coTeacher = seedTenantUser(tenant.getId(), "co@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser newTeacher = seedTenantUser(tenant.getId(), "new@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser suspended = seedTenantUser(tenant.getId(), "susp@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		return new Ctx(tenant, host, loginAndGetToken(host, "admin@example.test"),
				loginAndGetToken(host, "coordinator@example.test"), loginAndGetToken(host, "teacher@example.test"),
				teacher, coTeacher, newTeacher, suspended);
	}

	private MvcResult send(Ctx ctx, String token, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
			Map<String, Object> body) {
		builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
		return perform(authenticated(builder, ctx.host(), token));
	}

	private static Map<String, Object> body(String name, List<UUID> teacherIds) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("name", name);
		body.put("teacherIds", teacherIds);
		body.put("pricingModel", "MONTHLY");
		body.put("price", "4000.00");
		return body;
	}

	private void suspend(UUID userId) {
		jdbcTemplate.update("UPDATE tenant_user SET status = 'suspended' WHERE id = ?", userId);
	}

	private List<String> teacherIds(MvcResult result) {
		JsonNode data = objectMapper.readTree(rawContent(result)).path("data");
		List<String> ids = new ArrayList<>();
		data.path("teachers").forEach(node -> ids.add(node.path("id").asString()));
		return ids;
	}

	@Test
	void aSuspendedExistingCoTeacherDoesNotBlockATeacherListEditButAddingASuspendedTeacherIsRefused() {
		Ctx ctx = seed("r2d7-suspended");
		MvcResult created = send(ctx, ctx.adminToken(), post("/api/v1/courses"),
				body("Biology", List.of(ctx.teacher().getId(), ctx.coTeacher().getId())));
		assertThat(created.getResponse().getStatus()).as(rawContent(created)).isEqualTo(201);
		String courseId = objectMapper.readTree(rawContent(created)).path("data").path("id").asString();
		suspend(ctx.coTeacher().getId());
		suspend(ctx.suspendedNewcomer().getId());

		// Coordinator adds an active teacher; the suspended co-teacher stays assigned.
		MvcResult added = send(ctx, ctx.coordinatorToken(), patch("/api/v1/courses/" + courseId),
				body("Biology", List.of(ctx.teacher().getId(), ctx.coTeacher().getId(), ctx.newTeacher().getId())));
		assertThat(added.getResponse().getStatus()).as(rawContent(added)).isEqualTo(200);
		assertThat(teacherIds(added)).contains(ctx.coTeacher().getId().toString(),
				ctx.newTeacher().getId().toString());

		// Adding a suspended teacher is still refused.
		MvcResult refused = send(ctx, ctx.adminToken(), patch("/api/v1/courses/" + courseId),
				body("Biology", List.of(ctx.teacher().getId(), ctx.coTeacher().getId(), ctx.newTeacher().getId(),
						ctx.suspendedNewcomer().getId())));
		assertThat(refused.getResponse().getStatus()).isEqualTo(400);

		// Promoting the suspended co-teacher to primary is a reassignment: refused too.
		MvcResult promote = send(ctx, ctx.adminToken(), patch("/api/v1/courses/" + courseId),
				body("Biology", List.of(ctx.coTeacher().getId(), ctx.teacher().getId())));
		assertThat(promote.getResponse().getStatus()).isEqualTo(400);

		// Coordinator primary change 403; Teacher cannot edit the list at all.
		MvcResult coordinatorPrimary = send(ctx, ctx.coordinatorToken(), patch("/api/v1/courses/" + courseId),
				body("Biology", List.of(ctx.newTeacher().getId(), ctx.teacher().getId())));
		assertThat(coordinatorPrimary.getResponse().getStatus()).isEqualTo(403);
		MvcResult teacherEdit = send(ctx, ctx.teacherToken(), patch("/api/v1/courses/" + courseId),
				body("Biology", List.of(ctx.teacher().getId())));
		assertThat(teacherEdit.getResponse().getStatus()).isEqualTo(403);
	}

}
