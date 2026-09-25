package com.lms.paymentmanagement.slip.service;

import com.lms.paymentmanagement.api.OrderSlipDetail;
import com.lms.paymentmanagement.api.SlipStatusApi;
import com.lms.paymentmanagement.slip.domain.PaymentSlip;
import com.lms.paymentmanagement.slip.domain.PaymentSlipStatus;
import com.lms.paymentmanagement.slip.repository.PaymentSlipRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

	@Override
	public List<OrderSlipDetail> findOrderSlipDetails(List<UUID> orderIds) {
		if (orderIds.isEmpty()) {
			return List.of();
		}
		Map<UUID, List<PaymentSlip>> slipsByOrderId = new HashMap<>();
		for (PaymentSlip slip : paymentSlipRepository.findAllByOrderIdIn(orderIds)) {
			slipsByOrderId.computeIfAbsent(slip.getOrderId(), key -> new ArrayList<>()).add(slip);
		}

		List<OrderSlipDetail> results = new ArrayList<>();
		for (Map.Entry<UUID, List<PaymentSlip>> entry : slipsByOrderId.entrySet()) {
			boolean hasOpenSlip = false;
			boolean hasRejectedSlip = false;
			String approvedReferenceNumber = null;
			for (PaymentSlip slip : entry.getValue()) {
				switch (slip.getStatus()) {
					case SUBMITTED, UNDER_REVIEW -> hasOpenSlip = true;
					case REJECTED -> hasRejectedSlip = true;
					case APPROVED -> approvedReferenceNumber = slip.getReferenceNumber();
				}
			}
			results.add(new OrderSlipDetail(entry.getKey(), hasOpenSlip, hasRejectedSlip, approvedReferenceNumber));
		}
		return results;
	}

}
