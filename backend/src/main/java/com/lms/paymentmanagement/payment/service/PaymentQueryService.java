package com.lms.paymentmanagement.payment.service;

import com.lms.common.error.NotFoundException;
import com.lms.paymentmanagement.order.domain.StudentOrder;
import com.lms.paymentmanagement.order.repository.StudentOrderRepository;
import com.lms.paymentmanagement.payment.domain.Payment;
import com.lms.paymentmanagement.payment.repository.PaymentRepository;
import com.lms.paymentmanagement.support.PaymentDomainAccessGuard;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backs {@code GET /api/v1/payments/{id}}. Owner-or-staff-VIEW access rule
 * (plan §10) - since {@code Payment} itself carries no {@code student_id},
 * ownership is resolved by loading the underlying {@code StudentOrder}
 * (guaranteed same-tenant via V19's composite FK) and checking its {@code
 * student_id}.
 */
@Service
@Transactional(readOnly = true)
public class PaymentQueryService {

	private final PaymentRepository paymentRepository;

	private final StudentOrderRepository studentOrderRepository;

	private final PaymentDomainAccessGuard accessGuard;

	public PaymentQueryService(PaymentRepository paymentRepository, StudentOrderRepository studentOrderRepository,
			PaymentDomainAccessGuard accessGuard) {
		this.paymentRepository = paymentRepository;
		this.studentOrderRepository = studentOrderRepository;
		this.accessGuard = accessGuard;
	}

	public PaymentView getPayment(UUID id) {
		Payment payment = loadPaymentForCaller(id);
		return toView(payment);
	}

	Payment loadPaymentForCaller(UUID id) {
		Payment payment = paymentRepository.findById(id).orElseThrow(() -> new NotFoundException("Payment not found"));
		StudentOrder order = studentOrderRepository.findById(payment.getOrderId())
			.orElseThrow(() -> new NotFoundException("Order not found for this payment"));
		accessGuard.requireOwnerOrStaffView(order.getStudentId());
		return payment;
	}

	/**
	 * Tenant-scoped, {@code PESSIMISTIC_WRITE}-locked load with NO
	 * owner-or-staff-VIEW check - for callers (namely {@code
	 * RefundService#processRefund}) that have already independently
	 * enforced a stricter, mutation-specific authorization check ({@code
	 * PAYMENTS_SLIPS}/{@code APPROVE}) and only need the tenant-isolation
	 * guarantee this repository read provides, plus the row lock for the
	 * duration of the caller's transaction - required by {@code
	 * RefundService#processRefund}'s check-then-insert refundable-remainder
	 * guard, which is otherwise vulnerable to a concurrent-refund race (two
	 * simultaneous refunds each individually under the remainder, but
	 * together exceeding it). Must be called from within an existing
	 * {@code @Transactional} method - the lock is released at that
	 * transaction's commit/rollback. Explicitly {@code readOnly = false},
	 * overriding this class's default - a read-only Hibernate session is
	 * free to skip/weaken lock acquisition, which would silently defeat the
	 * whole point of this method.
	 */
	@Transactional(readOnly = false)
	Payment loadPaymentForRefundUpdate(UUID id, UUID tenantId) {
		return paymentRepository.findByIdAndTenantIdForUpdate(id, tenantId)
			.orElseThrow(() -> new NotFoundException("Payment not found"));
	}

	static PaymentView toView(Payment payment) {
		return new PaymentView(payment.getId(), payment.getOrderId(), payment.getAmount(), payment.getCurrency(),
				payment.getStatus(), payment.getGatewayReference(), payment.getConfirmedAt(), payment.getCreatedAt());
	}

}
