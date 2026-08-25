package com.lms.paymentmanagement.slip.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lms.paymentmanagement.slip.domain.FlagType;
import com.lms.paymentmanagement.slip.domain.PaymentSlip;
import com.lms.paymentmanagement.slip.domain.PaymentSlipFlag;
import com.lms.paymentmanagement.slip.repository.PaymentSlipFlagRepository;
import com.lms.paymentmanagement.slip.repository.PaymentSlipRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Mockito-only unit coverage for {@link SlipDuplicateCheckService}
 * (MVP-011/SLIP-2), backing plan §18's "Flag-additive-never-cleared logic"
 * and "Duplicate exact-match comparison as pure logic" unit-test bullets: a
 * re-run detection result always produces an INSERT (a brand new {@code
 * PaymentSlipFlag}), never an update/delete, on the mocked flag repository -
 * the real cross-tenant/candidate-set-filtering half of this behavior is
 * covered end-to-end by {@code SlipDuplicateDetectionIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class SlipDuplicateCheckServiceTest {

	private static final UUID TENANT_ID = UUID.randomUUID();

	@Mock
	private PaymentSlipRepository paymentSlipRepository;

	@Mock
	private PaymentSlipFlagRepository paymentSlipFlagRepository;

	private SlipDuplicateCheckService slipDuplicateCheckService;

	@BeforeEach
	void setUp() {
		slipDuplicateCheckService = new SlipDuplicateCheckService(paymentSlipRepository, paymentSlipFlagRepository);
	}

	@Test
	void aSlipWithNoMatchesIsAdvancedToUnderReviewWithNoFlagInserted() {
		PaymentSlip slip = freshSlip("REF-1", "HASH-1");
		UUID slipId = slip.getId();
		when(paymentSlipRepository.findById(slipId)).thenReturn(Optional.of(slip));
		when(paymentSlipRepository.findAllByReferenceNumber("REF-1")).thenReturn(List.of(slip));
		when(paymentSlipRepository.findAllByImageHash("HASH-1")).thenReturn(List.of(slip));

		slipDuplicateCheckService.runChecksAndAdvance(slipId);

		verify(paymentSlipFlagRepository, never()).save(any());
		verify(paymentSlipRepository, times(1)).save(slip);
		assertThat(slip.getStatus().name()).isEqualTo("UNDER_REVIEW");
	}

	@Test
	void aSlipWithAMatchingReferenceNumberFromAnotherSlipInsertsExactlyOneDuplicateReferenceFlag() {
		PaymentSlip slip = freshSlip("REF-DUP", "HASH-UNIQUE-1");
		PaymentSlip other = freshSlip("REF-DUP", "HASH-UNIQUE-2");
		UUID slipId = slip.getId();
		when(paymentSlipRepository.findById(slipId)).thenReturn(Optional.of(slip));
		when(paymentSlipRepository.findAllByReferenceNumber("REF-DUP")).thenReturn(List.of(slip, other));
		when(paymentSlipRepository.findAllByImageHash("HASH-UNIQUE-1")).thenReturn(List.of(slip));

		slipDuplicateCheckService.runChecksAndAdvance(slipId);

		ArgumentCaptor<PaymentSlipFlag> captor = ArgumentCaptor.forClass(PaymentSlipFlag.class);
		verify(paymentSlipFlagRepository, times(1)).save(captor.capture());
		assertThat(captor.getValue().getFlagType()).isEqualTo(FlagType.DUPLICATE_REFERENCE);
		assertThat(captor.getValue().getSlipId()).isEqualTo(slipId);
	}

	@Test
	void aSlipWithAMatchingImageHashFromAnotherSlipInsertsExactlyOneDuplicateImageHashFlag() {
		PaymentSlip slip = freshSlip("REF-UNIQUE-1", "HASH-DUP");
		PaymentSlip other = freshSlip("REF-UNIQUE-2", "HASH-DUP");
		UUID slipId = slip.getId();
		when(paymentSlipRepository.findById(slipId)).thenReturn(Optional.of(slip));
		when(paymentSlipRepository.findAllByReferenceNumber("REF-UNIQUE-1")).thenReturn(List.of(slip));
		when(paymentSlipRepository.findAllByImageHash("HASH-DUP")).thenReturn(List.of(slip, other));

		slipDuplicateCheckService.runChecksAndAdvance(slipId);

		ArgumentCaptor<PaymentSlipFlag> captor = ArgumentCaptor.forClass(PaymentSlipFlag.class);
		verify(paymentSlipFlagRepository, times(1)).save(captor.capture());
		assertThat(captor.getValue().getFlagType()).isEqualTo(FlagType.DUPLICATE_IMAGE_HASH);
	}

	@Test
	void bothChecksMatchingInsertsTwoDistinctFlagsNeverAnUpdateOnAnExistingOne() {
		PaymentSlip slip = freshSlip("REF-DUP-BOTH", "HASH-DUP-BOTH");
		PaymentSlip otherByRef = freshSlip("REF-DUP-BOTH", "HASH-UNIQUE-3");
		PaymentSlip otherByHash = freshSlip("REF-UNIQUE-3", "HASH-DUP-BOTH");
		UUID slipId = slip.getId();
		when(paymentSlipRepository.findById(slipId)).thenReturn(Optional.of(slip));
		when(paymentSlipRepository.findAllByReferenceNumber("REF-DUP-BOTH")).thenReturn(List.of(slip, otherByRef));
		when(paymentSlipRepository.findAllByImageHash("HASH-DUP-BOTH")).thenReturn(List.of(slip, otherByHash));

		slipDuplicateCheckService.runChecksAndAdvance(slipId);

		// Every call is a fresh save/INSERT of a brand-new PaymentSlipFlag
		// instance - PaymentSlipFlagRepository exposes no update method at
		// all (see its own javadoc/AppendOnlyRepositoriesStructuralIntegrationTest),
		// so this is structurally guaranteed to be additive, not just
		// asserted here.
		verify(paymentSlipFlagRepository, times(2)).save(any(PaymentSlipFlag.class));
	}

	@Test
	void aSlipNoLongerSubmittedIsANoOpAndNeverReRunsChecksOrFlags() {
		// Idempotent re-entry guard (SlipDuplicateCheckService's own javadoc):
		// once a slip has left SUBMITTED, a repeated call to this same entry
		// point must not re-flag or re-advance it - there is no other public
		// "re-run detection" trigger in this codebase (see this module's test
		// report for the resulting gap against plan §18 item 4).
		PaymentSlip slip = freshSlip("REF-X", "HASH-X");
		slip.markUnderReview();
		UUID slipId = slip.getId();
		when(paymentSlipRepository.findById(slipId)).thenReturn(Optional.of(slip));

		slipDuplicateCheckService.runChecksAndAdvance(slipId);

		verify(paymentSlipRepository, never()).findAllByReferenceNumber(any());
		verify(paymentSlipRepository, never()).findAllByImageHash(any());
		verify(paymentSlipFlagRepository, never()).save(any());
		verify(paymentSlipRepository, never()).save(any());
	}

	private static PaymentSlip freshSlip(String referenceNumber, String imageHash) {
		PaymentSlip slip = new PaymentSlip(TENANT_ID, UUID.randomUUID(), UUID.randomUUID(), "storage-key",
				referenceNumber, imageHash);
		org.springframework.test.util.ReflectionTestUtils.setField(slip, "id", UUID.randomUUID());
		return slip;
	}

}
