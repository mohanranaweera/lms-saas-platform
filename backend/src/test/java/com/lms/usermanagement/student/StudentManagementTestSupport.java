package com.lms.usermanagement.student;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.lms.coursemanagement.CourseManagementTestSupport;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.integrationmanagement.InMemoryObjectStorageApiTestConfig;
import com.lms.tenantmanagement.domain.Tenant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;

/**
 * Shared fixtures for the STU-MGMT-02 integration tests: a tenant with an admin, a
 * teacher and one PUBLIC course; JSON / multipart plumbing; and student creation through
 * {@code POST /api/v1/students/v2} (password {@link #RAW_PASSWORD} so a created student
 * can sign in).
 */
@Import(InMemoryObjectStorageApiTestConfig.class)
public abstract class StudentManagementTestSupport extends CourseManagementTestSupport {

	protected record Fx(Tenant tenant, String host, String adminToken, TenantUser admin, TenantUser teacher,
			UUID courseId) {
	}

	protected Fx fixture(String prefix) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain(prefix));
		TenantUser admin = seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");
		UUID courseId = createCourseOrFail(host, token,
				newCourseRequest(uniqueSlug("stu"), teacher.getId(), CourseStatus.PUBLIC)).id();
		return new Fx(tenant, host, token, admin, teacher, courseId);
	}

	protected static String email(String prefix) {
		return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
	}

	protected static String uniqueMobile() {
		// Sri Lankan mobile with a leading zero (the default phone region normalises it).
		long n = Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 10_000_000L);
		return String.format("077%07d", n);
	}

	protected Map<String, Object> form(Fx fx, String firstName, String lastName) {
		Map<String, Object> form = new LinkedHashMap<>();
		form.put("email", email(firstName.toLowerCase()));
		form.put("password", RAW_PASSWORD);
		form.put("firstName", firstName);
		form.put("lastName", lastName);
		form.put("mobile", uniqueMobile());
		form.put("courseIds", List.of(fx.courseId().toString()));
		return form;
	}

	protected MvcResult send(String host, String token, MockHttpServletRequestBuilder builder, Object body) {
		builder.contentType(MediaType.APPLICATION_JSON);
		if (body != null) {
			builder.content(objectMapper.writeValueAsString(body));
		}
		return perform(authenticated(builder, host, token));
	}

	protected MvcResult getAs(String host, String token, String path, Object... vars) {
		return perform(authenticated(get(path, vars), host, token));
	}

	protected MvcResult upload(String host, String token, String path, MockMultipartFile file, Object... vars) {
		MockMultipartHttpServletRequestBuilder builder = MockMvcRequestBuilders.multipart(path, vars).file(file);
		builder.header(HttpHeaders.HOST, host);
		if (token != null) {
			builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
		}
		try {
			return mockMvc.perform(builder).andReturn();
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	protected JsonNode data(MvcResult result) {
		return objectMapper.readTree(rawContent(result)).path("data");
	}

	protected JsonNode error(MvcResult result) {
		return objectMapper.readTree(rawContent(result)).path("error");
	}

	protected int status(MvcResult result) {
		return result.getResponse().getStatus();
	}

	/** Creates a student through the v2 form; returns the created detail. */
	protected JsonNode createStudent(Fx fx, Map<String, Object> form) {
		MvcResult result = send(fx.host(), fx.adminToken(), post("/api/v1/students/v2"), form);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(201);
		return data(result).path("student");
	}

	protected UUID createStudentId(Fx fx, String firstName, String lastName) {
		return UUID.fromString(createStudent(fx, form(fx, firstName, lastName)).path("id").asString());
	}

	protected UUID userIdOf(UUID studentProfileId) {
		return jdbcTemplate.queryForObject("SELECT user_id FROM student_profile WHERE id = ?", UUID.class,
				studentProfileId);
	}

	protected String emailOf(UUID studentProfileId) {
		return jdbcTemplate.queryForObject(
				"SELECT u.email FROM tenant_user u JOIN student_profile p ON p.user_id = u.id WHERE p.id = ?",
				String.class, studentProfileId);
	}

	/** Signs the student in (clears the forced password change first). */
	protected String studentToken(Fx fx, UUID studentProfileId) {
		return loginAndGetToken(fx.host(), emailOf(studentProfileId));
	}

	protected String staffToken(Fx fx, Role role) {
		String staffEmail = email(role.name().toLowerCase());
		seedTenantUser(fx.tenant().getId(), staffEmail, RAW_PASSWORD, role);
		return loginAndGetToken(fx.host(), staffEmail);
	}

	protected long count(String sql, Object... args) {
		Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
		return value == null ? 0 : value;
	}

}
