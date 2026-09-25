package com.lms.paymentmanagement.payment.service;

import com.lms.paymentmanagement.api.OrderPaymentDetail;
import com.lms.paymentmanagement.api.PaymentStatusApi;
import com.lms.paymentmanagement.order.domain.StudentOrder;
import com.lms.paymentmanagement.order.repository.StudentOrderRepository;
import com.lms.paymentmanagement.payment.domain.Payment;
import com.lms.paymentmanagement.payment.domain.PaymentStatus;
import com.lms.paymentmanagement.payment.repository.PaymentRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements {@link PaymentStatusApi} - see that interface's javadoc. Every
 * read here goes through {@link PaymentRepository#findById}/{@link
 * StudentOrderRepository}'s inherited tenant-scoped finders, exactly like
 * every other read in this module.
 */
@Service
@Transactional(readOnly = true)
public class PaymentStatusApiImpl implements PaymentStatusApi {

	private final PaymentRepository paymentRepository;

	private final StudentOrderRepository studentOrderRepository;

	public PaymentStatusApiImpl(PaymentRepository paymentRepository, StudentOrderRepository studentOrderRepository) {
		this.paymentRepository = paymentRepository;
		this.studentOrderRepository = studentOrderRepository;
	}

	@Override
	public boolean isConfirmedForCurrentTenant(UUID paymentId) {
		return paymentRepository.findById(paymentId).map(payment -> payment.getStatus() == PaymentStatus.CONFIRMED)
			.orElse(false);
	}

	@Override
	public List<UUID> findOrderIdsForStudent(UUID studentId) {
		return studentOrderRepository.findAllByStudentId(studentId).stream().map(StudentOrder::getId).toList();
	}

	@Override
	public List<OrderPaymentDetail> findOrderPaymentDetails(List<UUID> orderIds) {
		if (orderIds.isEmpty()) {
			return List.of();
		}
		List<StudentOrder> orders = studentOrderRepository.findAllById(orderIds);
		Map<UUID, List<Payment>> paymentsByOrderId = new HashMap<>();
		for (Payment payment : paymentRepository.findAllByOrderIdIn(orderIds)) {
			paymentsByOrderId.computeIfAbsent(payment.getOrderId(), key -> new ArrayList<>()).add(payment);
		}

		List<OrderPaymentDetail> results = new ArrayList<>();
		for (StudentOrder order : orders) {
			List<Payment> payments = paymentsByOrderId.getOrDefault(order.getId(), List.of());
			boolean hasPendingPayment = false;
			boolean hasRejectedPayment = false;
			UUID confirmedPaymentId = null;
			String confirmedGatewayReference = null;
			for (Payment payment : payments) {
				switch (payment.getStatus()) {
					case PENDING -> hasPendingPayment = true;
					case REJECTED -> hasRejectedPayment = true;
					case CONFIRMED -> {
						// PAY-2/PAY-3 invariant: at most one CONFIRMED
						// payment per order - never ambiguous which one to
						// report here.
						confirmedPaymentId = payment.getId();
						confirmedGatewayReference = payment.getGatewayReference();
					}
					case REFUNDED -> {
						// Never actually set by any code path - see
						// Payment's own javadoc; nothing to do here.
					}
				}
			}
			results.add(new OrderPaymentDetail(order.getId(), order.getStudentId(), order.getCourseId(),
					order.getBillingPeriodId(), order.getAmount(), order.getCurrency(), hasPendingPayment,
					hasRejectedPayment, confirmedPaymentId, confirmedGatewayReference));
		}
		return results;
	}

	@Override
	public List<UUID> findAllOrderIdsForCurrentTenant() {
		return studentOrderRepository.findAll().stream().map(StudentOrder::getId).toList();
	}

	@Override
	public List<UUID> findOrderIdsForCourse(UUID courseId) {
		return studentOrderRepository.findAllByCourseId(courseId).stream().map(StudentOrder::getId).toList();
	}

}
