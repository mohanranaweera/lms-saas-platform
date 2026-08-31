package com.lms.enrollmentmanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.enrollmentmanagement.api.EnrollmentReconciliationApi;
import com.lms.enrollmentmanagement.api.OrphanedEnrollmentEvidence;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.paymentmanagement.order.web.dto.OrderResponse;
import com.lms.paymentmanagement.order.web.dto.PaymentInitiationResponse;
import com.lms.tenantmanagement.domain.Tenant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * Proves {@link EnrollmentReconciliationApi} correctly identifies an {@code
 * enrollment} row whose {@code activating_payment_id} no longer resolves to a
 * {@code CONFIRMED} payment, and does NOT flag an ordinary, fully consistent
 * activation. In normal operation this diagnostic should always be empty -
 * since {@code EnrollmentActivationApi}'s reactivation methods now run in the
 * same transaction as their confirming payment/slip write, this exact
 * inconsistency can no longer arise through any production code path. The
 * corruption below is seeded directly via {@code jdbcTemplate} (never through
 * any production code path) purely to exercise this diagnostic's detection
 * logic against a simulated data-integrity anomaly (e.g. direct data
 * tampering, or a future regression), not to reproduce a reachable race.
 */
class EnrollmentReconciliationQueryIntegrationTest extends EnrollmentManagementTestSupport {

	@Autowired
	private EnrollmentReconciliationApi enrollmentReconciliationApi;

	@Test
	void doesNotFlagAnEnrollmentBackedByAGenuinelyConfirmedPayment() {
		Fixture fixture = seedConfirmedActivationFixture("consistent");

		List<OrphanedEnrollmentEvidence> results = enrollmentReconciliationApi
			.findEnrollmentsWithUnconfirmedActivationEvidenceAcrossTenants();

		assertThat(results).noneMatch(result -> result.enrollmentId().equals(fixture.enrollmentId()));
	}

	@Test
	void flagsAnEnrollmentWhosePaymentEvidenceHasSinceLeftTheConfirmedState() {
		Fixture fixture = seedConfirmedActivationFixture("orphaned");

		// Simulates a data-integrity anomaly (direct tampering, or a future
		// regression) - not a reachable production race, see class javadoc.
		int updated = jdbcTemplate.update("UPDATE payment SET status = 'REJECTED' WHERE id = ?", fixture.paymentId());
		assertThat(updated).isEqualTo(1);

		List<OrphanedEnrollmentEvidence> results = enrollmentReconciliationApi
			.findEnrollmentsWithUnconfirmedActivationEvidenceAcrossTenants();

		OrphanedEnrollmentEvidence flagged = results.stream()
			.filter(result -> result.enrollmentId().equals(fixture.enrollmentId()))
			.findFirst()
			.orElseThrow(
					() -> new AssertionError("Expected the corrupted enrollment to be flagged, got: " + results));
		assertThat(flagged.tenantId()).isEqualTo(fixture.tenant().getId());
		assertThat(flagged.activatingPaymentId()).isEqualTo(fixture.paymentId());
		assertThat(flagged.activatingSlipId()).isNull();
		// The relocated implementation cross-checks via PaymentStatusApi's
		// existing boolean-only isConfirmedForCurrentTenant (deliberately NOT
		// grown into a status-returning variant just for this diagnostic -
		// see EnrollmentReconciliationApi's javadoc), so the reason text
		// names the flagged id and tenant, not the payment's literal current
		// status.
		assertThat(flagged.reason()).contains(fixture.paymentId().toString()).contains("does not correspond to a "
				+ "CONFIRMED payment");
	}

	// ------------------------------------------------------------------
	// Fixture seeding.
	// ------------------------------------------------------------------

	private record Fixture(Tenant tenant, UUID enrollmentId, UUID paymentId) {
	}

	/**
	 * Seeds a tenant, teacher, student, published course, and completes a
	 * NORMAL first-time purchase (order -&gt; payment -&gt; webhook confirm)
	 * entirely through the real HTTP/service surface - the resulting {@code
	 * enrollment} row is genuinely, correctly activated.
	 */
	private Fixture seedConfirmedActivationFixture(String prefix) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain(prefix));
		TenantUser admin = seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser student = seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug(prefix), teacher.getId(), CourseStatus.PUBLIC));

		OrderResponse order = createOrderOrFail(host, studentToken, course.id());
		PaymentInitiationResponse initiation = initiatePaymentOrFail(host, studentToken, order.id());
		HttpResult<Void> webhook = sendPaymentWebhook(initiation.gatewayReference(), true);
		if (webhook.getStatusCode() != HttpStatus.OK) {
			throw new IllegalStateException("Webhook confirmation failed: " + webhook.getStatusCode());
		}

		UUID enrollmentId = jdbcTemplate.queryForObject(
				"SELECT id FROM enrollment WHERE tenant_id = ? AND student_id = ? AND course_id = ? "
						+ "AND superseded_at IS NULL",
				UUID.class, tenant.getId(), student.getId(), course.id());

		return new Fixture(tenant, enrollmentId, initiation.paymentId());
	}

}
