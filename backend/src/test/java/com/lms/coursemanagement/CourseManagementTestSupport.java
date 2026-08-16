package com.lms.coursemanagement;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.lms.common.api.ApiResponse;
import com.lms.common.api.PageResponse;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseCreateRequest;
import com.lms.coursemanagement.course.web.dto.CourseLessonRequest;
import com.lms.coursemanagement.course.web.dto.CourseLessonResponse;
import com.lms.coursemanagement.course.web.dto.CourseModuleRequest;
import com.lms.coursemanagement.course.web.dto.CourseModuleResponse;
import com.lms.coursemanagement.course.web.dto.CoursePriceChangeRequest;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.coursemanagement.course.web.dto.CourseTeacherReassignRequest;
import com.lms.coursemanagement.course.web.dto.CourseUpdateRequest;
import com.lms.coursemanagement.course.web.dto.PublicCourseResponse;
import com.lms.identityaccessservice.AuthIntegrationTestSupport;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.web.dto.LoginResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JavaType;

/**
 * Shared Testcontainers/MockMvc helpers for Course Management (MVP-008)'s
 * integration tests, mirroring {@code AuthIntegrationTestSupport}/{@code
 * StaffManagementIntegrationTest}'s established technique exactly (MockMvc,
 * not a real socket round-trip, so {@code Host} header spoofing - which
 * {@code TenantResolutionFilter} depends on - works). Not itself a test class
 * (no {@code @Test} methods, name doesn't match Surefire's inclusion
 * patterns).
 */
public abstract class CourseManagementTestSupport extends AuthIntegrationTestSupport {

	protected static String uniqueSlug(String prefix) {
		return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
	}

	protected String loginAndGetToken(String host, String email) {
		HttpResult<LoginResponse> response = login(host, email, RAW_PASSWORD);
		if (response.getStatusCode() != HttpStatus.OK) {
			throw new IllegalStateException("Login failed for " + email + ": " + response.getStatusCode());
		}
		return response.getBody().data().accessToken();
	}

	protected CourseCreateRequest newCourseRequest(String slug, UUID teacherId) {
		return newCourseRequest(slug, teacherId, null);
	}

	protected CourseCreateRequest newCourseRequest(String slug, UUID teacherId, CourseStatus status) {
		return newCourseRequest(slug, teacherId, status, "Mathematics");
	}

	protected CourseCreateRequest newCourseRequest(String slug, UUID teacherId, CourseStatus status,
			String category) {
		return new CourseCreateRequest("Test Course " + slug, slug, category, null, null, null, null, null,
				new BigDecimal("99.99"), null, null, status, teacherId);
	}

	protected CourseUpdateRequest editRequestFor(CourseResponse course) {
		return new CourseUpdateRequest(course.name() + " Updated", course.slug(), course.category(), course.subject(),
				course.stream(), course.grade(), course.academicYear(), course.description(), course.enrollmentRule(),
				course.accessDurationDays());
	}

	// ------------------------------------------------------------------
	// Course-level endpoints.
	// ------------------------------------------------------------------

