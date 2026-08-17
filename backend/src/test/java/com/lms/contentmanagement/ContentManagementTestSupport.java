package com.lms.contentmanagement;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import com.lms.contentmanagement.material.domain.MaterialVisibility;
import com.lms.contentmanagement.material.web.dto.MaterialDownloadUrlResponse;
import com.lms.contentmanagement.material.web.dto.MaterialResponse;
import com.lms.contentmanagement.material.web.dto.MaterialUpdateRequest;
import com.lms.coursemanagement.CourseManagementTestSupport;
import com.lms.identityaccessservice.HttpResult;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/**
 * Shared Testcontainers/MockMvc helpers for Content Management (MVP-009)'s
 * integration tests, mirroring {@code CourseManagementTestSupport}'s
 * established technique exactly (MockMvc, not a real socket round-trip, so
 * {@code Host} header spoofing - which {@code TenantResolutionFilter} depends
 * on - works; see {@code HttpResult}'s javadoc). Extends {@code
 * CourseManagementTestSupport} so material tests get its tenant/user/course
 * /module/lesson seeding and login helpers for free - materials always
 * attach to a real course/module/lesson. Also imports {@link
 * InMemoryObjectStorageApiTestConfig} so {@code createMaterial}/{@code
 * getDownloadUrl}/{@code deleteMaterial} actually succeed against a real
 * (in-memory) object store instead of the production {@code
 * UnavailableObjectStorageApi} 503 stub. Not itself a test class (no {@code
 * @Test} methods, name doesn't match Surefire's inclusion patterns).
 */
@Import(InMemoryObjectStorageApiTestConfig.class)
public abstract class ContentManagementTestSupport extends CourseManagementTestSupport {

	// ------------------------------------------------------------------
	// Fixture byte helpers - matching ContentSniffer's accepted signatures
	// (and one attack payload that deliberately fails every signature).
	// ------------------------------------------------------------------

	protected static byte[] validPdfBytes() {
		return "%PDF-1.4\n%Fixture PDF content for MVP-009 integration tests.\n%%EOF"
			.getBytes(StandardCharsets.US_ASCII);
	}

	protected static byte[] validPngBytes() {
		byte[] signature = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' };
		byte[] padding = "Fixture PNG payload padding for MVP-009 integration tests."
			.getBytes(StandardCharsets.US_ASCII);
		byte[] bytes = new byte[signature.length + padding.length];
		System.arraycopy(signature, 0, bytes, 0, signature.length);
		System.arraycopy(padding, 0, bytes, signature.length, padding.length);
		return bytes;
	}

	protected static byte[] validTextNotesBytes() {
		return "Lecture notes\nSection 1: Introduction\nSection 2: Key concepts"
			.getBytes(StandardCharsets.UTF_8);
	}

	/**
	 * A renamed-executable attack payload: begins with the Windows PE/EXE
	 * "MZ" DOS-stub magic header - matches no {@code ContentSniffer}
	 * signature (the NUL bytes in a realistic DOS header also independently
	 * fail its plain-text fallback), but is uploaded under a {@code .pdf}
	 * filename/declared content type to simulate the disguise scenario the
	 * whole module exists to catch.
	 */
	protected static byte[] executableDisguisedAsPdfBytes() {
		return new byte[] { 0x4D, 0x5A, (byte) 0x90, 0x00, 0x03, 0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00,
				(byte) 0xFF, (byte) 0xFF, 0x00, 0x00, (byte) 0xB8, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x40,
				0x00, 0x00, 0x00 };
	}

	protected static MockMultipartFile pdfFile(String filename) {
		return new MockMultipartFile("file", filename, "application/pdf", validPdfBytes());
	}

	protected static MockMultipartFile pngFile(String filename) {
		return new MockMultipartFile("file", filename, "image/png", validPngBytes());
	}

	protected static MockMultipartFile textNotesFile(String filename) {
		return new MockMultipartFile("file", filename, "text/plain", validTextNotesBytes());
	}

	protected static MockMultipartFile disguisedExecutableFile(String filename) {
		return new MockMultipartFile("file", filename, "application/pdf", executableDisguisedAsPdfBytes());
	}

	/** A PDF-signed payload padded out to at least {@code sizeBytes} bytes, for max-file-size tests. */
	protected static MockMultipartFile oversizedPdfFile(String filename, int sizeBytes) {
		byte[] header = "%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII);
		byte[] bytes = new byte[Math.max(sizeBytes, header.length)];
		System.arraycopy(header, 0, bytes, 0, header.length);
		return new MockMultipartFile("file", filename, "application/pdf", bytes);
	}

	// ------------------------------------------------------------------
	// Material endpoints.
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

	protected HttpResult<List<MaterialResponse>> listMaterials(String host, String token, UUID courseId,
			UUID moduleId, UUID lessonId) {
		MockHttpServletRequestBuilder builder = get(
				"/api/v1/courses/{courseId}/modules/{moduleId}/lessons/{lessonId}/materials", courseId, moduleId,
				lessonId);
		return parseList(perform(authenticated(builder, host, token)), MaterialResponse.class);
	}

	protected HttpResult<MaterialResponse> getMaterial(String host, String token, UUID courseId, UUID moduleId,
			UUID lessonId, UUID materialId) {
		MockHttpServletRequestBuilder builder = get(
				"/api/v1/courses/{courseId}/modules/{moduleId}/lessons/{lessonId}/materials/{materialId}", courseId,
				moduleId, lessonId, materialId);
		return parseSingle(perform(authenticated(builder, host, token)), MaterialResponse.class);
	}

	protected HttpResult<MaterialDownloadUrlResponse> getDownloadUrl(String host, String token, UUID courseId,
			UUID moduleId, UUID lessonId, UUID materialId) {
		MockHttpServletRequestBuilder builder = get(
				"/api/v1/courses/{courseId}/modules/{moduleId}/lessons/{lessonId}/materials/{materialId}/download-url",
				courseId, moduleId, lessonId, materialId);
		return parseSingle(perform(authenticated(builder, host, token)), MaterialDownloadUrlResponse.class);
	}

	protected HttpResult<MaterialResponse> updateMaterial(String host, String token, UUID courseId, UUID moduleId,
			UUID lessonId, UUID materialId, String title, int sequence, MaterialVisibility visibility) {
		MockHttpServletRequestBuilder builder = patch(
				"/api/v1/courses/{courseId}/modules/{moduleId}/lessons/{lessonId}/materials/{materialId}", courseId,
				moduleId, lessonId, materialId).contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(new MaterialUpdateRequest(title, sequence, visibility)));
		return parseSingle(perform(authenticated(builder, host, token)), MaterialResponse.class);
	}

	protected HttpResult<Void> deleteMaterial(String host, String token, UUID courseId, UUID moduleId, UUID lessonId,
			UUID materialId) {
		MockHttpServletRequestBuilder builder = delete(
				"/api/v1/courses/{courseId}/modules/{moduleId}/lessons/{lessonId}/materials/{materialId}", courseId,
				moduleId, lessonId, materialId);
		return parseSingle(perform(authenticated(builder, host, token)), Void.class);
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
