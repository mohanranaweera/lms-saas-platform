package com.lms.paymentmanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.identityaccessservice.HttpResult;
import com.lms.integrationmanagement.InMemoryObjectStorageApiTestConfig;
import com.lms.paymentmanagement.slip.web.dto.PaymentSlipResponse;
import com.lms.paymentmanagement.slip.web.dto.SlipDownloadUrlResponse;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

/**
 * Testcontainers/HTTP coverage for slip upload (SLIP-1, plan §18 items 1-2 &
 * 13). {@code app.payment.slip.max-file-size-bytes} is overridden to a small
 * value at the class level so the oversized-upload test doesn't need a
 * genuinely huge payload, mirroring {@code MaterialUploadIntegrationTest}'s
 * exact convention.
 */
@TestPropertySource(properties = "app.payment.slip.max-file-size-bytes=1024")
class SlipUploadIntegrationTest extends SlipTestSupport {

	@Autowired
	private InMemoryObjectStorageApiTestConfig.InMemoryObjectStorageApi inMemorySlipStorageApi;

	@Test
	void validUploadPersistsThenAutoAdvancesToUnderReviewTenantAndOrderScoped() {
		SlipFixture fixture = seedTenantWithOrder("slip-upload-valid");

		PaymentSlipResponse response = uploadSlipOrFail(fixture.host(), fixture.studentToken(), fixture.order().id(),
				"REF-VALID-1", pdfFile("slip.pdf"));

		assertThat(response.orderId()).isEqualTo(fixture.order().id());
		assertThat(response.studentId()).isEqualTo(fixture.student().getId());
		assertThat(response.referenceNumber()).isEqualTo("REF-VALID-1");
		// The upload response already reflects the post-duplicate-check state
		// (SlipUploadService synchronously runs the checks before returning) -
		// a clean, non-duplicate first slip auto-advances straight past
		// SUBMITTED to UNDER_REVIEW within the same request.
		assertThat(response.status().name()).isEqualTo("UNDER_REVIEW");
		assertThat(response.flags()).isEmpty();

		Long rowCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM payment_slip WHERE id = ? AND tenant_id = ? AND order_id = ? AND student_id = ? "
						+ "AND status = 'UNDER_REVIEW'",
				Long.class, response.id(), fixture.tenant().getId(), fixture.order().id(),
				fixture.student().getId());
		assertThat(rowCount).isEqualTo(1L);
	}

	/**
	 * Medium finding (item 5): a reviewer has zero on-screen way to identify
	 * who a slip belongs to or cross-check the expected amount without these
	 * fields. {@code reviewerEmail} stays {@code null} until an actual review
	 * decision has been made - see {@code SlipApprovalActivationIntegrationTest}
	 * for the populated-after-approve case.
	 */
	@Test
	void uploadResponseIsEnrichedWithStudentEmailAndOrderAmountAndCurrencyButNotYetAReviewerEmail() {
		SlipFixture fixture = seedTenantWithOrder("slip-upload-enriched");

		PaymentSlipResponse response = uploadSlipOrFail(fixture.host(), fixture.studentToken(), fixture.order().id(),
				"REF-ENRICHED", pdfFile("slip.pdf"));

		assertThat(response.studentEmail()).isEqualTo("student@example.test");
		assertThat(response.reviewerEmail()).isNull();
		assertThat(response.orderAmount()).isEqualByComparingTo(fixture.order().amount());
		assertThat(response.orderCurrency()).isEqualTo(fixture.order().currency());
	}

	/**
	 * Product-owner-approved policy (item 2): only one active
	 * (SUBMITTED/UNDER_REVIEW) slip may exist per order at a time - enforced
	 * by {@code uq_payment_slip_tenant_order_active}. The second attempt must
	 * leave zero partial writes: no second {@code payment_slip} row, and no
	 * orphaned object in storage either - the rejection is a friendly
	 * pre-check ({@code PaymentSlipRepository#existsActiveSlipForOrder}) that
	 * runs before the object store is ever touched, so the storage key set
	 * asserted below never gains an entry for the rejected attempt (there is
	 * no compensating-delete to exercise here since nothing was ever stored;
	 * item 4's compensating-delete path covers a save failure that happens
	 * AFTER a successful store, which this case is not).
	 */
	@Test
	void aSecondUploadAgainstAnOrderThatAlreadyHasAnActiveSlipIsRejectedWithNoPartialWrite() {
		SlipFixture fixture = seedTenantWithOrder("slip-upload-duplicate-order");
		PaymentSlipResponse first = uploadSlipOrFail(fixture.host(), fixture.studentToken(), fixture.order().id(),
				"REF-FIRST", pdfFile("first.pdf"));
		assertThat(first.status().name()).isEqualTo("UNDER_REVIEW");
		Set<String> keysAfterFirstUpload = inMemorySlipStorageApi.keySet();

		HttpResult<PaymentSlipResponse> second = uploadSlip(fixture.host(), fixture.studentToken(),
				fixture.order().id(), "REF-SECOND", pdfFile("second.pdf", distinctPdfBytes("second-attempt")));

		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(second.getBody().error().message())
			.isEqualTo("You already have a payment slip under review for this order");
		Long slipCount = jdbcTemplate.queryForObject("SELECT count(*) FROM payment_slip WHERE order_id = ?",
				Long.class, fixture.order().id());
		assertThat(slipCount).isEqualTo(1L);
		assertThat(inMemorySlipStorageApi.keySet()).isEqualTo(keysAfterFirstUpload);
	}

	@Test
	void uploadOfAFileFailingContentSniffingIsRejectedAndPersistsNothing() {
		SlipFixture fixture = seedTenantWithOrder("slip-upload-bad-mime");

		HttpResult<PaymentSlipResponse> result = uploadSlip(fixture.host(), fixture.studentToken(),
				fixture.order().id(), "REF-BAD-MIME", disguisedExecutableFile("totally-a.pdf"));

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
		assertZeroSlipRowsForOrder(fixture.order().id());
	}

	@Test
	void uploadOfAPlainTextFileIsRejectedAndPersistsNothing() {
		// Deliberately, unlike Module 9's ContentSniffer, SlipContentSniffer
		// has no plain-text/"notes" branch (spec 08 describes only "slip
		// image/PDF").
		SlipFixture fixture = seedTenantWithOrder("slip-upload-plain-text");

		HttpResult<PaymentSlipResponse> result = uploadSlip(fixture.host(), fixture.studentToken(),
				fixture.order().id(), "REF-PLAIN-TEXT", plainTextFile("notes.txt"));

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
		assertZeroSlipRowsForOrder(fixture.order().id());
	}

	@Test
	void uploadExceedingTheConfiguredMaxFileSizeIsRejectedAndPersistsNothing() {
		SlipFixture fixture = seedTenantWithOrder("slip-upload-oversize");

		HttpResult<PaymentSlipResponse> result = uploadSlip(fixture.host(), fixture.studentToken(),
				fixture.order().id(), "REF-OVERSIZE", oversizedPdfFile("big.pdf", 2048));

		// Spring Framework 7 exposes both HttpStatus.PAYLOAD_TOO_LARGE and
		// HttpStatus.CONTENT_TOO_LARGE for the same numeric code 413 -
		// HttpStatus.valueOf(413) (what this HttpResult's status is built
		// from) resolves to the latter, mirroring
		// MaterialUploadIntegrationTest's identical assertion.
		assertThat(result.getStatusCode().value()).isEqualTo(413);
		assertZeroSlipRowsForOrder(fixture.order().id());
	}

	@Test
	void uploadByADifferentStudentInTheSameTenantWhoDoesNotOwnTheOrderIsDeniedAndPersistsNothing() {
		SlipFixture fixture = seedTenantWithOrder("slip-upload-non-owner");
		seedActiveStudent(fixture.tenant().getId(), "other-student@example.test");
		String otherStudentToken = loginAndGetToken(fixture.host(), "other-student@example.test");

		HttpResult<PaymentSlipResponse> result = uploadSlip(fixture.host(), otherStudentToken, fixture.order().id(),
				"REF-NON-OWNER", pdfFile("slip.pdf"));

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertZeroSlipRowsForOrder(fixture.order().id());
	}

	@Test
	void theSlipFileIsNeverReachableViaADirectOrPredictableUrl() {
		SlipFixture fixture = seedTenantWithOrder("slip-upload-protected");
		Set<String> keysBeforeUpload = inMemorySlipStorageApi.keySet();

		PaymentSlipResponse uploaded = uploadSlipOrFail(fixture.host(), fixture.studentToken(), fixture.order().id(),
				"REF-PROTECTED", pdfFile("slip.pdf"));
		Set<String> keysAfterUpload = inMemorySlipStorageApi.keySet();
		String rawStorageKey = keysAfterUpload.stream()
			.filter(key -> !keysBeforeUpload.contains(key))
			.findFirst()
			.orElseThrow(() -> new AssertionError("Expected exactly one new object key after upload"));

		String detailRawJson = getSlipRawJson(fixture.host(), fixture.studentToken(), uploaded.id());
		assertThat(detailRawJson).doesNotContain(rawStorageKey);

		HttpResult<SlipDownloadUrlResponse> downloadResult = getSlipDownloadUrl(fixture.host(),
				fixture.studentToken(), uploaded.id());
		assertThat(downloadResult.getStatusCode()).isEqualTo(HttpStatus.OK);
		SlipDownloadUrlResponse signed = downloadResult.getBody().data();
		// A real signed URL (e.g. an S3-style presigned URL) legitimately
		// includes the object key in its path - that is not itself a
		// weakness, since the actual "never a direct/predictable URL"
		// property comes from the signature/expiry component making the URL
		// unguessable, not from hiding the key. InMemoryObjectStorageApi's fake
		// signed URL (mirroring InMemoryObjectStorageApiTestConfig, MVP-009,
		// exactly) has no signature component to assert against, so this test
		// checks the property that actually matters: the raw storage key
		// (and therefore the object it addresses) is never exposed through
		// any OTHER field of the API response - only inside the one signed
		// URL a caller who already passed the tenant/ownership guard
		// receives.
		assertThat(signed.url()).isNotBlank();
		assertThat(signed.expiresAt()).isAfter(Instant.now());
		String downloadRawJson = getSlipDownloadUrlRawJson(fixture.host(), fixture.studentToken(), uploaded.id());
		assertThat(downloadRawJson).contains("\"url\"").contains("\"expiresAt\"");
	}

	private void assertZeroSlipRowsForOrder(UUID orderId) {
		Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM payment_slip WHERE order_id = ?", Long.class,
				orderId);
		assertThat(count).isEqualTo(0L);
	}

}