	protected HttpResult<CourseResponse> createCourse(String host, String token, CourseCreateRequest request) {
		MockHttpServletRequestBuilder builder = post("/api/v1/courses").contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(request));
		return parseSingle(perform(authenticated(builder, host, token)), CourseResponse.class);
	}

	protected CourseResponse createCourseOrFail(String host, String token, CourseCreateRequest request) {
		HttpResult<CourseResponse> result = createCourse(host, token, request);
		if (result.getStatusCode() != HttpStatus.CREATED) {
			throw new IllegalStateException(
					"Course creation failed: " + result.getStatusCode() + " " + result.getBody());
		}
		return result.getBody().data();
	}

	protected HttpResult<PageResponse<CourseResponse>> listCourses(String host, String token) {
		MockHttpServletRequestBuilder builder = get("/api/v1/courses");
		return parsePage(perform(authenticated(builder, host, token)), CourseResponse.class);
	}

	protected HttpResult<PageResponse<CourseResponse>> listCourses(String host, String token, String queryString) {
		MockHttpServletRequestBuilder builder = get("/api/v1/courses?" + queryString);
		return parsePage(perform(authenticated(builder, host, token)), CourseResponse.class);
	}

	protected HttpResult<CourseResponse> getCourse(String host, String token, UUID id) {
		MockHttpServletRequestBuilder builder = get("/api/v1/courses/{id}", id);
		return parseSingle(perform(authenticated(builder, host, token)), CourseResponse.class);
	}

	protected HttpResult<CourseResponse> updateCourse(String host, String token, UUID id,
			CourseUpdateRequest request) {
		MockHttpServletRequestBuilder builder = patch("/api/v1/courses/{id}", id)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(request));
		return parseSingle(perform(authenticated(builder, host, token)), CourseResponse.class);
	}

	protected HttpResult<CourseResponse> reassignTeacher(String host, String token, UUID id, UUID newTeacherId) {
		MockHttpServletRequestBuilder builder = post("/api/v1/courses/{id}/teacher", id)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(new CourseTeacherReassignRequest(newTeacherId)));
		return parseSingle(perform(authenticated(builder, host, token)), CourseResponse.class);
	}

	protected HttpResult<CourseResponse> changePrice(String host, String token, UUID id, BigDecimal price) {
		MockHttpServletRequestBuilder builder = patch("/api/v1/courses/{id}/price", id)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(new CoursePriceChangeRequest(price)));
		return parseSingle(perform(authenticated(builder, host, token)), CourseResponse.class);
	}

	protected HttpResult<CourseResponse> publish(String host, String token, UUID id) {
		MockHttpServletRequestBuilder builder = post("/api/v1/courses/{id}/publish", id);
		return parseSingle(perform(authenticated(builder, host, token)), CourseResponse.class);
	}

	protected HttpResult<CourseResponse> unpublish(String host, String token, UUID id) {
		MockHttpServletRequestBuilder builder = post("/api/v1/courses/{id}/unpublish", id);
		return parseSingle(perform(authenticated(builder, host, token)), CourseResponse.class);
	}

	protected HttpResult<Void> deleteCourse(String host, String token, UUID id) {
		MockHttpServletRequestBuilder builder = delete("/api/v1/courses/{id}", id);
		return parseSingle(perform(authenticated(builder, host, token)), Void.class);
	}

	// ------------------------------------------------------------------
	// Module endpoints.
	// ------------------------------------------------------------------

	protected HttpResult<CourseModuleResponse> createModule(String host, String token, UUID courseId, String title,
			int sequence) {
		MockHttpServletRequestBuilder builder = post("/api/v1/courses/{courseId}/modules", courseId)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(new CourseModuleRequest(title, sequence)));
		return parseSingle(perform(authenticated(builder, host, token)), CourseModuleResponse.class);
	}

	protected CourseModuleResponse createModuleOrFail(String host, String token, UUID courseId, String title,
			int sequence) {
		HttpResult<CourseModuleResponse> result = createModule(host, token, courseId, title, sequence);
		if (result.getStatusCode() != HttpStatus.CREATED) {
			throw new IllegalStateException("Module creation failed: " + result.getStatusCode());
		}
		return result.getBody().data();
	}

	protected HttpResult<CourseModuleResponse> updateModule(String host, String token, UUID courseId, UUID moduleId,
			String title, int sequence) {
		MockHttpServletRequestBuilder builder = patch("/api/v1/courses/{courseId}/modules/{moduleId}", courseId,
				moduleId).contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(new CourseModuleRequest(title, sequence)));
		return parseSingle(perform(authenticated(builder, host, token)), CourseModuleResponse.class);
	}

	protected HttpResult<Void> deleteModule(String host, String token, UUID courseId, UUID moduleId) {
		MockHttpServletRequestBuilder builder = delete("/api/v1/courses/{courseId}/modules/{moduleId}", courseId,
				moduleId);
		return parseSingle(perform(authenticated(builder, host, token)), Void.class);
	}

	protected HttpResult<List<CourseModuleResponse>> listModules(String host, String token, UUID courseId) {
		MockHttpServletRequestBuilder builder = get("/api/v1/courses/{courseId}/modules", courseId);
		return parseList(perform(authenticated(builder, host, token)), CourseModuleResponse.class);
	}

	// ------------------------------------------------------------------
	// Lesson endpoints.
	// ------------------------------------------------------------------

	protected HttpResult<CourseLessonResponse> createLesson(String host, String token, UUID courseId, UUID moduleId,
			String title, int sequence) {
		MockHttpServletRequestBuilder builder = post("/api/v1/courses/{courseId}/modules/{moduleId}/lessons",
				courseId, moduleId).contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(new CourseLessonRequest(title, sequence)));
		return parseSingle(perform(authenticated(builder, host, token)), CourseLessonResponse.class);
	}

	protected CourseLessonResponse createLessonOrFail(String host, String token, UUID courseId, UUID moduleId,
			String title, int sequence) {
		HttpResult<CourseLessonResponse> result = createLesson(host, token, courseId, moduleId, title, sequence);
		if (result.getStatusCode() != HttpStatus.CREATED) {
			throw new IllegalStateException("Lesson creation failed: " + result.getStatusCode());
		}
		return result.getBody().data();
	}

	protected HttpResult<CourseLessonResponse> updateLesson(String host, String token, UUID courseId, UUID moduleId,
			UUID lessonId, String title, int sequence) {
		MockHttpServletRequestBuilder builder = patch(
				"/api/v1/courses/{courseId}/modules/{moduleId}/lessons/{lessonId}", courseId, moduleId, lessonId)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(new CourseLessonRequest(title, sequence)));
		return parseSingle(perform(authenticated(builder, host, token)), CourseLessonResponse.class);
	}

	protected HttpResult<Void> deleteLesson(String host, String token, UUID courseId, UUID moduleId, UUID lessonId) {
		MockHttpServletRequestBuilder builder = delete(
				"/api/v1/courses/{courseId}/modules/{moduleId}/lessons/{lessonId}", courseId, moduleId, lessonId);
		return parseSingle(perform(authenticated(builder, host, token)), Void.class);
	}

	// ------------------------------------------------------------------
	// Public storefront endpoints (no auth).
	// ------------------------------------------------------------------

	protected HttpResult<PageResponse<PublicCourseResponse>> listPublicCourses(String host) {
		MockHttpServletRequestBuilder builder = get("/api/v1/public/courses");
		if (host != null) {
			builder.header(HttpHeaders.HOST, host);
		}
		return parsePage(perform(builder), PublicCourseResponse.class);
	}

	protected HttpResult<PageResponse<PublicCourseResponse>> listPublicCourses(String host, String queryString) {
		MockHttpServletRequestBuilder builder = get("/api/v1/public/courses?" + queryString);
		if (host != null) {
			builder.header(HttpHeaders.HOST, host);
		}
		return parsePage(perform(builder), PublicCourseResponse.class);
	}

	protected HttpResult<PublicCourseResponse> getPublicCourse(String host, String slug) {
		MockHttpServletRequestBuilder builder = get("/api/v1/public/courses/{slug}", slug);
		if (host != null) {
			builder.header(HttpHeaders.HOST, host);
		}
		return parseSingle(perform(builder), PublicCourseResponse.class);
	}

	// ------------------------------------------------------------------
	// HTTP request/response plumbing.
	// ------------------------------------------------------------------

	protected MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder builder, String host,
			String token) {
		if (host != null) {
			builder.header(HttpHeaders.HOST, host);
		}
		if (token != null) {
			builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
		}
		return builder;
	}

	protected MvcResult perform(MockHttpServletRequestBuilder request) {
		try {
			return mockMvc.perform(request).andReturn();
		}
		catch (Exception e) {
			throw new IllegalStateException("MockMvc request failed", e);
		}
	}

	protected <T> HttpResult<T> parseSingle(MvcResult raw, Class<T> type) {
		MockHttpServletResponse response = raw.getResponse();
		HttpStatus status = HttpStatus.valueOf(response.getStatus());
		String json = rawContent(raw);
		ApiResponse<T> body = null;
		if (json != null && !json.isBlank()) {
			JavaType javaType = objectMapper.getTypeFactory().constructParametricType(ApiResponse.class, type);
			body = objectMapper.readValue(json, javaType);
		}
		return new HttpResult<>(status, body, new HttpHeaders());
	}

	protected <T> HttpResult<List<T>> parseList(MvcResult raw, Class<T> elementType) {
		MockHttpServletResponse response = raw.getResponse();
		HttpStatus status = HttpStatus.valueOf(response.getStatus());
		String json = rawContent(raw);
		ApiResponse<List<T>> body = null;
		if (json != null && !json.isBlank()) {
			JavaType listType = objectMapper.getTypeFactory().constructCollectionType(List.class, elementType);
			JavaType type = objectMapper.getTypeFactory().constructParametricType(ApiResponse.class, listType);
			body = objectMapper.readValue(json, type);
		}
		return new HttpResult<>(status, body, new HttpHeaders());
	}

	protected <T> HttpResult<PageResponse<T>> parsePage(MvcResult raw, Class<T> elementType) {
		MockHttpServletResponse response = raw.getResponse();
		HttpStatus status = HttpStatus.valueOf(response.getStatus());
		String json = rawContent(raw);
		ApiResponse<PageResponse<T>> body = null;
		if (json != null && !json.isBlank()) {
			JavaType pageType = objectMapper.getTypeFactory().constructParametricType(PageResponse.class, elementType);
			JavaType type = objectMapper.getTypeFactory().constructParametricType(ApiResponse.class, pageType);
			body = objectMapper.readValue(json, type);
		}
		return new HttpResult<>(status, body, new HttpHeaders());
	}

	protected String rawContent(MvcResult raw) {
		try {
			return raw.getResponse().getContentAsString();
		}
		catch (Exception e) {
			throw new IllegalStateException("Failed to read MockMvc response content", e);
		}
	}

}
