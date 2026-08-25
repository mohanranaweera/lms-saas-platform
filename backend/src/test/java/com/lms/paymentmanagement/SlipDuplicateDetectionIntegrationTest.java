package com.lms.paymentmanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.paymentmanagement.slip.web.dto.PaymentSlipResponse;
import org.junit.jupiter.api.Test;

/**
 * Testcontainers/HTTP coverage for SLIP-2's exact-match duplicate detection
 * (plan §18 items 3 & 5). Test {@code
 * sameReferenceNumberWithinOneTenantIsFlagged}/{@code
 * sameReferenceNumberAcrossTwoDifferentTenantsIsNotFlagged} (and their
 * image-hash counterparts) together close the "mandatory dual-direction
 * duplicate-detection test" plan §18/§21 calls "the single most important
 * test in this module" - a missing tenant filter here would be a cross-tenant
 * information leak (confirming another tenant's slip/reference exists), not
 * merely a false-positive bug.
 *
 * <p><b>Gap, not silently worked around:</b> plan §18 item 4 ("re-running
 * duplicate checks adds a new flag row, never clears/overwrites a prior one")
 * has no integration-level test here. {@code SlipDuplicateCheckService}'s
 * only public entry point, {@code runChecksAndAdvance}, is a documented,
 * enforced no-op once a slip has left {@code SUBMITTED} (see its own
 * javadoc) - there is no re-check/re-run trigger anywhere in this
 * implementation (no admin "re-scan" endpoint, no scheduled job) that could
 * genuinely exercise "run the checks a second time against an already-flagged
 * slip" over real HTTP/Postgres. Inventing an artificial re-entry path (e.g.
 * resetting a slip's status back to {@code SUBMITTED} via raw SQL and calling
 * the service bean directly) would test a state transition that cannot
 * happen in production, which this task's own instructions say not to do.
 * The equivalent property - a re-run always INSERTs, never UPDATEs/DELETEs,
 * on the flag repository - is covered at the unit level instead, in {@code
 * SlipDuplicateCheckServiceTest}.
 */
class SlipDuplicateDetectionIntegrationTest extends SlipTestSupport {

	@Test
	void sameReferenceNumberWithinOneTenantIsFlaggedOnTheSecondSlipOnlyAndNeverAutoRejected() {
		SlipFixture fixture = seedTenantWithOrder("slip-dup-ref-a");
		var secondOrder = createAnotherOrder(fixture, "slip-dup-ref-a-2");

		// Deliberately DISTINCT image bytes for the two uploads - this isolates
		// the reference-number duplicate check from the image-hash duplicate
		// check. pdfFile(filename) (single-arg) always returns the same fixed
		// bytes regardless of filename, so reusing it for both uploads here
		// would spuriously also trigger DUPLICATE_IMAGE_HASH and break this
		// test's single-flag-type assertion below.
		PaymentSlipResponse first = uploadSlipOrFail(fixture.host(), fixture.studentToken(), fixture.order().id(),
				"SHARED-REF-1", pdfFile("slip-1.pdf", distinctPdfBytes("slip-dup-ref-a-first")));
		PaymentSlipResponse second = uploadSlipOrFail(fixture.host(), fixture.studentToken(), secondOrder.id(),
				"SHARED-REF-1", pdfFile("slip-2.pdf", distinctPdfBytes("slip-dup-ref-a-second")));

		assertThat(first.flags()).isEmpty();
		assertThat(second.flags()).hasSize(1);
		assertThat(second.flags().get(0).flagType().name()).isEqualTo("DUPLICATE_REFERENCE");
		// A flagged slip is auto-flagged only - never auto-rejected: it still
		// reaches UNDER_REVIEW, exactly like a clean slip.
		assertThat(second.status().name()).isEqualTo("UNDER_REVIEW");

		Long flagCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM payment_slip_flag WHERE slip_id = ? AND flag_type = 'DUPLICATE_REFERENCE'",
				Long.class, second.id());
		assertThat(flagCount).isEqualTo(1L);
	}

	@Test
	void sameReferenceNumberAcrossTwoDifferentTenantsIsNotFlagged() {
		SlipFixture tenantA = seedTenantWithOrder("slip-dup-ref-xt-a");
		SlipFixture tenantB = seedTenantWithOrder("slip-dup-ref-xt-b");

		uploadSlipOrFail(tenantA.host(), tenantA.studentToken(), tenantA.order().id(), "CROSS-TENANT-SHARED-REF",
				pdfFile("slip-a.pdf"));
		PaymentSlipResponse slipB = uploadSlipOrFail(tenantB.host(), tenantB.studentToken(), tenantB.order().id(),
				"CROSS-TENANT-SHARED-REF", pdfFile("slip-b.pdf"));

		assertThat(slipB.flags()).isEmpty();
		Long flagCountForB = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM payment_slip_flag WHERE slip_id = ?", Long.class, slipB.id());
		assertThat(flagCountForB).isEqualTo(0L);
	}

	@Test
	void sameImageHashWithinOneTenantIsFlaggedOnTheSecondSlipOnly() {
		SlipFixture fixture = seedTenantWithOrder("slip-dup-hash-a");
		var secondOrder = createAnotherOrder(fixture, "slip-dup-hash-a-2");
		byte[] identicalBytes = validPdfBytes();

		PaymentSlipResponse first = uploadSlipOrFail(fixture.host(), fixture.studentToken(), fixture.order().id(),
				"REF-HASH-1", pdfFile("slip-1.pdf", identicalBytes));
		PaymentSlipResponse second = uploadSlipOrFail(fixture.host(), fixture.studentToken(), secondOrder.id(),
				"REF-HASH-2", pdfFile("slip-2.pdf", identicalBytes));

		assertThat(first.flags()).isEmpty();
		assertThat(second.flags()).hasSize(1);
		assertThat(second.flags().get(0).flagType().name()).isEqualTo("DUPLICATE_IMAGE_HASH");
	}

	@Test
	void sameImageHashAcrossTwoDifferentTenantsIsNotFlagged() {
		SlipFixture tenantA = seedTenantWithOrder("slip-dup-hash-xt-a");
		SlipFixture tenantB = seedTenantWithOrder("slip-dup-hash-xt-b");
		byte[] identicalBytes = validPdfBytes();

		uploadSlipOrFail(tenantA.host(), tenantA.studentToken(), tenantA.order().id(), "REF-A",
				pdfFile("slip-a.pdf", identicalBytes));
		PaymentSlipResponse slipB = uploadSlipOrFail(tenantB.host(), tenantB.studentToken(), tenantB.order().id(),
				"REF-B", pdfFile("slip-b.pdf", identicalBytes));

		assertThat(slipB.flags()).isEmpty();
	}

}
