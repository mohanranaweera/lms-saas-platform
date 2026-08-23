package com.lms.paymentmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.paymentmanagement.order.web.dto.OrderResponse;
import com.lms.paymentmanagement.order.web.dto.PaymentInitiationResponse;
import com.lms.paymentmanagement.payment.domain.Payment;
import com.lms.paymentmanagement.payment.repository.PaymentRepository;
import com.lms.tenantmanagement.domain.Tenant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Documents/regression-guards MVP-010 review item 6: {@code
 * uq_payment_gateway_reference} (V19) is a deliberate, ACCEPTED design
 * choice - GLOBAL (platform-wide), not tenant-scoped - because the
 * webhook-confirmation path resolves tenant identity FROM the gateway
 * reference itself, before any tenant context exists (see V19's own header
 * comment and {@code PaymentConfirmationService}'s javadoc). This is not a
 * bug to fix; this test exists to make that behavior explicit and
 * regression-proof: two different tenants' payments can never both hold the
 * same {@code gateway_reference}, and the resulting DB-level unique-
 * constraint violation surfaces as a clean, translated Spring exception -
 * never an unhandled crash.
 */
class GatewayReferenceGlobalUniquenessIntegrationTest extends PaymentManagementTestSupport {

	@Autowired
	private PaymentRepository paymentRepository;

	@Test
	void twoDifferentTenantsPaymentsCanNeverShareTheSameGatewayReference() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("pay-gwref-a"));
		seedTenantUser(tenantA.getId(), "admin-a@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacherA = seedTenantUser(tenantA.getId(), "teacher-a@example.test", RAW_PASSWORD, Role.TEACHER);
		seedActiveStudent(tenantA.getId(), "student-a@example.test");
		String hostA = hostFor(tenantA.getSubdomain());
		String adminTokenA = loginAndGetToken(hostA, "admin-a@example.test");
		String studentTokenA = loginAndGetToken(hostA, "student-a@example.test");
		CourseResponse courseA = createCourseOrFail(hostA, adminTokenA,
				newCourseRequest(uniqueSlug("pay-gwref-a"), teacherA.getId(), CourseStatus.PUBLIC));
		OrderResponse orderA = createOrderOrFail(hostA, studentTokenA, courseA.id());
		PaymentInitiationResponse initiationA = initiatePaymentOrFail(hostA, studentTokenA, orderA.id());
		String sharedReference = initiationA.gatewayReference();

		Tenant tenantB = seedActiveTenant(uniqueSubdomain("pay-gwref-b"));
		seedTenantUser(tenantB.getId(), "admin-b@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacherB = seedTenantUser(tenantB.getId(), "teacher-b@example.test", RAW_PASSWORD, Role.TEACHER);
		seedActiveStudent(tenantB.getId(), "student-b@example.test");
		String hostB = hostFor(tenantB.getSubdomain());
		String adminTokenB = loginAndGetToken(hostB, "admin-b@example.test");
		String studentTokenB = loginAndGetToken(hostB, "student-b@example.test");
		CourseResponse courseB = createCourseOrFail(hostB, adminTokenB,
				newCourseRequest(uniqueSlug("pay-gwref-b"), teacherB.getId(), CourseStatus.PUBLIC));
		OrderResponse orderB = createOrderOrFail(hostB, studentTokenB, courseB.id());
		PaymentInitiationResponse initiationB = initiatePaymentOrFail(hostB, studentTokenB, orderB.id());

		// The real FakePaymentGatewayAdapter always mints a fresh UUID-based
		// reference, so this collision could only happen via a
		// compromised/buggy external gateway - simulated directly at the
		// repository layer (standing in for what PaymentWriteService#
		// assignGatewayReference would do), inside tenant B's own tenant
		// context, exactly like the real call it stands in for.
		assertThatThrownBy(() -> withTenant(tenantB.getId(), () -> {
			Payment paymentB = paymentRepository.findById(initiationB.paymentId()).orElseThrow();
			paymentB.assignGatewayReference(sharedReference);
			return paymentRepository.save(paymentB);
		})).isInstanceOf(DataIntegrityViolationException.class);

		// A clean, translated failure - not a crash - and tenant A's own
		// payment (the only legitimate holder of this reference) is
		// untouched.
		Long paymentsWithSharedReference = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM payment WHERE gateway_reference = ?", Long.class, sharedReference);
		assertThat(paymentsWithSharedReference).isEqualTo(1L);
		String ownerTenantId = jdbcTemplate.queryForObject(
				"SELECT tenant_id::text FROM payment WHERE gateway_reference = ?", String.class, sharedReference);
		assertThat(ownerTenantId).isEqualTo(tenantA.getId().toString());
	}

}
