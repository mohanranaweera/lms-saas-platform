package com.lms.paymentmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.lms.coursemanagement.course.domain.CoursePricingModel;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseCreateRequest;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.tenantmanagement.domain.Tenant;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * R2-D6 (Klass gaps round 2): {@code GET /api/v1/checkout/courses} items carry
 * the course's tags ({@code [{id, name}]}, ordered by name) so the payment
 * composer can filter by tag - read in one tenant-scoped query; another
 * tenant's tags never appear.
 */
class CheckoutCourseTagsIntegrationTest extends SlipTestSupport {

	private record Fx(Tenant tenant, String host, String studentToken, UUID tagged, UUID untagged, UUID tagA,
			UUID tagB) {

	}

	private Fx seed(String prefix) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain(prefix));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");

		UUID tagged = monthlyCourseWithCoveredLesson(host, adminToken, teacher.getId(), prefix + "-tagged");
		UUID untagged = monthlyCourseWithCoveredLesson(host, adminToken, teacher.getId(), prefix + "-plain");
		UUID tagA = tag(tenant.getId(), "Theory");
		UUID tagB = tag(tenant.getId(), "A/L 2027");
		assign(tenant.getId(), tagged, tagA);
		assign(tenant.getId(), tagged, tagB);
		for (UUID course : List.of(tagged, untagged)) {
			MvcResult enrolled = perform(
					authenticated(post("/api/v1/enrollments/my/courses/{courseId}", course), host, studentToken));
			assertThat(enrolled.getResponse().getStatus()).as(rawContent(enrolled)).isEqualTo(200);
		}
		return new Fx(tenant, host, studentToken, tagged, untagged, tagA, tagB);
	}

	private UUID monthlyCourseWithCoveredLesson(String host, String adminToken, UUID teacherId, String prefix) {
		String slug = uniqueSlug(prefix);
		UUID course = createCourseOrFail(host, adminToken,
				new CourseCreateRequest("Course " + slug, slug, "Mathematics", null, null, null, null, null,
						new BigDecimal("3500.00"), null, null, CourseStatus.PUBLIC, teacherId, null,
						CoursePricingModel.MONTHLY, null, null, null, null, null, null, null))
			.id();
		UUID module = createModuleOrFail(host, adminToken, course, "Module", 1).id();
		UUID lesson = createLessonOrFail(host, adminToken, course, module, "October class", 1).id();
		jdbcTemplate.update("UPDATE course_lesson SET payment_type = 'COVERED_BY_CLASS_FEES', billing_year = 2026, "
				+ "billing_month = 10 WHERE id = ?", lesson);
		return course;
	}

	private UUID tag(UUID tenantId, String name) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO course_tag (id, tenant_id, name, normalized_name, created_at) "
				+ "VALUES (?, ?, ?, ?, ?)", id, tenantId, name, name.toLowerCase(), Timestamp.from(Instant.now()));
		return id;
	}

	private void assign(UUID tenantId, UUID courseId, UUID tagId) {
		jdbcTemplate.update("INSERT INTO course_tag_assignment (tenant_id, course_id, tag_id) VALUES (?, ?, ?)",
				tenantId, courseId, tagId);
	}

	private JsonNode courses(Fx fx) {
		MvcResult result = perform(authenticated(get("/api/v1/checkout/courses?scope=MONTHLY"), fx.host(),
				fx.studentToken()));
		assertThat(result.getResponse().getStatus()).as(rawContent(result)).isEqualTo(200);
		return objectMapper.readTree(rawContent(result)).path("data");
	}

	private static JsonNode course(JsonNode list, UUID courseId) {
		for (JsonNode item : list) {
			if (item.path("courseId").asString().equals(courseId.toString())) {
				return item;
			}
		}
		throw new AssertionError("course " + courseId + " not offered");
	}

	@Test
	@Tag("cross-tenant")
	void checkoutCoursesCarryTheirOwnTenantsTagsOnly() {
		Fx a = seed("r2d6-a");
		Fx b = seed("r2d6-b");

		JsonNode list = courses(a);
		assertThat(list).hasSize(2);
		JsonNode tagged = course(list, a.tagged());
		List<String> names = new ArrayList<>();
		List<String> ids = new ArrayList<>();
		tagged.path("tags").forEach(tag -> {
			names.add(tag.path("name").asString());
			ids.add(tag.path("id").asString());
		});
		assertThat(names).containsExactly("A/L 2027", "Theory");
		assertThat(ids).containsExactly(a.tagB().toString(), a.tagA().toString());
		assertThat(course(list, a.untagged()).path("tags").isArray()).isTrue();
		assertThat(course(list, a.untagged()).path("tags")).isEmpty();
		assertThat(rawContent(perform(authenticated(get("/api/v1/checkout/courses?scope=MONTHLY"), a.host(),
				a.studentToken())))).doesNotContain(b.tagA().toString()).doesNotContain(b.tagB().toString());
		assertThat(rawContent(perform(authenticated(get("/api/v1/checkout/courses?scope=MONTHLY"), a.host(),
				a.studentToken())))).doesNotContain("normalized");
	}

}
