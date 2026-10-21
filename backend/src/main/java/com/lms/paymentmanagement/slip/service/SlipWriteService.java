package com.lms.paymentmanagement.slip.service;

import com.lms.paymentmanagement.checkout.service.CheckoutComposerService;
import com.lms.paymentmanagement.discount.service.DiscountRedemptionService;
import com.lms.paymentmanagement.order.domain.StudentOrder;
import com.lms.paymentmanagement.slip.domain.PaymentSlip;
import com.lms.paymentmanagement.slip.repository.PaymentSlipRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * KLASS-T8b (D24, payment review H-1): the one short transaction that inserts a
 * new {@code payment_slip} row. For a line order it first takes the shared
 * (tenant, student, coverage) advisory locks and re-checks that the order has
 * no other live payment evidence and that none of its items was meanwhile paid
 * or put on hold by another order ({@link
 * CheckoutComposerService#lockAndRequireNewLineOrderEvidenceAllowed}); for a
 * legacy order that check is a no-op. A separate bean from {@link
 * SlipUploadService} (which is deliberately not transactional, since it calls
 * the object store) so this method runs through the Spring proxy.
 */
@Service
public class SlipWriteService {

	private final PaymentSlipRepository paymentSlipRepository;

	private final CheckoutComposerService checkoutComposerService;

	public SlipWriteService(PaymentSlipRepository paymentSlipRepository,
			CheckoutComposerService checkoutComposerService) {
		this.paymentSlipRepository = paymentSlipRepository;
		this.checkoutComposerService = checkoutComposerService;
	}

	@Transactional
	public PaymentSlip saveNewSlip(StudentOrder order, PaymentSlip slip) {
		checkoutComposerService.lockAndRequireNewLineOrderEvidenceAllowed(order,
				DiscountRedemptionService.EvidenceKind.SLIP);
		return paymentSlipRepository.saveAndFlush(slip);
	}

}
