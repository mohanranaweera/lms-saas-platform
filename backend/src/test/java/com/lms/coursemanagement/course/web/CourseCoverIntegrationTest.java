package com.lms.coursemanagement.course.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import com.lms.coursemanagement.CourseManagementTestSupport;
import com.lms.coursemanagement.course.web.dto.CourseCreateRequest;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.integrationmanagement.InMemoryObjectStorageApiTestConfig;
import com.lms.integrationmanagement.InMemoryObjectStorageApiTestConfig.InMemoryObjectStorageApi;
import com.lms.tenantmanagement.domain.Tenant;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * KLASS-T4a cover image upload: server-side content sniffing (a PDF/GIF
 * renamed to .png is rejected with no storage write), signed-URL exposure,
 * the 5 MB cap, removal and cross-tenant 404.
 */
@Import(InMemoryObjectStorageApiTestConfig.class)
class CourseCoverIntegrationTest extends CourseManagementTestSupport {

	private static final byte[] PNG_BYTES = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13 };

	@Autowired
	private InMemoryObjectStorageApi storage;

	private record Ctx(Tenant tenant, String host, String token, CourseResponse course) {

	}

	private Ctx setUp(String prefix) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain(prefix));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");
		CourseResponse course = createCourseOrFail(host, token, new CourseCreateRequest("Cover course",
				uniqueSlug("cover"), "Science", null, null, null, null, null, new BigDecimal("10.00"), null, null,
				null, teacher.getId()));
		return new Ctx(tenant, host, token, course);
	}

	private MvcResult upload(String host, String token, UUID courseId, MockMultipartFile file) {
		MockMultipartHttpServletRequestBuilder builder = multipart("/api/v1/courses/{id}/cover", courseId).file(file);
		builder.header(HttpHeaders.HOST, host).header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
		try {
			return mockMvc.perform(builder).andReturn();
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private JsonNode json(MvcResult result) {
		return objectMapper.readTree(rawContent(result));
	}

	@Test
	void nonImageContentIsRejectedBeforeAnyStorageWriteEvenWithAnImageNameAndType() {
		Ctx ctx = setUp("cover-mime");
		Set<String> before = new HashSet<>(storage.keySet());

		MvcResult pdf = upload(ctx.host(), ctx.token(), ctx.course().id(),
				new MockMultipartFile("file", "cover.png", "image/png", "%PDF-1.7 not an image".getBytes()));
		MvcResult gif = upload(ctx.host(), ctx.token(), ctx.course().id(),
				new MockMultipartFile("file", "cover.png", "image/png", new byte[] { 'G', 'I', 'F', '8', '9', 'a' }));

		assertThat(pdf.getResponse().getStatus()).isEqualTo(415);
		assertThat(gif.getResponse().getStatus()).isEqualTo(415);
		assertThat(storage.keySet()).isEqualTo(before);
		String key = jdbcTemplate.queryForObject("SELECT cover_image_object_key FROM course WHERE id = ?",
				String.class, ctx.course().id());
		assertThat(key).isNull();
	}

	@Test
	void oversizedCoverIsRejected() {
		Ctx ctx = setUp("cover-size");
		byte[] big = new byte[5 * 1024 * 1024 + 1];
		System.arraycopy(PNG_BYTES, 0, big, 0, PNG_BYTES.length);

		MvcResult result = upload(ctx.host(), ctx.token(), ctx.course().id(),
				new MockMultipartFile("file", "big.png", "image/png", big));

		assertThat(result.getResponse().getStatus()).isEqualTo(413);
	}

	@Test
	void validCoverIsStoredUnderATenantKeyExposedOnlyAsASignedUrlAndCanBeRemoved() {
		Ctx ctx = setUp("cover-ok");

		MvcResult result = upload(ctx.host(), ctx.token(), ctx.course().id(),
				new MockMultipartFile("file", "cover.png", "image/png", PNG_BYTES));

		assertThat(result.getResponse().getStatus()).as(rawContent(result)).isEqualTo(200);
		String key = jdbcTemplate.queryForObject("SELECT cover_image_object_key FROM course WHERE id = ?",
				String.class, ctx.course().id());
		assertThat(key).isNotNull();
		assertThat(storage.containsKey(key)).isTrue();
		assertThat(json(result).path("data").path("coverImageUrl").asString()).contains(key);
		assertThat(rawContent(result)).doesNotContain("coverImageObjectKey");
		Long audits = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM audit_log WHERE action = 'course.cover_changed' AND target_id = ?", Long.class,
				ctx.course().id());
		assertThat(audits).isEqualTo(1L);

		MvcResult removed = perform(
				authenticated(delete("/api/v1/courses/{id}/cover", ctx.course().id()), ctx.host(), ctx.token()));
		assertThat(removed.getResponse().getStatus()).isEqualTo(200);
		assertThat(json(removed).path("data").path("coverImageUrl").isNull()).isTrue();
		assertThat(storage.containsKey(key)).isFalse();
	}

	@Test
	@Tag("cross-tenant")
	void crossTenantCoverUploadReturns404WithoutStoring() {
		Ctx a = setUp("cover-xt-a");
		Ctx b = setUp("cover-xt-b");
		Set<String> before = new HashSet<>(storage.keySet());

		MvcResult result = upload(b.host(), b.token(), a.course().id(),
				new MockMultipartFile("file", "cover.png", "image/png", PNG_BYTES));

		assertThat(result.getResponse().getStatus()).isEqualTo(404);
		assertThat(storage.keySet()).isEqualTo(before);
	}

}
