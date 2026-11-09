package com.lms.tutemanagement.service;

import com.lms.common.tenant.TenantContextHolder;
import com.lms.paymentmanagement.api.PaymentConfirmedEvent;
import com.lms.paymentmanagement.api.PaymentRefundedEvent;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * TUTE-01 section 5 triggers (ADR-032 exam precedent): after a payment transaction COMMITS,
 * assign matching tutes (confirmation) or put the payment's copies on hold (staff refund).
 * Tenant identity is the event's own {@code tenantId} (set on the thread for the call, the
 * previous value restored afterwards), never client input. Failures are logged and never
 * propagate into the already-committed payment flow; "Sync payments" can always re-run the
 * matcher. Chargebacks publish no refund event - Sync detects them (refund facts).
 */
@Component
public class TutePaymentEventListener {

	private static final Logger log = LoggerFactory.getLogger(TutePaymentEventListener.class);

	private final TutePaymentAssignmentService assignmentService;

	public TutePaymentEventListener(TutePaymentAssignmentService assignmentService) {
		this.assignmentService = assignmentService;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onPaymentConfirmed(PaymentConfirmedEvent event) {
		if (event.tenantId() == null || event.paymentId() == null || event.orderId() == null
				|| !event.isConfirmed()) {
			return;
		}
		runInTenant(event.tenantId(),
				() -> assignmentService.assignForConfirmedPayment(event.paymentId(), event.orderId()),
				"tute.auto_assign.failed", event.paymentId());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onPaymentRefunded(PaymentRefundedEvent event) {
		if (event.tenantId() == null || event.paymentId() == null) {
			return;
		}
		runInTenant(event.tenantId(), () -> {
			int flagged = assignmentService.holdForReversedPayment(event.paymentId(), event.actorUserId(),
					event.refundId());
			log.atInfo()
				.setMessage("tute.auto_assign.refund_processed")
				.addKeyValue("tuteTenantId", event.tenantId())
				.addKeyValue("paymentId", event.paymentId())
				.addKeyValue("held", flagged)
				.log();
			return flagged;
		}, "tute.auto_assign.refund_failed", event.paymentId());
	}

	private void runInTenant(UUID tenantId, Supplier<Integer> work, String failureMessage, UUID paymentId) {
		UUID previous = TenantContextHolder.isSet() ? new TenantContextHolder().getTenantId() : null;
		try {
			TenantContextHolder.set(tenantId);
			work.get();
		}
		catch (RuntimeException ex) {
			log.atError()
				.setMessage(failureMessage)
				.addKeyValue("tuteTenantId", tenantId)
				.addKeyValue("paymentId", paymentId)
				.setCause(ex)
				.log();
		}
		finally {
			if (previous == null) {
				TenantContextHolder.clear();
			}
			else {
				TenantContextHolder.set(previous);
			}
		}
	}

}
