package com.lms.paymentmanagement;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.lms.common.api.PageResponse;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.integrationmanagement.InMemoryObjectStorageApiTestConfig;
import com.lms.paymentmanagement.order.web.dto.OrderResponse;
import com.lms.paymentmanagement.slip.domain.PaymentSlipStatus;
import com.lms.paymentmanagement.slip.web.dto.PaymentSlipResponse;
import com.lms.paymentmanagement.slip.web.dto.SlipDownloadUrlResponse;
import com.lms.tenantmanagement.domain.Tenant;
import java.nio.charset.StandardCharsets;
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
 * Shared Testcontainers/MockMvc helpers for MVP-011 (Manual Payment Slips)'s
 * integration tests, mirroring {@code ContentManagementTestSupport}'s
 * established multipart-upload technique exactly. Extends {@code
 * PaymentManagementTestSupport} so slip tests get its order/payment seeding
 * and login helpers for free, and imports {@link
 * InMemoryObjectStorageApiTestConfig} (shared with {@code
 * ContentManagementTestSupport} - the same {@code integration-management}
 * fake, not a slip-local double) so upload/download-url calls actually
 * succeed against a real (in-memory) object store instead of the production
 * {@code UnavailableObjectStorageApi} 503 stub. Not itself a test class (no
 * {@code @Test} methods, name doesn't match Surefire's inclusion patterns).
 */
@Import(InMemoryObjectStorageApiTestConfig.class)
public abstract class SlipTestSupport extends PaymentManagementTestSupport {

	// ------------------------------------------------------------------
	// Tenant/order fixture seeding.
	// ------------------------------------------------------------------

