package com.lms.paymentmanagement.slip.service;

import com.lms.common.error.NotFoundException;
import com.lms.paymentmanagement.slip.domain.FlagType;
import com.lms.paymentmanagement.slip.domain.PaymentSlip;
import com.lms.paymentmanagement.slip.domain.PaymentSlipFlag;
import com.lms.paymentmanagement.slip.domain.PaymentSlipStatus;
import com.lms.paymentmanagement.slip.repository.PaymentSlipFlagRepository;
import com.lms.paymentmanagement.slip.repository.PaymentSlipRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SLIP-2: exact-match-only duplicate detection (no OCR, no fuzzy matching -
 * explicitly Phase 3 per spec 25 §10). Both the reference-number and
 * image-hash checks run against candidate sets obtained through {@link
 * PaymentSlipRepository}'s tenant-scoped finders - never a hand-rolled
 * {@code WHERE} - so a same-reference/same-hash slip belonging to a
 * DIFFERENT tenant can never surface as a flag (the mandatory dual-direction
 * property, plan §14).
 *
 * <p>A flagged slip is auto-flagged only, never auto-rejected: {@link
 * #runChecksAndAdvance(UUID)} only ever inserts new {@code
 * payment_slip_flag} rows (additive, never an update/delete on an existing
 * flag) and advances the slip's own status to {@code UNDER_REVIEW} - it
 * never itself moves a slip to {@code REJECTED}.
 */
@Service
public class SlipDuplicateCheckService {

	private final PaymentSlipRepository paymentSlipRepository;

	private final PaymentSlipFlagRepository paymentSlipFlagRepository;

	public SlipDuplicateCheckService(PaymentSlipRepository paymentSlipRepository,
			PaymentSlipFlagRepository paymentSlipFlagRepository) {
		this.paymentSlipRepository = paymentSlipRepository;
		this.paymentSlipFlagRepository = paymentSlipFlagRepository;
	}

	/**
	 * Runs both duplicate checks for {@code slipId} and, if the slip is still
	 * {@code SUBMITTED}, advances it to {@code UNDER_REVIEW}. Idempotent: if
	 * the slip has already left {@code SUBMITTED} (e.g. a retried call after
	 * the first one already succeeded), this is a no-op - it never re-flags
	 * or re-advances a slip a second time from this entry point.
	 */
	@Transactional
	public void runChecksAndAdvance(UUID slipId) {
		PaymentSlip slip = paymentSlipRepository.findById(slipId)
			.orElseThrow(() -> new NotFoundException("Payment slip not found"));
		if (slip.getStatus() != PaymentSlipStatus.SUBMITTED) {
			return;
		}

		List<PaymentSlip> referenceMatches = paymentSlipRepository.findAllByReferenceNumber(slip.getReferenceNumber())
			.stream()
			.filter(other -> !other.getId().equals(slip.getId()))
			.toList();
		if (!referenceMatches.isEmpty()) {
			paymentSlipFlagRepository
				.save(new PaymentSlipFlag(slip.getTenantId(), slip.getId(), FlagType.DUPLICATE_REFERENCE));
		}

		List<PaymentSlip> hashMatches = paymentSlipRepository.findAllByImageHash(slip.getImageHash())
			.stream()
			.filter(other -> !other.getId().equals(slip.getId()))
			.toList();
		if (!hashMatches.isEmpty()) {
			paymentSlipFlagRepository
				.save(new PaymentSlipFlag(slip.getTenantId(), slip.getId(), FlagType.DUPLICATE_IMAGE_HASH));
		}

		slip.markUnderReview();
		paymentSlipRepository.save(slip);
	}

}
