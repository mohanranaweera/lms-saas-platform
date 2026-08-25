package com.lms.paymentmanagement.slip.service;

import com.lms.paymentmanagement.api.SlipStatusApi;
import com.lms.paymentmanagement.slip.domain.PaymentSlipStatus;
import com.lms.paymentmanagement.slip.repository.PaymentSlipRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements {@link SlipStatusApi} - see that interface's javadoc. The read
 * goes through {@link PaymentSlipRepository#findById}'s inherited
 * tenant-scoped finder, exactly like every other read in this module.
 */
@Service
@Transactional(readOnly = true)
public class SlipStatusApiImpl implements SlipStatusApi {

	private final PaymentSlipRepository paymentSlipRepository;

	public SlipStatusApiImpl(PaymentSlipRepository paymentSlipRepository) {
		this.paymentSlipRepository = paymentSlipRepository;
	}

	@Override
	public boolean isApprovedForCurrentTenant(UUID slipId) {
		return paymentSlipRepository.findById(slipId)
			.map(slip -> slip.getStatus() == PaymentSlipStatus.APPROVED)
			.orElse(false);
	}

}