	protected SlipFixture seedTenantWithOrder(String prefix) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain(prefix));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		seedTenantUser(tenant.getId(), "finance@example.test", RAW_PASSWORD, Role.FINANCE_STAFF);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser student = seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String financeToken = loginAndGetToken(host, "finance@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug(prefix), teacher.getId(), CourseStatus.PUBLIC));
		OrderResponse order = createOrderOrFail(host, studentToken, course.id());
		return new SlipFixture(tenant, host, adminToken, financeToken, studentToken, teacher, student, course, order);
	}

	/** A second order for the same tenant/student (a different course) - used by duplicate-detection tests. */
	protected OrderResponse createAnotherOrder(SlipFixture fixture, String coursePrefix) {
		CourseResponse course = createCourseOrFail(fixture.host(), fixture.adminToken(),
				newCourseRequest(uniqueSlug(coursePrefix), fixture.teacher().getId(), CourseStatus.PUBLIC));
		return createOrderOrFail(fixture.host(), fixture.studentToken(), course.id());
	}

	protected record SlipFixture(Tenant tenant, String host, String adminToken, String financeToken,
			String studentToken, TenantUser teacher, TenantUser student, CourseResponse course, OrderResponse order) {
	}

	// ------------------------------------------------------------------
	// Fixture byte helpers - matching SlipContentSniffer's accepted
	// signatures (PDF/image only, deliberately no plain-text "notes" branch -
	// see that class's own javadoc), and one attack payload that deliberately
	// fails every signature.
	// ------------------------------------------------------------------

	protected static byte[] validPdfBytes() {
		return "%PDF-1.4\n%Fixture PDF content for MVP-011 tests.\n%%EOF".getBytes(StandardCharsets.US_ASCII);
	}

	/**
	 * A valid, {@code SlipContentSniffer}-accepted PDF whose bytes (and
	 * therefore SHA-256 hash) are unique per {@code seed} - unlike {@link
	 * #validPdfBytes()}, which is a single fixed byte array reused by every
	 * caller. Use this whenever a test needs two uploads to share a reference
	 * number but NOT an image hash (isolating the reference-number duplicate
	 * check from the image-hash duplicate check), or vice versa.
	 */
	protected static byte[] distinctPdfBytes(String seed) {
		return ("%PDF-1.4\n%Fixture PDF content for MVP-011 tests - " + seed + "\n%%EOF")
			.getBytes(StandardCharsets.US_ASCII);
	}

	protected static byte[] validPngBytes() {
		byte[] signature = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' };
		byte[] padding = "Fixture PNG payload padding for MVP-011 tests.".getBytes(StandardCharsets.US_ASCII);
		byte[] bytes = new byte[signature.length + padding.length];
		System.arraycopy(signature, 0, bytes, 0, signature.length);
		System.arraycopy(padding, 0, bytes, signature.length, padding.length);
		return bytes;
	}

	protected static byte[] plainTextBytes() {
		return "This is a plain-text file - not a PDF or image, and (unlike Module 9's ContentSniffer) SlipContentSniffer has no plain-text branch to accept it."
			.getBytes(StandardCharsets.UTF_8);
	}

	/**
	 * A renamed-executable attack payload: begins with the Windows PE/EXE
	 * "MZ" DOS-stub magic header - matches no {@code SlipContentSniffer}
	 * signature - but is uploaded under a {@code .pdf} filename/declared
	 * content type to simulate the disguise scenario the module exists to
	 * catch.
	 */
	protected static byte[] executableDisguisedAsPdfBytes() {
		return new byte[] { 0x4D, 0x5A, (byte) 0x90, 0x00, 0x03, 0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00,
				(byte) 0xFF, (byte) 0xFF, 0x00, 0x00, (byte) 0xB8, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x40,
				0x00, 0x00, 0x00 };
	}

	protected static MockMultipartFile pdfFile(String filename) {
		return new MockMultipartFile("file", filename, "application/pdf", validPdfBytes());
	}

	protected static MockMultipartFile pdfFile(String filename, byte[] bytes) {
		return new MockMultipartFile("file", filename, "application/pdf", bytes);
	}

	protected static MockMultipartFile disguisedExecutableFile(String filename) {
		return new MockMultipartFile("file", filename, "application/pdf", executableDisguisedAsPdfBytes());
	}

	protected static MockMultipartFile plainTextFile(String filename) {
		return new MockMultipartFile("file", filename, "text/plain", plainTextBytes());
	}

	/** A PDF-signed payload padded out to at least {@code sizeBytes} bytes, for max-file-size tests. */
	protected static MockMultipartFile oversizedPdfFile(String filename, int sizeBytes) {
		byte[] header = "%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII);
		byte[] bytes = new byte[Math.max(sizeBytes, header.length)];
		System.arraycopy(header, 0, bytes, 0, header.length);
		return new MockMultipartFile("file", filename, "application/pdf", bytes);
	}

	// ------------------------------------------------------------------
	// Slip endpoints.
	// ------------------------------------------------------------------

	protected HttpResult<PaymentSlipResponse> uploadSlip(String host, String token, UUID orderId,
			String referenceNumber, MockMultipartFile file) {
		MockMultipartHttpServletRequestBuilder builder = multipart("/api/v1/orders/{orderId}/slips", orderId)
			.file(file)
			.param("referenceNumber", referenceNumber);
		return parseSingle(performMultipart(authenticatedMultipart(builder, host, token)), PaymentSlipResponse.class);
	}

	protected PaymentSlipResponse uploadSlipOrFail(String host, String token, UUID orderId, String referenceNumber,
			MockMultipartFile file) {
		HttpResult<PaymentSlipResponse> result = uploadSlip(host, token, orderId, referenceNumber, file);
		if (result.getStatusCode() != HttpStatus.CREATED) {
			throw new IllegalStateException(
					"Slip upload failed: " + result.getStatusCode() + " " + result.getBody());
		}
		return result.getBody().data();
	}

	protected HttpResult<PaymentSlipResponse> getSlip(String host, String token, UUID slipId) {
		MockHttpServletRequestBuilder builder = get("/api/v1/payment-slips/{slipId}", slipId);
		return parseSingle(perform(authenticated(builder, host, token)), PaymentSlipResponse.class);
	}

	protected HttpResult<SlipDownloadUrlResponse> getSlipDownloadUrl(String host, String token, UUID slipId) {
		MockHttpServletRequestBuilder builder = get("/api/v1/payment-slips/{slipId}/download-url", slipId);
		return parseSingle(perform(authenticated(builder, host, token)), SlipDownloadUrlResponse.class);
	}

	/**
	 * Raw, unparsed JSON response body for the slip-detail read - used by
	 * protected-content tests that must prove a raw storage key is absent
	 * from the actual wire response, not merely from the (already
	 * key-less-by-construction) parsed DTO.
	 */
	protected String getSlipRawJson(String host, String token, UUID slipId) {
		MockHttpServletRequestBuilder builder = get("/api/v1/payment-slips/{slipId}", slipId);
		return rawContent(perform(authenticated(builder, host, token)));
	}

	/** Raw, unparsed JSON response body for the download-url read - see {@link #getSlipRawJson}. */
	protected String getSlipDownloadUrlRawJson(String host, String token, UUID slipId) {
		MockHttpServletRequestBuilder builder = get("/api/v1/payment-slips/{slipId}/download-url", slipId);
		return rawContent(perform(authenticated(builder, host, token)));
	}

	protected HttpResult<PageResponse<PaymentSlipResponse>> getReviewQueue(String host, String token,
			PaymentSlipStatus status) {
		String query = (status != null) ? "?status=" + status.name() : "";
		MockHttpServletRequestBuilder builder = get("/api/v1/payment-slips/review-queue" + query);
		return parsePage(perform(authenticated(builder, host, token)), PaymentSlipResponse.class);
	}

	protected HttpResult<PaymentSlipResponse> approveSlip(String host, String token, UUID slipId,
			String overrideReason) {
		String body = (overrideReason != null) ? "{\"overrideReason\":" + jsonString(overrideReason) + "}" : "{}";
		MockHttpServletRequestBuilder builder = post("/api/v1/payment-slips/{slipId}/approve", slipId)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body);
		return parseSingle(perform(authenticated(builder, host, token)), PaymentSlipResponse.class);
	}

	protected HttpResult<PaymentSlipResponse> approveSlipNoBody(String host, String token, UUID slipId) {
		MockHttpServletRequestBuilder builder = post("/api/v1/payment-slips/{slipId}/approve", slipId);
		return parseSingle(perform(authenticated(builder, host, token)), PaymentSlipResponse.class);
	}

	protected HttpResult<PaymentSlipResponse> rejectSlip(String host, String token, UUID slipId, String reason) {
		String body = "{\"reason\":" + jsonString(reason) + "}";
		MockHttpServletRequestBuilder builder = post("/api/v1/payment-slips/{slipId}/reject", slipId)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body);
		return parseSingle(perform(authenticated(builder, host, token)), PaymentSlipResponse.class);
	}

	private static String jsonString(String raw) {
		if (raw == null) {
			return "null";
		}
		return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

	// ------------------------------------------------------------------
	// Multipart HTTP request/response plumbing (parallel to
	// CourseManagementTestSupport's authenticated/perform, which are typed to
	// the non-multipart MockHttpServletRequestBuilder and so can't be reused
	// directly for a MockMultipartHttpServletRequestBuilder).
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
