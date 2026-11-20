package com.lms.paymentmanagement.payment.service;

import com.lms.paymentmanagement.api.ManualReceiptFact;
import com.lms.paymentmanagement.api.OrderCourseCharge;
import com.lms.paymentmanagement.api.OrderPostageDelivery;
import com.lms.paymentmanagement.api.PaymentReportApi;
import com.lms.paymentmanagement.manualpayment.domain.ManualPaymentReceipt;
import com.lms.paymentmanagement.manualpayment.repository.ManualPaymentReceiptRepository;
import com.lms.paymentmanagement.order.domain.StudentOrder;
import com.lms.paymentmanagement.order.domain.StudentOrderCourseCharge;
import com.lms.paymentmanagement.order.repository.StudentOrderCourseChargeRepository;
import com.lms.paymentmanagement.order.repository.StudentOrderRepository;
import com.lms.paymentmanagement.payment.domain.Payment;
import com.lms.paymentmanagement.payment.domain.PaymentStatus;
import com.lms.paymentmanagement.payment.repository.PaymentRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements {@link PaymentReportApi} (RPT-02). Read-only; every read goes
 * through this module's structurally tenant-scoped repositories (ADR-006) in
 * id chunks of {@link #CHUNK}. Nothing is written.
 */
@Service
@Transactional(readOnly = true)
public class PaymentReportQueryService implements PaymentReportApi {

	static final int CHUNK = 1000;

	private final StudentOrderRepository orderRepository;

	private final StudentOrderCourseChargeRepository chargeRepository;

	private final ManualPaymentReceiptRepository receiptRepository;

	private final PaymentRepository paymentRepository;

	public PaymentReportQueryService(StudentOrderRepository orderRepository,
			StudentOrderCourseChargeRepository chargeRepository, ManualPaymentReceiptRepository receiptRepository,
			PaymentRepository paymentRepository) {
		this.orderRepository = orderRepository;
		this.chargeRepository = chargeRepository;
		this.receiptRepository = receiptRepository;
		this.paymentRepository = paymentRepository;
	}

	@Override
	public Map<UUID, UUID> findOrderStudentIds(Collection<UUID> orderIds) {
		Map<UUID, UUID> result = new HashMap<>();
		for (List<UUID> chunk : chunks(orderIds)) {
			for (StudentOrder order : orderRepository.findAllById(chunk)) {
				result.put(order.getId(), order.getStudentId());
			}
		}
		return result;
	}

	@Override
	public List<OrderCourseCharge> findOrderCourseCharges(Collection<UUID> orderIds) {
		List<OrderCourseCharge> result = new ArrayList<>();
		for (List<UUID> chunk : chunks(orderIds)) {
			for (StudentOrderCourseCharge charge : chargeRepository.findAllByOrderIdIn(chunk)) {
				result.add(new OrderCourseCharge(charge.getOrderId(), charge.getCourseId(),
						charge.getFacilityFeeAmount(), charge.getPostageAmount()));
			}
		}
		return result;
	}

	@Override
	public List<ManualReceiptFact> findManualReceiptsByPaymentIds(Collection<UUID> paymentIds) {
		List<ManualReceiptFact> result = new ArrayList<>();
		for (List<UUID> chunk : chunks(paymentIds)) {
			for (ManualPaymentReceipt receipt : receiptRepository.findAllByPaymentIdIn(chunk)) {
				result.add(new ManualReceiptFact(receipt.getId(), receipt.getPaymentId(), receipt.getOrderId(),
						receipt.getPaymentMethod().name(), receipt.getReceivedOn(), receipt.getCurrency(),
						receipt.getExpectedAmount(), receipt.getStaffAdjustmentAmount(), receipt.getReceivedAmount(),
						receipt.getRecordedAt()));
			}
		}
		return result;
	}

	@Override
	public long countManualReceiptsReceivedBetween(LocalDate fromInclusive, LocalDate toInclusive) {
		if (fromInclusive == null || toInclusive == null || fromInclusive.isAfter(toInclusive)) {
			return 0;
		}
		return receiptRepository.countReceivedBetween(fromInclusive, toInclusive);
	}

	@Override
	public long countManualReceiptsReceivedBetweenForPaymentIds(LocalDate fromInclusive, LocalDate toInclusive,
			Collection<UUID> paymentIds) {
		if (fromInclusive == null || toInclusive == null || fromInclusive.isAfter(toInclusive) || paymentIds == null
				|| paymentIds.isEmpty()) {
			return 0;
		}
		long result = 0;
		for (List<UUID> chunk : chunks(paymentIds)) {
			result += receiptRepository.countReceivedBetweenForPaymentIds(fromInclusive, toInclusive, chunk);
		}
		return result;
	}

	@Override
	public List<UUID> findManualReceiptPaymentIdsReceivedBetween(LocalDate fromInclusive, LocalDate toInclusive) {
		if (fromInclusive == null || toInclusive == null || fromInclusive.isAfter(toInclusive)) {
			return List.of();
		}
		return receiptRepository.findPaymentIdsReceivedBetween(fromInclusive, toInclusive)
			.stream()
			.distinct()
			.toList();
	}

	@Override
	public List<OrderPostageDelivery> findOrderPostageDeliveries(Collection<UUID> orderIds) {
		List<OrderPostageDelivery> result = new ArrayList<>();
		for (List<UUID> chunk : chunks(orderIds)) {
			List<StudentOrderCourseCharge> charges = chargeRepository.findAllByOrderIdIn(chunk);
			if (charges.isEmpty()) {
				continue;
			}
			Map<UUID, StudentOrder.DeliverySnapshot> deliveries = new HashMap<>();
			for (StudentOrder order : orderRepository
				.findAllById(charges.stream().map(StudentOrderCourseCharge::getOrderId).distinct().toList())) {
				if (order.getDelivery() != null) {
					deliveries.put(order.getId(), order.getDelivery());
				}
			}
			for (StudentOrderCourseCharge charge : charges) {
				StudentOrder.DeliverySnapshot delivery = deliveries.get(charge.getOrderId());
				result.add(new OrderPostageDelivery(charge.getOrderId(), charge.getCourseId(),
						charge.isPostageSelected(), delivery == null ? null : delivery.recipientName(),
						delivery == null ? null : delivery.addressLine(), delivery == null ? null : delivery.city(),
						delivery == null ? null : delivery.phone()));
			}
		}
		return result;
	}

	@Override
	public Map<UUID, Instant> findConfirmedAtByPaymentIds(Collection<UUID> paymentIds) {
		Map<UUID, Instant> result = new HashMap<>();
		for (List<UUID> chunk : chunks(paymentIds)) {
			for (Payment payment : paymentRepository.findAllById(chunk)) {
				if (payment.getStatus() == PaymentStatus.CONFIRMED && payment.getConfirmedAt() != null) {
					result.put(payment.getId(), payment.getConfirmedAt());
				}
			}
		}
		return result;
	}

	private static List<List<UUID>> chunks(Collection<UUID> ids) {
		if (ids == null || ids.isEmpty()) {
			return List.of();
		}
		List<UUID> distinct = List.copyOf(new LinkedHashSet<>(ids));
		List<List<UUID>> result = new ArrayList<>();
		for (int from = 0; from < distinct.size(); from += CHUNK) {
			result.add(distinct.subList(from, Math.min(distinct.size(), from + CHUNK)));
		}
		return result;
	}

}
