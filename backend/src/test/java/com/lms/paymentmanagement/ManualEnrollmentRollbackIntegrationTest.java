package com.lms.paymentmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.ledgersettlementmanagement.api.LedgerEntryApi;
import com.lms.tenantmanagement.domain.Tenant;
import com.lms.usermanagement.student.web.dto.StudentCreateRequest;
import com.lms.usermanagement.student.web.dto.StudentResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Wave 3 fix-pass (payment-ledger review, "enroll flow test gaps") - mirrors
 * {@link PaymentConfirmationRollbackIntegrationTest}'s exact technique for the
 * NEW manual-enroll chain (staff "enroll student in course"): {@code
 * ManualEnrollmentService#grantEnrollment} is one {@code @Transactional}
 * method that writes {@code student_order}, {@code payment}, a {@code
 * ledger_entry}, and (via {@code EnrollmentActivationApi}) an {@code
 * enrollment} row, all inside one transaction. This test forces the {@code
 * ledger_entry} write to throw (a real Spring context, real Testcontainers
 * Postgres, only {@link LedgerEntryApi} replaced by a throwing mock) and
 * proves the ENTIRE transaction rolls back: no {@code student_order} row, no
 * {@code payment} row, and no {@code enrollment} row survive - never a
 * partial Order-without-Payment or Payment-without-Enrollment state.
 */
class ManualEnrollmentRollbackIntegrationTest extends PaymentManagementTestSupport {

	@MockitoBean
	private LedgerEntryApi ledgerEntryApi;

	@Test
	void aFailureWritingTheLedgerEntryRollsBackTheEntireManualEnrollmentTransaction() {
		doThrow(new RuntimeException("Simulated mid-transaction failure recording the ledger entry"))
			.when(ledgerEntryApi)
			.recordPaymentConfirmed(any(), any(), any());

		Tenant tenant = seedActiveTenant(uniqueSubdomain("manual-enroll-rollback"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		StudentResponse student = createStudentProfileOrFail(host, adminToken, uniqueEmail("manual-enroll-rollback"));
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug("manual-enroll-rollback"), teacher.getId(), CourseStatus.PUBLIC));

		HttpResult<Void> enrollResult = postEnroll(host, adminToken, student.id(), course.id(), "scholarship grant");

		assertThat(enrollResult.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

		Long orderCount = jdbcTemplate.queryForObject("SELECT count(*) FROM student_order WHERE tenant_id = ?",
				Long.class, tenant.getId());
		assertThat(orderCount).isEqualTo(0L);

		Long paymentCount = jdbcTemplate.queryForObject("SELECT count(*) FROM payment WHERE tenant_id = ?", Long.class,
				tenant.getId());
		assertThat(paymentCount).isEqualTo(0L);

		Long enrollmentCount = jdbcTemplate.queryForObject("SELECT count(*) FROM enrollment WHERE tenant_id = ?",
				Long.class, tenant.getId());
		assertThat(enrollmentCount).isEqualTo(0L);
	}

	// ------------------------------------------------------------------
	// Local plumbing helpers.
	// ------------------------------------------------------------------

	private StudentResponse createStudentProfileOrFail(String host, String token, String email) {
		MockHttpServletRequestBuilder builder = post("/api/v1/students").contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(new StudentCreateRequest("Test Student", email, RAW_PASSWORD)));
		HttpResult<StudentResponse> result = parseSingle(perform(authenticated(builder, host, token)),
				StudentResponse.class);
		if (result.getStatusCode() != HttpStatus.CREATED) {
			throw new IllegalStateException("Student creation failed: " + result.getStatusCode());
		}
		return result.getBody().data();
	}

	private HttpResult<Void> postEnroll(String host, String token, java.util.UUID studentProfileId,
			java.util.UUID courseId, String reason) {
		String body = "{\"courseId\":\"" + courseId + "\",\"reason\":\"" + reason + "\"}";
		MockHttpServletRequestBuilder builder = post("/api/v1/students/{id}/enroll", studentProfileId)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body);
		return parseSingle(perform(authenticated(builder, host, token)), Void.class);
	}

	private static String uniqueEmail(String prefix) {
		return prefix + "-" + java.util.UUID.randomUUID().toString().substring(0, 8) + "@example.test";
	}

}
