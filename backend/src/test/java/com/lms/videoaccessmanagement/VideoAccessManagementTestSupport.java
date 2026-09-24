package com.lms.videoaccessmanagement;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.lms.contentmanagement.ContentManagementTestSupport;
import com.lms.contentmanagement.material.domain.MaterialType;
import com.lms.contentmanagement.material.web.dto.MaterialResponse;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseLessonResponse;
import com.lms.coursemanagement.course.web.dto.CourseModuleResponse;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.tenantmanagement.domain.Tenant;
import com.lms.videoaccessmanagement.web.dto.PlaybackSessionResponse;
import com.lms.videoaccessmanagement.web.dto.VideoAssetResponse;
import com.lms.videoaccessmanagement.web.dto.VideoPlaybackPolicyRequest;
import com.lms.videoaccessmanagement.web.dto.VideoPlaybackPolicyResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/**
 * Shared Testcontainers/MockMvc helpers for {@code video-access-management}'s
 * integration tests, mirroring {@code LiveClassManagementTestSupport}'s
 * established technique exactly. Extends {@code ContentManagementTestSupport}
 * so tests get real course/module/lesson/material seeding AND real order/
 * payment/webhook-driven enrollment seeding for free - a real, webhook
 * -confirmed payment is the only legitimate way a Student's {@code ACTIVE}
 * entitlement can exist for {@code VideoAccessGuard} to see.
 */
public abstract class VideoAccessManagementTestSupport extends ContentManagementTestSupport {

	// ------------------------------------------------------------------
	// Fixture byte helpers - matching VideoContentSniffer's accepted signature.
	// ------------------------------------------------------------------

	protected static byte[] validMp4Bytes() {
		byte[] header = new byte[12];
		header[3] = 0x18; // arbitrary box-size low byte, never actually parsed as a real size by the sniffer
		System.arraycopy("ftyp".getBytes(StandardCharsets.US_ASCII), 0, header, 4, 4);
		System.arraycopy("isom".getBytes(StandardCharsets.US_ASCII), 0, header, 8, 4);
		byte[] padding = "Fixture MP4 payload padding for Wave 5 tests.".getBytes(StandardCharsets.US_ASCII);
		byte[] bytes = new byte[header.length + padding.length];
		System.arraycopy(header, 0, bytes, 0, header.length);
		System.arraycopy(padding, 0, bytes, header.length, padding.length);
		return bytes;
	}

	protected static MockMultipartFile mp4File(String filename) {
		return new MockMultipartFile("file", filename, "video/mp4", validMp4Bytes());
	}

	// ------------------------------------------------------------------
	// Fixture seeding.
	// ------------------------------------------------------------------

	protected record VideoFixture(Tenant tenant, String host, String adminToken, String teacherToken,
			String studentToken, TenantUser admin, TenantUser teacher, TenantUser student, CourseResponse course,
			UUID moduleId, UUID lessonId, UUID videoAssetId, UUID materialId) {
	}

