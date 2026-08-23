package com.lms.paymentmanagement.payment.service;

import com.lms.common.error.NotFoundException;
import com.lms.paymentmanagement.order.domain.StudentOrder;
import com.lms.paymentmanagement.order.repository.StudentOrderRepository;
import com.lms.paymentmanagement.payment.domain.Payment;
import com.lms.paymentmanagement.payment.repository.PaymentRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The two short, separately-committed transactions either side of the
 * (fake, but architecturally-treated-as-external) gateway call, per plan §9
 * item 2 / {@code .claude/rules/backend.md}'s "do not span a transaction
 * across an outbound call to an external system" rule. Deliberately a
 * separate bean from {@link PaymentInitiationService} (which is NOT itself
 * {@code @Transactional}) - calling these two methods from a different bean
 * is what makes each one open and commit its own transaction rather than
 * both joining one enclosing transaction that would then span the gateway
 * call in between.
 */
@Service
public class PaymentWriteService {

	private final PaymentRepository paymentRepository;

	private final StudentOrderRepository studentOrderRepository;

	public PaymentWriteService(PaymentRepository paymentRepository, StudentOrderRepository studentOrderRepository) {
		this.paymentRepository = paymentRepository;
		this.studentOrderRepository = studentOrderRepository;
	}

	@Transactional
	public Payment createPendingPayment(UUID orderId) {
		StudentOrder order = studentOrderRepository.findById(orderId)
			.orElseThrow(() -> new NotFoundException("Order not found"));
		Payment payment = new Payment(order.getTenantId(), order.getId(), order.getAmount(), order.getCurrency());
		payment = paymentRepository.save(payment);
		order.markPending();
		return payment;
	}

	@Transactional
	public void assignGatewayReference(UUID paymentId, String gatewayReference) {
		Payment payment = paymentRepository.findById(paymentId)
			.orElseThrow(() -> new NotFoundException("Payment not found"));
		payment.assignGatewayReference(gatewayReference);
		paymentRepository.save(payment);
	}

}
