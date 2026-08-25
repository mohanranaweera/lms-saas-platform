package com.lms.paymentmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lms.paymentmanagement.slip.web.dto.PaymentSlipResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Closes plan §18 Testcontainers items 11-12: {@code ck_payment_slip_status}
 * and {@code ck_payment_slip_flag_flag_type} (V21) are schema-enforced, not
 * just validated by the Java-layer enums - a raw, out-of-enum insert is
 * rejected by Postgres itself, bypassing the JPA/Hibernate/enum layer
 * entirely (mirroring {@code PaymentLedgerCheckConstraintIntegrationTest}'s
 * exact technique) - and {@code fk_enrollment_activating_slip} enforces
 * same-tenant linkage structurally, the same composite-FK mechanism already
 * proven for {@code activating_payment_id}.
 */
class SlipSchemaConstraintIntegrationTest extends SlipTestSupport {

	@Test
	void aRawInsertWithAnOutOfEnumPaymentSlipStatusIsRejectedByTheDatabase() {
		SlipFixture fixture = seedTenantWithOrder("slip-check-status");
		UUID bogusSlipId = UUID.randomUUID();

		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO payment_slip (id, tenant_id, order_id, student_id, storage_object_key, "
						+ "reference_number, image_hash, status, submitted_at, created_at, updated_at) "
						+ "VALUES (?, ?, ?, ?, 'bogus-key', 'REF-BOGUS', 'HASH-BOGUS', 'NOT_A_REAL_STATUS', now(), now(), now())",
				bogusSlipId, fixture.tenant().getId(), fixture.order().id(), fixture.student().getId()))
			.isInstanceOf(DataIntegrityViolationException.class);

		Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM payment_slip WHERE id = ?", Long.class,
				bogusSlipId);
		assertThat(count).isEqualTo(0L);
	}

	@Test
	void aRawInsertOfAnApprovedSlipWithNoReviewerIsRejectedByTheDatabase() {
		// ck_payment_slip_reviewed_requires_reviewer: a terminal review
		// decision must carry who/when, never a bare status flip.
		SlipFixture fixture = seedTenantWithOrder("slip-check-reviewer");
		UUID bogusSlipId = UUID.randomUUID();

		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO payment_slip (id, tenant_id, order_id, student_id, storage_object_key, "
						+ "reference_number, image_hash, status, submitted_at, created_at, updated_at) "
						+ "VALUES (?, ?, ?, ?, 'bogus-key', 'REF-BOGUS-2', 'HASH-BOGUS-2', 'APPROVED', now(), now(), now())",
				bogusSlipId, fixture.tenant().getId(), fixture.order().id(), fixture.student().getId()))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void aRawInsertWithAnOutOfEnumFlagTypeIsRejectedByTheDatabase() {
		SlipFixture fixture = seedTenantWithOrder("slip-check-flagtype");
		PaymentSlipResponse slip = uploadSlipOrFail(fixture.host(), fixture.studentToken(), fixture.order().id(),
				"REF-CHECK-FLAGTYPE", pdfFile("slip.pdf"));
		UUID bogusFlagId = UUID.randomUUID();

		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO payment_slip_flag (id, tenant_id, slip_id, flag_type, detected_at) "
						+ "VALUES (?, ?, ?, 'NOT_A_REAL_FLAG_TYPE', now())",
				bogusFlagId, fixture.tenant().getId(), slip.id())).isInstanceOf(DataIntegrityViolationException.class);

		Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM payment_slip_flag WHERE id = ?", Long.class,
				bogusFlagId);
		assertThat(count).isEqualTo(0L);
	}

	@Test
	void fkEnrollmentActivatingSlipRejectsACrossTenantLinkage() {
		SlipFixture tenantA = seedTenantWithOrder("slip-check-fk-a");
		SlipFixture tenantB = seedTenantWithOrder("slip-check-fk-b");
		PaymentSlipResponse slipA = uploadSlipOrFail(tenantA.host(), tenantA.studentToken(), tenantA.order().id(),
				"REF-CHECK-FK", pdfFile("slip.pdf"));
		// Approve slip A for real so it's a genuinely APPROVED row - the FK
		// under test is about tenant linkage, not slip status.
		approveSlipOrFail(tenantA, slipA.id());
		UUID bogusEnrollmentId = UUID.randomUUID();

		// Attempt to insert an enrollment row OWNED BY tenant B that points
		// its activating_slip_id at tenant A's (genuinely APPROVED) slip -
		// fk_enrollment_activating_slip is a composite (tenant_id,
		// activating_slip_id) FK against payment_slip's (tenant_id, id), so
		// this must be rejected even though the slip id itself is real.
		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO enrollment (id, tenant_id, student_id, course_id, activating_slip_id, status, "
						+ "activated_at) VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?)",
				bogusEnrollmentId, tenantB.tenant().getId(), tenantB.student().getId(), tenantB.course().id(),
				slipA.id(), Timestamp.from(Instant.now())))
			.isInstanceOf(DataIntegrityViolationException.class);

		Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM enrollment WHERE id = ?", Long.class,
				bogusEnrollmentId);
		assertThat(count).isEqualTo(0L);
	}

	private void approveSlipOrFail(SlipFixture fixture, UUID slipId) {
		var result = approveSlip(fixture.host(), fixture.financeToken(), slipId, null);
		if (result.getStatusCode() != org.springframework.http.HttpStatus.OK) {
			throw new IllegalStateException("Slip approval failed: " + result.getStatusCode());
		}
	}

}
