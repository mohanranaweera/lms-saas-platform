package com.lms.auditlogmanagement;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import com.lms.auditlogmanagement.web.dto.AuditLogEntryResponse;
import com.lms.common.api.PageResponse;
import com.lms.contentmanagement.material.web.dto.MaterialResponse;
import com.lms.identityaccessservice.HttpResult;
import com.lms.integrationmanagement.InMemoryObjectStorageApiTestConfig;
import com.lms.paymentmanagement.PaymentManagementTestSupport;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/**
 * Shared Testcontainers/MockMvc helpers for {@code audit-log-management}'s
 * (MVP-019) integration tests. Extends {@link PaymentManagementTestSupport}
 * (which itself extends {@code CourseManagementTestSupport}) rather than
 * {@code AuthIntegrationTestSupport} directly, so tests here get
 * tenant/user/course seeding, price-change, and order/payment/webhook/refund
 * helpers for free - three of this module's audited flows (price change,
 * refund) are already reachable through that chain.
 *
 * <p>{@code content-management}'s material endpoints are NOT reachable
 * through that chain (its own {@code ContentManagementTestSupport} extends
 * {@code CourseManagementTestSupport} directly, a sibling branch, not an
 * ancestor of {@code PaymentManagementTestSupport}), so the minimal
 * material create/delete helpers needed for the material-deletion audit
 * flow are duplicated here, mirroring {@code ContentManagementTestSupport}'s
 * exact request-building style (including importing the same shared
 * {@link InMemoryObjectStorageApiTestConfig} fake, so uploads succeed
 * against a real in-memory object store rather than the production 503
 * stub).
 */
@Import(InMemoryObjectStorageApiTestConfig.class)
public abstract class AuditLogManagementTestSupport extends PaymentManagementTestSupport {

	// ------------------------------------------------------------------
	// Minimal material fixture helper (copied from
	// ContentManagementTestSupport.textNotesFile(...), which is not
	// reachable from this support class's inheritance chain).
	// ------------------------------------------------------------------

	protected static MockMultipartFile textNotesFile(String filename) {
		byte[] bytes = "Lecture notes\nSection 1: Introduction\nSection 2: Key concepts"
			.getBytes(StandardCharsets.UTF_8);
		return new MockMultipartFile("file", filename, "text/plain", bytes);
	}

	// ------------------------------------------------------------------
	// Material endpoints (minimal subset needed for the material-deletion
	// audit flow).
	// ------------------------------------------------------------------

	protected HttpResult<MaterialResponse> createMaterial(String host, String token, UUID courseId, UUID moduleId,
			UUID lessonId, String title, MockMultipartFile file) {
		MockMultipartHttpServletRequestBuilder builder = multipart(
				"/api/v1/courses/{courseId}/modules/{moduleId}/lessons/{lessonId}/materials", courseId, moduleId,
				lessonId).file(file).param("title", title);
		return parseSingle(performMultipart(authenticatedMultipart(builder, host, token)), MaterialResponse.class);
	}

	protected MaterialResponse createMaterialOrFail(String host, String token, UUID courseId, UUID moduleId,
			UUID lessonId, String title, MockMultipartFile file) {
		HttpResult<MaterialResponse> result = createMaterial(host, token, courseId, moduleId, lessonId, title, file);
		if (result.getStatusCode() != HttpStatus.CREATED) {
			throw new IllegalStateException(
					"Material creation failed: " + result.getStatusCode() + " " + result.getBody());
		}
		return result.getBody().data();
	}

	protected HttpResult<Void> deleteMaterial(String host, String token, UUID courseId, UUID moduleId, UUID lessonId,
			UUID materialId) {
		MockHttpServletRequestBuilder builder = delete(
				"/api/v1/courses/{courseId}/modules/{moduleId}/lessons/{lessonId}/materials/{materialId}", courseId,
				moduleId, lessonId, materialId);
		return parseSingle(perform(authenticated(builder, host, token)), Void.class);
	}

	// ------------------------------------------------------------------
	// Audit log read endpoint.
	// ------------------------------------------------------------------

	protected HttpResult<PageResponse<AuditLogEntryResponse>> auditLogSearch(String host, String token) {
		return auditLogSearch(host, token, null);
	}

	protected HttpResult<PageResponse<AuditLogEntryResponse>> auditLogSearch(String host, String token,
			String queryString) {
		String path = (queryString == null || queryString.isBlank()) ? "/api/v1/audit-log"
				: "/api/v1/audit-log?" + queryString;
		MockHttpServletRequestBuilder builder = get(path);
		return parsePage(perform(authenticated(builder, host, token)), AuditLogEntryResponse.class);
	}

	// ------------------------------------------------------------------
	// Direct-insert seeding for tests that need precise control over
	// occurred_at/action/targetEntity combinations (pagination/filter
	// tests) rather than triggering every row through a real domain flow -
	// explicitly sanctioned for this purpose by the test plan, as long as
	// tenant_id/actor_id (an FK to a real tenant_user row) stay correct.
	// ------------------------------------------------------------------

	protected UUID seedAuditLogRow(UUID tenantId, UUID actorId, String action, String targetEntity, UUID targetId,
			Instant occurredAt) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update(
				"INSERT INTO audit_log (id, tenant_id, actor_id, action, target_entity, target_id, reason, "
						+ "metadata, occurred_at) VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, ?)",
				id, tenantId, actorId, action, targetEntity, targetId, Timestamp.from(occurredAt));
		return id;
	}

	/**
	 * Same as {@link #seedAuditLogRow}, but with an explicit {@code metadata}
	 * value rather than {@code NULL} - lets a test seed a row whose
	 * {@code metadata} is syntactically-valid JSON (so it still satisfies the
	 * {@code jsonb} column - Postgres itself would reject truly-malformed
	 * JSON text at insert time) but does not deserialize into the {@code
	 * Map<String, Object>} shape {@code AuditLogQueryService
	 * #deserializeMetadata} expects (e.g. a JSON array like {@code "[1,2,3]"}
	 * instead of a JSON object), exercising that method's error path.
	 */
	protected UUID seedAuditLogRowWithMetadata(UUID tenantId, UUID actorId, String action, String targetEntity,
			UUID targetId, Instant occurredAt, String metadataJson) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update(
				"INSERT INTO audit_log (id, tenant_id, actor_id, action, target_entity, target_id, reason, "
						+ "metadata, occurred_at) VALUES (?, ?, ?, ?, ?, ?, NULL, ?::jsonb, ?)",
				id, tenantId, actorId, action, targetEntity, targetId, metadataJson, Timestamp.from(occurredAt));
		return id;
	}

	// ------------------------------------------------------------------
	// Multipart HTTP request/response plumbing (parallel to
	// CourseManagementTestSupport's authenticated/perform, which are typed
	// to the non-multipart MockHttpServletRequestBuilder and so can't be
	// reused directly for a MockMultipartHttpServletRequestBuilder).
	// ------------------------------------------------------------------

	protected MockMultipartHttpServletRequestBuilder authenticatedMultipart(
			MockMultipartHttpServletRequestBuilder builder, String host, String token) {
		if (host != null) {
			builder.header(HttpHeaders.HOST, host);
		}
		if (token != null) {
			builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
		}
		return builder;
	}

	protected MvcResult performMultipart(MockMultipartHttpServletRequestBuilder request) {
		try {
			return mockMvc.perform(request).andReturn();
		}
		catch (Exception e) {
			throw new IllegalStateException("MockMvc multipart request failed", e);
		}
	}

}
