package com.lms.paymentmanagement;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
	void enrollmentRepositoryDeleteAllByIdInBatchThrows() {
		assertThatThrownBy(() -> enrollmentRepository.deleteAllByIdInBatch(List.of(UUID.randomUUID())))
			.isInstanceOf(UnsupportedOperationException.class);
	}

}
