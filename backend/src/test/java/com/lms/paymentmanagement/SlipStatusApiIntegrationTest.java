package com.lms.paymentmanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.paymentmanagement.api.SlipStatusApi;
import com.lms.paymentmanagement.slip.web.dto.PaymentSlipResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * Closes the L7 gap: {@link SlipStatusApiImpl} - the narrow contract {@code
 * enrollment-management}'s {@code EnrollmentActivationService} independently
 * re-verifies a slip's {@code APPROVED} status against before activating
 * enrollment - previously had no test of its own, only indirect coverage via
 * a mock (in {@code EnrollmentActivationServiceTest}) or implicitly through
 * the full HTTP approve flow (in {@code SlipApprovalActivationIntegrationTest}).
 *
 * <p>This proves the one property that actually matters for that defense-in-
 * depth call site: {@link SlipStatusApi#isApprovedForCurrentTenant(java.util.UUID)}
 * reads {@link com.lms.common.tenant.TenantContext}, not a caller-supplied
 * tenant id, so a cross-tenant call for the same slip id must never report
 * {@code true} - even though the slip is genuinely {@code APPROVED} under its
 * own tenant. Uses {@code AbstractIntegrationTest#withTenant} (mirroring
 * every other direct, non-HTTP {@code TenantContextHolder} test in this
 * suite, e.g. {@code TenantLookupServiceIntegrationTest}) to simulate calling
 * under a specific tenant context without going through the HTTP filter
 * chain.
 */
class SlipStatusApiIntegrationTest extends SlipTestSupport {

	@Autowired
	private SlipStatusApi slipStatusApi;

	@Test
	void isApprovedForCurrentTenantReturnsTrueForAGenuinelyApprovedSlipUnderItsOwnTenant() {
		SlipFixture fixture = seedTenantWithOrder("slip-status-api-own-tenant");
		PaymentSlipResponse slip = uploadSlipOrFail(fixture.host(), fixture.studentToken(), fixture.order().id(),
				"REF-STATUS-API-OWN", pdfFile("slip.pdf"));
		var approveResult = approveSlip(fixture.host(), fixture.financeToken(), slip.id(), null);
		assertThat(approveResult.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(approveResult.getBody().data().status().name()).isEqualTo("APPROVED");

		boolean approved = withTenant(fixture.tenant().getId(),
				() -> slipStatusApi.isApprovedForCurrentTenant(slip.id()));

		assertThat(approved).isTrue();
	}

	@Test
	void isApprovedForCurrentTenantReturnsFalseWhenCalledUnderADifferentTenantForTheSameSlipId() {
		SlipFixture tenantA = seedTenantWithOrder("slip-status-api-xt-a");
		SlipFixture tenantB = seedTenantWithOrder("slip-status-api-xt-b");
		PaymentSlipResponse slipA = uploadSlipOrFail(tenantA.host(), tenantA.studentToken(), tenantA.order().id(),
				"REF-STATUS-API-XT", pdfFile("slip.pdf"));
		var approveResult = approveSlip(tenantA.host(), tenantA.financeToken(), slipA.id(), null);
		assertThat(approveResult.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(approveResult.getBody().data().status().name()).isEqualTo("APPROVED");

		// Sanity: genuinely APPROVED under its own tenant.
		boolean approvedUnderOwnTenant = withTenant(tenantA.tenant().getId(),
				() -> slipStatusApi.isApprovedForCurrentTenant(slipA.id()));
		assertThat(approvedUnderOwnTenant).isTrue();

		// The actual property that matters: a cross-tenant call for the same
		// slip id must never report true, even though the slip really is
		// APPROVED - just not in tenant B's tenant context.
		boolean approvedUnderOtherTenant = withTenant(tenantB.tenant().getId(),
				() -> slipStatusApi.isApprovedForCurrentTenant(slipA.id()));

		assertThat(approvedUnderOtherTenant).isFalse();
	}

}
