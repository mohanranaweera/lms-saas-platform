package com.lms.paymentmanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.common.api.PageResponse;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.paymentmanagement.slip.domain.PaymentSlipStatus;
import com.lms.paymentmanagement.slip.web.dto.PaymentSlipResponse;
import com.lms.paymentmanagement.slip.web.dto.SlipDownloadUrlResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Closes plan §18's "Mandatory cross-tenant negative tests" list for every
 * slip surface (upload, detail read, download-url, review-queue list,
 * approve, reject, override-approve), plus the anti-enumeration
 * (same-tenant-different-student) and role-boundary (Student
 * Support/Read-only Auditor/Student) negative tests plan §15/§18 both call
 * out explicitly. Every mutating case proves zero side effects on rejection,
 * mirroring {@code PaymentCrossTenantIntegrationTest}'s exact assertion
 * style.
 */
class SlipCrossTenantIntegrationTest extends SlipTestSupport {

	@Test
	void uploadAgainstAnotherTenantsOrderIdReturns404AndCreatesNoRows() {
		SlipFixture tenantA = seedTenantWithOrder("slip-xt-upload-a");
		SlipFixture tenantB = seedTenantWithOrder("slip-xt-upload-b");

		HttpResult<PaymentSlipResponse> result = uploadSlip(tenantB.host(), tenantB.studentToken(),
				tenantA.order().id(), "REF-XT-UPLOAD", pdfFile("slip.pdf"));

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM payment_slip WHERE order_id = ?", Long.class,
				tenantA.order().id());
		assertThat(count).isEqualTo(0L);
	}

	@Test
	void slipDetailReadCrossTenantReturns404NeverTenantAsData() {
		SlipFixture tenantA = seedTenantWithOrder("slip-xt-detail-a");
		SlipFixture tenantB = seedTenantWithOrder("slip-xt-detail-b");
		PaymentSlipResponse slipA = uploadSlipOrFail(tenantA.host(), tenantA.studentToken(), tenantA.order().id(),
				"REF-XT-DETAIL", pdfFile("slip.pdf"));

		HttpResult<PaymentSlipResponse> result = getSlip(tenantB.host(), tenantB.studentToken(), slipA.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void downloadUrlCrossTenantReturns404() {
		SlipFixture tenantA = seedTenantWithOrder("slip-xt-download-a");
		SlipFixture tenantB = seedTenantWithOrder("slip-xt-download-b");
		PaymentSlipResponse slipA = uploadSlipOrFail(tenantA.host(), tenantA.studentToken(), tenantA.order().id(),
				"REF-XT-DOWNLOAD", pdfFile("slip.pdf"));

		HttpResult<SlipDownloadUrlResponse> result = getSlipDownloadUrl(tenantB.host(), tenantB.studentToken(),
				slipA.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	/**
	 * Closes the M5 gap: a staff caller takes a different code path through
	 * {@code PaymentDomainAccessGuard#requireOwnerOrStaffView} (the
	 * permission-check branch) than a Student caller does (the ownership
	 * branch) - see that class's javadoc. This proves the tenant-scoped
	 * repository lookup keeps
	 * a cross-tenant id safe for a Finance Staff caller too, even though they
	 * hold {@code PAYMENTS_SLIPS}/{@code VIEW} in their own tenant.
	 */
	@Test
	void financeStaffCallerSlipDetailAndDownloadUrlReadsCrossTenantReturn404() {
		SlipFixture tenantA = seedTenantWithOrder("slip-xt-staff-a");
		SlipFixture tenantB = seedTenantWithOrder("slip-xt-staff-b");
		PaymentSlipResponse slipA = uploadSlipOrFail(tenantA.host(), tenantA.studentToken(), tenantA.order().id(),
				"REF-XT-STAFF", pdfFile("slip.pdf"));

		HttpResult<PaymentSlipResponse> detailResult = getSlip(tenantB.host(), tenantB.financeToken(), slipA.id());
		assertThat(detailResult.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

		HttpResult<SlipDownloadUrlResponse> downloadResult = getSlipDownloadUrl(tenantB.host(), tenantB.financeToken(),
				slipA.id());
		assertThat(downloadResult.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void reviewQueueNeverLeaksAnotherTenantsSlips() {
		SlipFixture tenantA = seedTenantWithOrder("slip-xt-queue-a");
		SlipFixture tenantB = seedTenantWithOrder("slip-xt-queue-b");
		uploadSlipOrFail(tenantA.host(), tenantA.studentToken(), tenantA.order().id(), "REF-XT-QUEUE",
				pdfFile("slip.pdf"));

		HttpResult<PageResponse<PaymentSlipResponse>> queueB = getReviewQueue(tenantB.host(), tenantB.financeToken(),
				null);

		assertThat(queueB.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(queueB.getBody().data().content()).isEmpty();

		// Sanity: tenant A's own queue DOES see its own slip, proving the
		// emptiness above is tenant isolation, not a broken queue.
		HttpResult<PageResponse<PaymentSlipResponse>> queueA = getReviewQueue(tenantA.host(), tenantA.financeToken(),
				null);
		assertThat(queueA.getBody().data().content()).isNotEmpty();
	}

	/**
	 * Closes the L5 gap: {@code reviewQueueNeverLeaksAnotherTenantsSlips}
	 * above only exercises {@code PaymentSlipRepository#findReviewQueue}'s
	 * default ({@code status == null}) branch. The status-supplied branch is
	 * a materially different query ({@code cb.equal(...)} instead of {@code
	 * .in(SUBMITTED, UNDER_REVIEW)}) and needs its own isolation proof. The
	 * uploaded slip is genuinely {@code UNDER_REVIEW} immediately after
	 * upload (see {@code SlipApprovalActivationIntegrationTest}), so no extra
	 * state transition is needed to make this a real positive-match query
	 * for tenant A's own queue.
	 */
	@Test
	void reviewQueueWithAnExplicitStatusFilterNeverLeaksAnotherTenantsSlips() {
		SlipFixture tenantA = seedTenantWithOrder("slip-xt-queue-status-a");
		SlipFixture tenantB = seedTenantWithOrder("slip-xt-queue-status-b");
		uploadSlipOrFail(tenantA.host(), tenantA.studentToken(), tenantA.order().id(), "REF-XT-QUEUE-STATUS",
				pdfFile("slip.pdf"));

		HttpResult<PageResponse<PaymentSlipResponse>> queueB = getReviewQueue(tenantB.host(), tenantB.financeToken(),
				PaymentSlipStatus.UNDER_REVIEW);

		assertThat(queueB.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(queueB.getBody().data().content()).isEmpty();

		// Sanity: tenant A's own status-filtered queue DOES see its own
		// UNDER_REVIEW slip, proving the emptiness above is tenant
		// isolation, not a query that never matches anything.
		HttpResult<PageResponse<PaymentSlipResponse>> queueA = getReviewQueue(tenantA.host(), tenantA.financeToken(),
				PaymentSlipStatus.UNDER_REVIEW);
		assertThat(queueA.getBody().data().content()).isNotEmpty();
	}

	@Test
	void approveCrossTenantReturns404WithZeroSideEffects() {
		SlipFixture tenantA = seedTenantWithOrder("slip-xt-approve-a");
		SlipFixture tenantB = seedTenantWithOrder("slip-xt-approve-b");
		PaymentSlipResponse slipA = uploadSlipOrFail(tenantA.host(), tenantA.studentToken(), tenantA.order().id(),
				"REF-XT-APPROVE", pdfFile("slip.pdf"));

		HttpResult<PaymentSlipResponse> result = approveSlip(tenantB.host(), tenantB.financeToken(), slipA.id(),
				null);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		String status = jdbcTemplate.queryForObject("SELECT status FROM payment_slip WHERE id = ?", String.class,
				slipA.id());
		assertThat(status).isEqualTo("UNDER_REVIEW");
		Long enrollmentCount = jdbcTemplate.queryForObject("SELECT count(*) FROM enrollment WHERE tenant_id = ?",
				Long.class, tenantB.tenant().getId());
		assertThat(enrollmentCount).isEqualTo(0L);
	}

	@Test
	void rejectCrossTenantReturns404WithZeroSideEffects() {
		SlipFixture tenantA = seedTenantWithOrder("slip-xt-reject-a");
		SlipFixture tenantB = seedTenantWithOrder("slip-xt-reject-b");
		PaymentSlipResponse slipA = uploadSlipOrFail(tenantA.host(), tenantA.studentToken(), tenantA.order().id(),
				"REF-XT-REJECT", pdfFile("slip.pdf"));

		HttpResult<PaymentSlipResponse> result = rejectSlip(tenantB.host(), tenantB.financeToken(), slipA.id(),
				"Attempted cross-tenant rejection");

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		String status = jdbcTemplate.queryForObject("SELECT status FROM payment_slip WHERE id = ?", String.class,
				slipA.id());
		assertThat(status).isEqualTo("UNDER_REVIEW");
	}

	@Test
	void overrideApproveCrossTenantReturns404WithZeroSideEffectsEvenWithAReasonSupplied() {
		SlipFixture tenantA = seedTenantWithOrder("slip-xt-override-a");
		SlipFixture tenantB = seedTenantWithOrder("slip-xt-override-b");
		PaymentSlipResponse slipA = uploadSlipOrFail(tenantA.host(), tenantA.studentToken(), tenantA.order().id(),
				"REF-XT-OVERRIDE", pdfFile("slip.pdf"));

		HttpResult<PaymentSlipResponse> result = approveSlip(tenantB.host(), tenantB.financeToken(), slipA.id(),
				"A plausible-looking override reason");

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		Long auditCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM audit_log WHERE target_entity = 'payment_slip' AND target_id = ?", Long.class,
				slipA.id());
		assertThat(auditCount).isEqualTo(0L);
	}

	/**
	 * Anti-enumeration (plan §15, mirroring {@code MaterialAccessGuard}'s
	 * established convention): a Student requesting another student's slip -
	 * even within the same tenant - must receive 404, never 403, so they can
	 * never distinguish "exists but isn't mine" from "doesn't exist".
	 */
	@Test
	void sameTenantDifferentStudentSlipAccessByAStudentReturns404NotForbidden() {
		SlipFixture fixture = seedTenantWithOrder("slip-same-tenant-diff-student");
		seedActiveStudent(fixture.tenant().getId(), "other-student@example.test");
		String otherStudentToken = loginAndGetToken(fixture.host(), "other-student@example.test");
		PaymentSlipResponse slip = uploadSlipOrFail(fixture.host(), fixture.studentToken(), fixture.order().id(),
				"REF-ANTI-ENUM", pdfFile("slip.pdf"));

		HttpResult<PaymentSlipResponse> detailResult = getSlip(fixture.host(), otherStudentToken, slip.id());
		assertThat(detailResult.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

		HttpResult<SlipDownloadUrlResponse> downloadResult = getSlipDownloadUrl(fixture.host(), otherStudentToken,
				slip.id());
		assertThat(downloadResult.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void studentSupportAndReadOnlyAuditorAreDeniedOnApproveAndReject() {
		SlipFixture fixture = seedTenantWithOrder("slip-role-boundary");
		seedTenantUser(fixture.tenant().getId(), "support@example.test", RAW_PASSWORD, Role.STUDENT_SUPPORT);
		seedTenantUser(fixture.tenant().getId(), "auditor@example.test", RAW_PASSWORD, Role.READ_ONLY_AUDITOR);
		String supportToken = loginAndGetToken(fixture.host(), "support@example.test");
		String auditorToken = loginAndGetToken(fixture.host(), "auditor@example.test");
		PaymentSlipResponse slip = uploadSlipOrFail(fixture.host(), fixture.studentToken(), fixture.order().id(),
				"REF-ROLE-BOUNDARY", pdfFile("slip.pdf"));

		assertThat(approveSlip(fixture.host(), supportToken, slip.id(), null).getStatusCode())
			.isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(rejectSlip(fixture.host(), supportToken, slip.id(), "reason").getStatusCode())
			.isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(approveSlip(fixture.host(), auditorToken, slip.id(), null).getStatusCode())
			.isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(rejectSlip(fixture.host(), auditorToken, slip.id(), "reason").getStatusCode())
			.isEqualTo(HttpStatus.FORBIDDEN);

		String status = jdbcTemplate.queryForObject("SELECT status FROM payment_slip WHERE id = ?", String.class,
				slip.id());
		assertThat(status).isEqualTo("UNDER_REVIEW");
	}

	/** Student Support DOES hold {@code PAYMENTS_SLIPS}/{@code VIEW} - the review queue read itself must succeed. */
	@Test
	void studentSupportCanReadTheReviewQueueButNeverMutate() {
		SlipFixture fixture = seedTenantWithOrder("slip-support-view");
		seedTenantUser(fixture.tenant().getId(), "support@example.test", RAW_PASSWORD, Role.STUDENT_SUPPORT);
		String supportToken = loginAndGetToken(fixture.host(), "support@example.test");
		uploadSlipOrFail(fixture.host(), fixture.studentToken(), fixture.order().id(), "REF-SUPPORT-VIEW",
				pdfFile("slip.pdf"));

		HttpResult<PageResponse<PaymentSlipResponse>> queue = getReviewQueue(fixture.host(), supportToken,
				PaymentSlipStatus.UNDER_REVIEW);

		assertThat(queue.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(queue.getBody().data().content()).isNotEmpty();
	}

	@Test
	void aStudentCallingApproveRejectOrTheReviewQueueDirectlyIsDenied() {
		SlipFixture fixture = seedTenantWithOrder("slip-student-denied");
		PaymentSlipResponse slip = uploadSlipOrFail(fixture.host(), fixture.studentToken(), fixture.order().id(),
				"REF-STUDENT-DENIED", pdfFile("slip.pdf"));

		assertThat(approveSlip(fixture.host(), fixture.studentToken(), slip.id(), null).getStatusCode())
			.isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(rejectSlip(fixture.host(), fixture.studentToken(), slip.id(), "reason").getStatusCode())
			.isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(getReviewQueue(fixture.host(), fixture.studentToken(), null).getStatusCode())
			.isEqualTo(HttpStatus.FORBIDDEN);

		String status = jdbcTemplate.queryForObject("SELECT status FROM payment_slip WHERE id = ?", String.class,
				slip.id());
		assertThat(status).isEqualTo("UNDER_REVIEW");
	}

}
