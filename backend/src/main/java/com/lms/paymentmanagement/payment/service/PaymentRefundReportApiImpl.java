package com.lms.paymentmanagement.payment.service;

import com.lms.paymentmanagement.api.PaymentRefundReportApi;
import com.lms.paymentmanagement.api.PaymentRefundReportApi.RefundFact;
import com.lms.paymentmanagement.order.domain.StudentOrder;
import com.lms.paymentmanagement.order.repository.StudentOrderRepository;
import com.lms.paymentmanagement.payment.domain.Payment;
import com.lms.paymentmanagement.payment.repository.PaymentRepository;
import com.lms.paymentmanagement.refund.domain.PaymentRefund;
import com.lms.paymentmanagement.refund.repository.PaymentRefundRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tenant-scoped reporting adapter over immutable refund/payment/order facts. */
@Service
@Transactional(readOnly = true)
public class PaymentRefundReportApiImpl implements PaymentRefundReportApi {

	private final PaymentRefundRepository refundRepository;
	private final PaymentRepository paymentRepository;
	private final StudentOrderRepository orderRepository;

	public PaymentRefundReportApiImpl(PaymentRefundRepository refundRepository, PaymentRepository paymentRepository,
			StudentOrderRepository orderRepository) {
		this.refundRepository = refundRepository;
		this.paymentRepository = paymentRepository;
		this.orderRepository = orderRepository;
	}

	@Override
	public List<RefundFact> findEffectiveRefundsBetween(Instant fromInclusive, Instant toExclusive, int maxRows) {
		List<PaymentRefund> refunds = refundRepository.findAll((root, query, cb) -> cb.and(
				cb.greaterThanOrEqualTo(root.get("createdAt"), fromInclusive),
				cb.lessThan(root.get("createdAt"), toExclusive)),
				org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt")
					.and(org.springframework.data.domain.Sort.by("id")));
		if (refunds.size() > maxRows) {
			throw new IllegalArgumentException("Undo payment report scope exceeds " + maxRows + " refunds");
		}
		List<UUID> paymentIds = refunds.stream().map(PaymentRefund::getOriginalPaymentId).distinct().toList();
		Map<UUID, Payment> payments = paymentRepository.findAllById(paymentIds).stream()
				.collect(Collectors.toMap(Payment::getId, Function.identity()));
		List<UUID> orderIds = payments.values().stream().map(Payment::getOrderId).distinct().toList();
		Map<UUID, StudentOrder> orders = orderRepository.findAllById(orderIds).stream()
				.collect(Collectors.toMap(StudentOrder::getId, Function.identity()));
		List<RefundFact> facts = new ArrayList<>();
		for (PaymentRefund refund : refunds) {
			Payment payment = payments.get(refund.getOriginalPaymentId());
			if (payment == null) continue;
			StudentOrder order = orders.get(payment.getOrderId());
			if (order == null) continue;
			facts.add(new RefundFact(refund.getId(), payment.getId(), order.getId(), order.getStudentId(), order.getCourseId(),
					refund.getAmount().abs(), refund.getReason(), refund.getSource(), refund.getCreatedAt(),
					payment.getCreatedAt()));
		}
		return facts;
	}
}
