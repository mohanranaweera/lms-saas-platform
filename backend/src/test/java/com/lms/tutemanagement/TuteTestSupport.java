package com.lms.tutemanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.paymentmanagement.PaymentManagementTestSupport;
import com.lms.tenantmanagement.domain.Tenant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/** Shared Testcontainers/MockMvc fixtures for the TUTE-01 integration tests (synthetic data only). */
public abstract class TuteTestSupport extends PaymentManagementTestSupport {

	protected record Fx(Tenant tenant, String host, String adminToken, TenantUser admin, TenantUser teacher,
			String teacherToken, UUID course, UUID otherCourse) {

		UUID tenantId() {
			return tenant.getId();
		}

	}

	protected record Student(TenantUser user, String token) {

		UUID id() {
			return user.getId();
		}

	}

	protected Fx seed(String prefix) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain(prefix));
		TenantUser admin = seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser teacher2 = seedTenantUser(tenant.getId(), "teacher2@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		UUID course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug(prefix + "-a"), teacher.getId(), CourseStatus.PUBLIC)).id();
		UUID other = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug(prefix + "-b"), teacher2.getId(), CourseStatus.PUBLIC)).id();
		return new Fx(tenant, host, adminToken, admin, teacher, loginAndGetToken(host, "teacher@example.test"), course,
				other);
	}

	/** A student user with a profile (synthetic name/address) who joined {@code courseIds} for free. */
	protected Student student(Fx fx, String email, String name, String addressLine, String city, UUID... courseIds) {
		TenantUser user = seedActiveStudent(fx.tenantId(), email);
		jdbcTemplate.update("INSERT INTO student_profile (id, tenant_id, user_id, name, created_at, updated_at) "
				+ "VALUES (?, ?, ?, ?, now(), now())", UUID.randomUUID(), fx.tenantId(), user.getId(), name);
		jdbcTemplate.update("UPDATE student_profile SET address_line = ?, city = ?, mobile = '0770000000' "
				+ "WHERE user_id = ?", addressLine, city, user.getId());
		Student student = new Student(user, loginAndGetToken(fx.host(), email));
		for (UUID courseId : courseIds) {
			MvcResult joined = call(fx.host(), student.token(), post("/api/v1/enrollments/my/courses/{c}", courseId));
			assertThat(status(joined)).as(rawContent(joined)).isEqualTo(200);
		}
		return student;
	}

	protected static Map<String, Object> tuteBody(String name, int quantity, String type, Object price, UUID lesson) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("name", name);
		body.put("quantity", quantity);
		body.put("paymentType", type);
		body.put("price", price);
		body.put("paidLessonId", lesson);
		body.put("description", "Synthetic handout");
		return body;
	}

	protected MvcResult createTute(String host, String token, UUID courseId, Map<String, Object> body) {
		return json(host, token, post("/api/v1/courses/{c}/tutes", courseId), body);
	}

	protected JsonNode createTuteOk(Fx fx, UUID courseId, Map<String, Object> body) {
		MvcResult result = createTute(fx.host(), fx.adminToken(), courseId, body);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(201);
		return data(result);
	}

	protected MvcResult updateTute(String host, String token, UUID courseId, UUID tuteId, Map<String, Object> body) {
		return json(host, token, put("/api/v1/courses/{c}/tutes/{t}", courseId, tuteId), body);
	}

	protected MvcResult assign(String host, String token, UUID courseId, UUID tuteId, List<UUID> students,
			String method) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("studentIds", students);
		body.put("method", method);
		return json(host, token, post("/api/v1/courses/{c}/tutes/{t}/fulfillments", courseId, tuteId), body);
	}

	protected MvcResult queue(String host, String token, String query) {
		return call(host, token, get("/api/v1/tute-dispatch/queue" + (query == null ? "" : "?" + query)));
	}

	protected MvcResult labels(String host, String token, Map<String, Object> body) {
		return json(host, token, post("/api/v1/tute-dispatch/labels"), body);
	}

	protected MvcResult confirm(String host, String token, List<UUID> ids) {
		return json(host, token, post("/api/v1/tute-dispatch/confirm"), Map.of("fulfillmentIds", ids));
	}

	protected UUID fulfillmentOf(UUID tuteId, UUID studentId) {
		return jdbcTemplate.queryForObject("SELECT id FROM tute_fulfillment WHERE tute_id = ? AND student_id = ? "
				+ "AND status <> 'CANCELLED'", UUID.class, tuteId, studentId);
	}

	/** Grants (or revokes) capabilities for a role through the Staff -> Permissions API. */
	protected void setGrants(Fx fx, String role, boolean granted, String... capabilities) {
		MvcResult matrix = call(fx.host(), fx.adminToken(), get("/api/v1/staff/permission-matrix"));
		assertThat(status(matrix)).as(rawContent(matrix)).isEqualTo(200);
		long version = data(matrix).path("version").asLong();
		List<Map<String, Object>> changes = java.util.Arrays.stream(capabilities)
			.map(c -> Map.<String, Object>of("roleCode", role, "capabilityKey", c, "granted", granted))
			.toList();
		MvcResult saved = json(fx.host(), fx.adminToken(), put("/api/v1/staff/permission-matrix"),
				Map.of("expectedVersion", version, "changes", changes));
		assertThat(status(saved)).as(rawContent(saved)).isEqualTo(200);
	}

	protected MvcResult call(String host, String token, MockHttpServletRequestBuilder builder) {
		return perform(authenticated(builder, host, token));
	}

	protected MvcResult json(String host, String token, MockHttpServletRequestBuilder builder, Object body) {
		return call(host, token,
				builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
	}

	protected JsonNode body(MvcResult result) {
		return objectMapper.readTree(rawContent(result));
	}

	protected JsonNode data(MvcResult result) {
		return body(result).path("data");
	}

	protected String errorCode(MvcResult result) {
		return body(result).path("error").path("code").asString();
	}

	protected static int status(MvcResult result) {
		return result.getResponse().getStatus();
	}

	protected long count(String sql, Object... args) {
		Long count = jdbcTemplate.queryForObject(sql, Long.class, args);
		return count == null ? 0 : count;
	}

}
