package com.lms.paymentmanagement.payment.service;

import com.lms.common.error.NotFoundException;
import com.lms.paymentmanagement.order.domain.StudentOrder;
import com.lms.paymentmanagement.order.repository.StudentOrderRepository;
import com.lms.paymentmanagement.payment.domain.Payment;
import com.lms.paymentmanagement.payment.repository.PaymentRepository;
import java.util.Optional;
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
		return createPendingPayment(orderId, null).payment();
	}

	/**
	 * @param idempotencyKey optional client-supplied dedup key (V52, mirrors
	 * {@code RefundService#processRefund}'s established replay idiom). When
	 * present and a {@code payment} row already exists for {@code (tenantId,
	 * orderId, idempotencyKey)}, this is treated as a replay of that same
	 * "Pay Now" click - the existing row is returned unchanged ({@link
	 * PendingPaymentResult#replayed()} {@code true}) and no second {@code
	 * PENDING} payment/gateway call is made. {@code null} behaves exactly as
	 * {@link #createPendingPayment(UUID)} always has.
	 *
	 * <p>The genuinely-concurrent-duplicate-request race (two "Pay Now"
	 * clicks with the same key, both reaching the pre-insert replay check
	 * above at the same instant) is closed via {@link
	 * PaymentRepository#acquireIdempotencyLock} - a transaction-scoped
	 * Postgres advisory lock acquired BEFORE that replay check, serializing
	 * the two callers so the loser's own replay check runs only after the
	 * winner's insert has already committed, meaning the loser observes and
	 * returns the winner's row as a replay too - never a raw unique
	 * -constraint exception leaking to the caller as an unhandled 500. See
	 * that method's javadoc for why this - not a bare {@code
	 * catch(DataIntegrityViolationException)} around the insert - is this
	 * codebase's correct mechanism here.
	 */
	@Transactional
	public PendingPaymentResult createPendingPayment(UUID orderId, UUID idempotencyKey) {
		StudentOrder order = studentOrderRepository.findById(orderId)
			.orElseThrow(() -> new NotFoundException("Order not found"));

		if (idempotencyKey != null) {
			// See PaymentRepository#acquireIdempotencyLock / StudentOrderRepository
			// #acquireIdempotencyLock's javadoc for why this advisory lock -
			// not a bare catch(DataIntegrityViolationException) - is this
			// codebase's correct mechanism for a FRESH insert with no
			// pre-existing row to lock. Blocks until any other in-flight
			// request for this exact key commits or rolls back; auto
			// -released at this transaction's end.
			paymentRepository.acquireIdempotencyLock(order.getTenantId() + ":" + orderId + ":" + idempotencyKey);
			Optional<Payment> existing = paymentRepository.findByOrderIdAndIdempotencyKey(orderId, idempotencyKey);
			if (existing.isPresent()) {
				return new PendingPaymentResult(existing.get(), true);
			}
		}

		Payment payment = new Payment(order.getTenantId(), order.getId(), order.getAmount(), order.getCurrency(),
				idempotencyKey);
		payment = paymentRepository.save(payment);
		order.markPending();
		return new PendingPaymentResult(payment, false);
	}

	/**
	 * @param payment either the newly-created {@code PENDING} payment, or
	 * (when {@link #replayed} is {@code true}) the pre-existing row an
	 * idempotency-key replay resolved to - the caller must not re-run any
	 * gateway-initiation side effect in the latter case.
	 */
	public record PendingPaymentResult(Payment payment, boolean replayed) {
	}

	@Transactional
	public void assignGatewayReference(UUID paymentId, String gatewayReference) {
		Payment payment = paymentRepository.findById(paymentId)
			.orElseThrow(() -> new NotFoundException("Payment not found"));
		payment.assignGatewayReference(gatewayReference);
		paymentRepository.save(payment);
	}

}
