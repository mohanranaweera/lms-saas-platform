package com.lms.paymentmanagement.payment.service;

import com.lms.common.error.NotFoundException;
import com.lms.paymentmanagement.order.domain.StudentOrder;
import com.lms.paymentmanagement.order.repository.StudentOrderRepository;
import com.lms.paymentmanagement.order.repository.StudentOrderLineRepository;
import com.lms.paymentmanagement.payment.domain.Payment;
import com.lms.paymentmanagement.payment.repository.PaymentRepository;
import com.lms.paymentmanagement.refund.repository.PaymentRefundRepository;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.coursemanagement.api.CourseSummary;
import com.lms.paymentmanagement.api.OrderLineSnapshot;
import com.lms.paymentmanagement.api.PaymentStatusApi;
import com.lms.paymentmanagement.support.PaymentDomainAccessGuard;
import java.util.UUID;
import java.util.List;
import java.util.Map;
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

	private final PaymentRefundRepository paymentRefundRepository;
	private final StudentOrderLineRepository orderLineRepository;
	private final CourseLookupApi courseLookupApi;

	public PaymentQueryService(PaymentRepository paymentRepository, StudentOrderRepository studentOrderRepository,
			PaymentDomainAccessGuard accessGuard, PaymentRefundRepository paymentRefundRepository,
			StudentOrderLineRepository orderLineRepository, CourseLookupApi courseLookupApi) {
		this.paymentRepository = paymentRepository;
		this.studentOrderRepository = studentOrderRepository;
		this.accessGuard = accessGuard;
		this.paymentRefundRepository = paymentRefundRepository;
		this.orderLineRepository = orderLineRepository;
		this.courseLookupApi = courseLookupApi;
	}

	public PaymentView getPayment(UUID id) {
		Payment payment = loadPaymentForCaller(id);
		return toView(payment);
	}

	public RefundVerificationView getRefundVerification(UUID id) {
		Payment payment = loadPaymentForCaller(id);
		var refunds = paymentRefundRepository.findAllByOriginalPaymentId(id);
		java.math.BigDecimal refunded = refunds.stream().map(com.lms.paymentmanagement.refund.domain.PaymentRefund::getAmount)
			.reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
		StudentOrder order = studentOrderRepository.findById(payment.getOrderId())
				.orElseThrow(() -> new NotFoundException("Order not found for this payment"));
		List<OrderLineSnapshot> lines = orderLineRepository.findAllByOrderId(order.getId()).stream()
				.map(line -> PaymentStatusApiImpl.toSnapshot(line, order.getStudentId())).toList();
		Map<UUID, String> courseNames = courseLookupApi.getCourseSummaries(lines.stream().map(OrderLineSnapshot::courseId).collect(java.util.stream.Collectors.toSet()))
				.stream().collect(java.util.stream.Collectors.toMap(CourseSummary::id, CourseSummary::name, (a,b) -> a));
		List<RefundVerificationView.Line> lineViews = lines.stream().map(line -> new RefundVerificationView.Line(line.lineNo(), line.courseId(), courseNames.getOrDefault(line.courseId(), "Unknown course"), line.label(), line.scope(), line.unitAmount(), line.facilityFee(), line.discountAmount(), line.lineTotal(), line.currency())).toList();
		return new RefundVerificationView(payment.getId(), payment.getOrderId(), order.getOrderNumber(), order.getDisplayOrderNumber(), payment.getAmount(), payment.getCurrency(),
				payment.getStatus().name(), payment.getGatewayReference(), payment.getCreatedAt(), refunded,
				payment.getAmount().subtract(refunded).max(java.math.BigDecimal.ZERO), lineViews);
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