	/**
	 * Seeds a tenant, Tenant Admin, Teacher, Student, a published course with
	 * one module/lesson, an enrolled Student (real order -> payment ->
	 * webhook-confirm), and one uploaded video already attached as a {@code
	 * VIDEO} material on that lesson - everything a playback-session test
	 * needs.
	 */
	protected VideoFixture seedVideoFixture(String prefix) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain(prefix));
		TenantUser admin = seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser student = seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String teacherToken = loginAndGetToken(host, "teacher@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug(prefix), teacher.getId(), CourseStatus.PUBLIC));
		CourseModuleResponse module = createModuleOrFail(host, adminToken, course.id(), "Module 1", 1);
		CourseLessonResponse lesson = createLessonOrFail(host, adminToken, course.id(), module.id(), "Lesson 1", 1);
		enrollStudentOrFail(host, studentToken, course.id());

		VideoAssetResponse asset = uploadVideoOrFail(host, teacherToken, mp4File(prefix + ".mp4"));
		HttpResult<MaterialResponse> materialResult = createMaterialRaw(host, teacherToken, course.id(), module.id(),
				lesson.id(), "Lecture video", MaterialType.VIDEO, null, null, null, asset.id(), null, null, null,
				null);
		if (materialResult.getStatusCode() != HttpStatus.CREATED) {
			throw new IllegalStateException(
					"Video material attach failed: " + materialResult.getStatusCode() + " " + materialResult.getBody());
		}
		MaterialResponse material = materialResult.getBody().data();

		return new VideoFixture(tenant, host, adminToken, teacherToken, studentToken, admin, teacher, student, course,
				module.id(), lesson.id(), asset.id(), material.id());
	}

	// ------------------------------------------------------------------
	// video-access-management endpoints.
	// ------------------------------------------------------------------

	protected HttpResult<VideoAssetResponse> uploadVideo(String host, String token, MockMultipartFile file) {
		MockMultipartHttpServletRequestBuilder builder = multipart("/api/v1/videos").file(file);
		return parseSingle(performMultipart(authenticatedMultipart(builder, host, token)), VideoAssetResponse.class);
	}

	protected VideoAssetResponse uploadVideoOrFail(String host, String token, MockMultipartFile file) {
		HttpResult<VideoAssetResponse> result = uploadVideo(host, token, file);
		if (result.getStatusCode() != HttpStatus.CREATED) {
			throw new IllegalStateException("Video upload failed: " + result.getStatusCode() + " " + result.getBody());
		}
		return result.getBody().data();
	}

	protected HttpResult<VideoPlaybackPolicyResponse> upsertPolicy(String host, String token, UUID videoAssetId,
			VideoPlaybackPolicyRequest request) {
		MockHttpServletRequestBuilder builder = put("/api/v1/videos/{id}/policy", videoAssetId)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(request));
		return parseSingle(perform(authenticated(builder, host, token)), VideoPlaybackPolicyResponse.class);
	}

	protected VideoPlaybackPolicyResponse upsertPolicyOrFail(String host, String token, UUID videoAssetId,
			VideoPlaybackPolicyRequest request) {
		HttpResult<VideoPlaybackPolicyResponse> result = upsertPolicy(host, token, videoAssetId, request);
		if (result.getStatusCode() != HttpStatus.OK) {
			throw new IllegalStateException(
					"Policy upsert failed: " + result.getStatusCode() + " " + result.getBody());
		}
		return result.getBody().data();
	}

	protected HttpResult<PlaybackSessionResponse> issuePlaybackSession(String host, String token,
			UUID videoAssetId) {
		MockHttpServletRequestBuilder builder = post("/api/v1/videos/{id}/playback-sessions", videoAssetId);
		return parseSingle(perform(authenticated(builder, host, token)), PlaybackSessionResponse.class);
	}

	protected PlaybackSessionResponse issuePlaybackSessionOrFail(String host, String token, UUID videoAssetId) {
		HttpResult<PlaybackSessionResponse> result = issuePlaybackSession(host, token, videoAssetId);
		if (result.getStatusCode() != HttpStatus.CREATED) {
			throw new IllegalStateException(
					"Playback session issuance failed: " + result.getStatusCode() + " " + result.getBody());
		}
		return result.getBody().data();
	}

	protected HttpResult<Void> recordProgress(String host, String token, UUID watchSessionId, String playbackToken,
			int positionSeconds, int watchedDeltaSeconds) {
		String body = "{\"playbackToken\":" + jsonString(playbackToken) + ",\"positionSeconds\":" + positionSeconds
				+ ",\"watchedDeltaSeconds\":" + watchedDeltaSeconds + "}";
		MockHttpServletRequestBuilder builder = post("/api/v1/videos/playback-sessions/{id}/progress", watchSessionId)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body);
		return parseSingle(perform(authenticated(builder, host, token)), Void.class);
	}

	protected HttpResult<Void> endPlaybackSession(String host, String token, UUID watchSessionId) {
		MockHttpServletRequestBuilder builder = post("/api/v1/videos/playback-sessions/{id}/end", watchSessionId);
		return parseSingle(perform(authenticated(builder, host, token)), Void.class);
	}

	private static String jsonString(String raw) {
		if (raw == null) {
			return "null";
		}
		return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

}
