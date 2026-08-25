package com.lms.paymentmanagement;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lms.auditlogmanagement.domain.AuditLog;
import com.lms.auditlogmanagement.repository.AuditLogRepository;
import com.lms.common.AbstractIntegrationTest;
import com.lms.enrollmentmanagement.domain.Enrollment;
import com.lms.enrollmentmanagement.repository.EnrollmentRepository;
import com.lms.ledgersettlementmanagement.domain.LedgerEntry;
import com.lms.ledgersettlementmanagement.repository.LedgerEntryRepository;
import com.lms.paymentmanagement.order.domain.StudentOrder;
import com.lms.paymentmanagement.order.repository.StudentOrderRepository;
import com.lms.paymentmanagement.payment.domain.Payment;
import com.lms.paymentmanagement.payment.repository.PaymentRepository;
import com.lms.paymentmanagement.refund.domain.PaymentRefund;
import com.lms.paymentmanagement.refund.repository.PaymentRefundRepository;
import com.lms.paymentmanagement.slip.domain.PaymentSlip;
import com.lms.paymentmanagement.slip.domain.PaymentSlipFlag;
import com.lms.paymentmanagement.slip.repository.PaymentSlipFlagRepository;
import com.lms.paymentmanagement.slip.repository.PaymentSlipRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Structural coverage (plan §18 test 4): {@code StudentOrderRepository}/
 * {@code PaymentRepository}/{@code LedgerEntryRepository}/{@code
 * PaymentRefundRepository}/{@code EnrollmentRepository} expose no working
 * delete method - every one of the eight Spring-Data-JPA-exposed
 * delete-shaped methods throws {@link UnsupportedOperationException},
 * mirroring {@code CoursePriceHistoryRepository}'s established pattern and
 * its own implicit structural test (this module's is explicit). No seeded
 * data is needed - every override throws unconditionally, before ever
 * touching the database.
 *
 * <p>MVP-011 additions (plan §18 Testcontainers item 10): {@code
 * PaymentSlipRepository} (financial history - status transitions in place,
 * but no row may ever be deleted), {@code PaymentSlipFlagRepository} (fully
 * append-only), and {@code AuditLogRepository} (fully append-only, per
 * {@code .claude/rules/security.md}'s "Audit logs are themselves ...
 * append-only" rule) all follow the exact same pattern.
 *
 * <p>L2: each repository block also asserts the older, {@code @Deprecated}
 * {@code JpaRepository.deleteInBatch(Iterable&lt;T&gt;)} overload throws too -
 * distinct from {@code deleteAllInBatch(Iterable&lt;T&gt;)} above, even though
 * it delegates to it by JDK bytecode (confirmed via {@code javap}). This is a
 * coverage-completeness item, not evidence of a live bug: it proves the
 * deprecated overload is actually exercised - not merely assumed-safe by
 * delegation - for every append-only repository in this file.
 */
class AppendOnlyRepositoriesStructuralIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private StudentOrderRepository studentOrderRepository;

	@Autowired
	private PaymentRepository paymentRepository;

	@Autowired
	private LedgerEntryRepository ledgerEntryRepository;

	@Autowired
	private PaymentRefundRepository paymentRefundRepository;

	@Autowired
	private EnrollmentRepository enrollmentRepository;

	@Autowired
	private PaymentSlipRepository paymentSlipRepository;

	@Autowired
	private PaymentSlipFlagRepository paymentSlipFlagRepository;

	@Autowired
	private AuditLogRepository auditLogRepository;

	// ------------------------------------------------------------------
	// StudentOrderRepository.
	// ------------------------------------------------------------------

	@Test
	void studentOrderRepositoryDeleteByIdThrows() {
		assertThatThrownBy(() -> studentOrderRepository.deleteById(UUID.randomUUID()))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void studentOrderRepositoryDeleteThrows() {
		assertThatThrownBy(() -> studentOrderRepository.delete((StudentOrder) null))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void studentOrderRepositoryDeleteAllByIdThrows() {
		assertThatThrownBy(() -> studentOrderRepository.deleteAllById(List.of(UUID.randomUUID())))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void studentOrderRepositoryDeleteAllWithEntitiesThrows() {
		assertThatThrownBy(() -> studentOrderRepository.deleteAll(List.of()))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void studentOrderRepositoryDeleteAllThrows() {
		assertThatThrownBy(() -> studentOrderRepository.deleteAll()).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void studentOrderRepositoryDeleteAllInBatchThrows() {
		assertThatThrownBy(() -> studentOrderRepository.deleteAllInBatch())
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void studentOrderRepositoryDeleteAllInBatchWithEntitiesThrows() {
		assertThatThrownBy(() -> studentOrderRepository.deleteAllInBatch(List.of()))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void studentOrderRepositoryDeleteInBatchDeprecatedOverloadThrows() {
		assertThatThrownBy(() -> studentOrderRepository.deleteInBatch(List.of()))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void studentOrderRepositoryDeleteAllByIdInBatchThrows() {
		assertThatThrownBy(() -> studentOrderRepository.deleteAllByIdInBatch(List.of(UUID.randomUUID())))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	// ------------------------------------------------------------------
	// PaymentRepository.
	// ------------------------------------------------------------------

	@Test
	void paymentRepositoryDeleteByIdThrows() {
		assertThatThrownBy(() -> paymentRepository.deleteById(UUID.randomUUID()))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void paymentRepositoryDeleteThrows() {
		// null is fine as the argument here: Payment's constructor is
		// protected (not accessible from this test's package), and every
		// override throws unconditionally before ever touching the
		// argument.
		assertThatThrownBy(() -> paymentRepository.delete((Payment) null))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void paymentRepositoryDeleteAllByIdThrows() {
		assertThatThrownBy(() -> paymentRepository.deleteAllById(List.of(UUID.randomUUID())))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void paymentRepositoryDeleteAllWithEntitiesThrows() {
		assertThatThrownBy(() -> paymentRepository.deleteAll(List.of())).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void paymentRepositoryDeleteAllThrows() {
		assertThatThrownBy(() -> paymentRepository.deleteAll()).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void paymentRepositoryDeleteAllInBatchThrows() {
		assertThatThrownBy(() -> paymentRepository.deleteAllInBatch()).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void paymentRepositoryDeleteAllInBatchWithEntitiesThrows() {
		assertThatThrownBy(() -> paymentRepository.deleteAllInBatch(List.of()))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void paymentRepositoryDeleteInBatchDeprecatedOverloadThrows() {
		assertThatThrownBy(() -> paymentRepository.deleteInBatch(List.of()))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void paymentRepositoryDeleteAllByIdInBatchThrows() {
		assertThatThrownBy(() -> paymentRepository.deleteAllByIdInBatch(List.of(UUID.randomUUID())))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	// ------------------------------------------------------------------
	// LedgerEntryRepository.
	// ------------------------------------------------------------------

	@Test
	void ledgerEntryRepositoryDeleteByIdThrows() {
		assertThatThrownBy(() -> ledgerEntryRepository.deleteById(UUID.randomUUID()))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void ledgerEntryRepositoryDeleteThrows() {
		assertThatThrownBy(() -> ledgerEntryRepository.delete((LedgerEntry) null))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void ledgerEntryRepositoryDeleteAllByIdThrows() {
		assertThatThrownBy(() -> ledgerEntryRepository.deleteAllById(List.of(UUID.randomUUID())))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void ledgerEntryRepositoryDeleteAllWithEntitiesThrows() {
		assertThatThrownBy(() -> ledgerEntryRepository.deleteAll(List.of()))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void ledgerEntryRepositoryDeleteAllThrows() {
		assertThatThrownBy(() -> ledgerEntryRepository.deleteAll()).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void ledgerEntryRepositoryDeleteAllInBatchThrows() {
		assertThatThrownBy(() -> ledgerEntryRepository.deleteAllInBatch())
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void ledgerEntryRepositoryDeleteAllInBatchWithEntitiesThrows() {
		assertThatThrownBy(() -> ledgerEntryRepository.deleteAllInBatch(List.of()))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void ledgerEntryRepositoryDeleteInBatchDeprecatedOverloadThrows() {
		assertThatThrownBy(() -> ledgerEntryRepository.deleteInBatch(List.of()))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void ledgerEntryRepositoryDeleteAllByIdInBatchThrows() {
		assertThatThrownBy(() -> ledgerEntryRepository.deleteAllByIdInBatch(List.of(UUID.randomUUID())))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	// ------------------------------------------------------------------
	// PaymentRefundRepository.
	// ------------------------------------------------------------------

	@Test
	void paymentRefundRepositoryDeleteByIdThrows() {
		assertThatThrownBy(() -> paymentRefundRepository.deleteById(UUID.randomUUID()))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void paymentRefundRepositoryDeleteThrows() {
		assertThatThrownBy(() -> paymentRefundRepository.delete((PaymentRefund) null))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void paymentRefundRepositoryDeleteAllByIdThrows() {
		assertThatThrownBy(() -> paymentRefundRepository.deleteAllById(List.of(UUID.randomUUID())))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void paymentRefundRepositoryDeleteAllWithEntitiesThrows() {
		assertThatThrownBy(() -> paymentRefundRepository.deleteAll(List.of()))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void paymentRefundRepositoryDeleteAllThrows() {
		assertThatThrownBy(() -> paymentRefundRepository.deleteAll()).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void paymentRefundRepositoryDeleteAllInBatchThrows() {
		assertThatThrownBy(() -> paymentRefundRepository.deleteAllInBatch())
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void paymentRefundRepositoryDeleteAllInBatchWithEntitiesThrows() {
		assertThatThrownBy(() -> paymentRefundRepository.deleteAllInBatch(List.of()))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void paymentRefundRepositoryDeleteInBatchDeprecatedOverloadThrows() {
		assertThatThrownBy(() -> paymentRefundRepository.deleteInBatch(List.of()))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void paymentRefundRepositoryDeleteAllByIdInBatchThrows() {
		assertThatThrownBy(() -> paymentRefundRepository.deleteAllByIdInBatch(List.of(UUID.randomUUID())))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	// ------------------------------------------------------------------
	// EnrollmentRepository.
	// ------------------------------------------------------------------

	@Test
	void enrollmentRepositoryDeleteByIdThrows() {
		assertThatThrownBy(() -> enrollmentRepository.deleteById(UUID.randomUUID()))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void enrollmentRepositoryDeleteThrows() {
		assertThatThrownBy(() -> enrollmentRepository.delete((Enrollment) null))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void enrollmentRepositoryDeleteAllByIdThrows() {
		assertThatThrownBy(() -> enrollmentRepository.deleteAllById(List.of(UUID.randomUUID())))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void enrollmentRepositoryDeleteAllWithEntitiesThrows() {
		assertThatThrownBy(() -> enrollmentRepository.deleteAll(List.of()))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void enrollmentRepositoryDeleteAllThrows() {
		assertThatThrownBy(() -> enrollmentRepository.deleteAll()).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void enrollmentRepositoryDeleteAllInBatchThrows() {
		assertThatThrownBy(() -> enrollmentRepository.deleteAllInBatch())
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void enrollmentRepositoryDeleteAllInBatchWithEntitiesThrows() {
		assertThatThrownBy(() -> enrollmentRepository.deleteAllInBatch(List.of()))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void enrollmentRepositoryDeleteInBatchDeprecatedOverloadThrows() {
		assertThatThrownBy(() -> enrollmentRepository.deleteInBatch(List.of()))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void enrollmentRepositoryDeleteAllByIdInBatchThrows() {
		assertThatThrownBy(() -> enrollmentRepository.deleteAllByIdInBatch(List.of(UUID.randomUUID())))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	// ------------------------------------------------------------------
	// PaymentSlipRepository (MVP-011).
	// ------------------------------------------------------------------

	@Test
	void paymentSlipRepositoryDeleteByIdThrows() {
		assertThatThrownBy(() -> paymentSlipRepository.deleteById(UUID.randomUUID()))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void paymentSlipRepositoryDeleteThrows() {
		assertThatThrownBy(() -> paymentSlipRepository.delete((PaymentSlip) null))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void paymentSlipRepositoryDeleteAllByIdThrows() {
		assertThatThrownBy(() -> paymentSlipRepository.deleteAllById(List.of(UUID.randomUUID())))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void paymentSlipRepositoryDeleteAllWithEntitiesThrows() {
		assertThatThrownBy(() -> paymentSlipRepository.deleteAll(List.of()))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void paymentSlipRepositoryDeleteAllThrows() {
		assertThatThrownBy(() -> paymentSlipRepository.deleteAll()).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void paymentSlipRepositoryDeleteAllInBatchThrows() {
		assertThatThrownBy(() -> paymentSlipRepository.deleteAllInBatch())
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void paymentSlipRepositoryDeleteAllInBatchWithEntitiesThrows() {
		assertThatThrownBy(() -> paymentSlipRepository.deleteAllInBatch(List.of()))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void paymentSlipRepositoryDeleteInBatchDeprecatedOverloadThrows() {
		assertThatThrownBy(() -> paymentSlipRepository.deleteInBatch(List.of()))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void paymentSlipRepositoryDeleteAllByIdInBatchThrows() {
		assertThatThrownBy(() -> paymentSlipRepository.deleteAllByIdInBatch(List.of(UUID.randomUUID())))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	// ------------------------------------------------------------------
	// PaymentSlipFlagRepository (MVP-011).
	// ------------------------------------------------------------------

	@Test
	void paymentSlipFlagRepositoryDeleteByIdThrows() {
		assertThatThrownBy(() -> paymentSlipFlagRepository.deleteById(UUID.randomUUID()))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void paymentSlipFlagRepositoryDeleteThrows() {
		assertThatThrownBy(() -> paymentSlipFlagRepository.delete((PaymentSlipFlag) null))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void paymentSlipFlagRepositoryDeleteAllByIdThrows() {
		assertThatThrownBy(() -> paymentSlipFlagRepository.deleteAllById(List.of(UUID.randomUUID())))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void paymentSlipFlagRepositoryDeleteAllWithEntitiesThrows() {
		assertThatThrownBy(() -> paymentSlipFlagRepository.deleteAll(List.of()))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void paymentSlipFlagRepositoryDeleteAllThrows() {
		assertThatThrownBy(() -> paymentSlipFlagRepository.deleteAll())
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void paymentSlipFlagRepositoryDeleteAllInBatchThrows() {
		assertThatThrownBy(() -> paymentSlipFlagRepository.deleteAllInBatch())
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void paymentSlipFlagRepositoryDeleteAllInBatchWithEntitiesThrows() {
		assertThatThrownBy(() -> paymentSlipFlagRepository.deleteAllInBatch(List.of()))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void paymentSlipFlagRepositoryDeleteInBatchDeprecatedOverloadThrows() {
		assertThatThrownBy(() -> paymentSlipFlagRepository.deleteInBatch(List.of()))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void paymentSlipFlagRepositoryDeleteAllByIdInBatchThrows() {
		assertThatThrownBy(() -> paymentSlipFlagRepository.deleteAllByIdInBatch(List.of(UUID.randomUUID())))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	// ------------------------------------------------------------------
	// AuditLogRepository (MVP-011).
	// ------------------------------------------------------------------

	@Test
	void auditLogRepositoryDeleteByIdThrows() {
		assertThatThrownBy(() -> auditLogRepository.deleteById(UUID.randomUUID()))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void auditLogRepositoryDeleteThrows() {
		assertThatThrownBy(() -> auditLogRepository.delete((AuditLog) null))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void auditLogRepositoryDeleteAllByIdThrows() {
		assertThatThrownBy(() -> auditLogRepository.deleteAllById(List.of(UUID.randomUUID())))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void auditLogRepositoryDeleteAllWithEntitiesThrows() {
		assertThatThrownBy(() -> auditLogRepository.deleteAll(List.of()))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void auditLogRepositoryDeleteAllThrows() {
		assertThatThrownBy(() -> auditLogRepository.deleteAll()).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void auditLogRepositoryDeleteAllInBatchThrows() {
		assertThatThrownBy(() -> auditLogRepository.deleteAllInBatch())
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void auditLogRepositoryDeleteAllInBatchWithEntitiesThrows() {
		assertThatThrownBy(() -> auditLogRepository.deleteAllInBatch(List.of()))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void auditLogRepositoryDeleteInBatchDeprecatedOverloadThrows() {
		assertThatThrownBy(() -> auditLogRepository.deleteInBatch(List.of()))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void auditLogRepositoryDeleteAllByIdInBatchThrows() {
		assertThatThrownBy(() -> auditLogRepository.deleteAllByIdInBatch(List.of(UUID.randomUUID())))
			.isInstanceOf(UnsupportedOperationException.class);
	}

}
