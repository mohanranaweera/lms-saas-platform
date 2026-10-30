package com.lms.exammanagement.service.exam;

import com.lms.common.tenant.TenantContextHolder;
import com.lms.exammanagement.domain.ExamEnrollment;
import com.lms.paymentmanagement.api.PaymentConfirmedEvent;
import com.lms.paymentmanagement.api.PaymentRefundedEvent;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * ADR-032 §3/§6 triggers: after a payment transaction COMMITS, auto-enroll (confirmation) or
 * remove PAYMENT enrollments without an attempt (refund / chargeback). Tenant identity is the
 * event's own {@code tenantId} (set on the thread for the duration of the call, the previous
 * value restored afterwards), never client input. Failures are logged and never propagate into
 * the already-committed payment flow; "Sync payments" can always re-run the matcher.
 */
@Component
public class ExamPaymentEventListener {

	private static final Logger log = LoggerFactory.getLogger(ExamPaymentEventListener.class);

	private final ExamPaymentEnrollmentService paymentEnrollmentService;

	public ExamPaymentEventListener(ExamPaymentEnrollmentService paymentEnrollmentService) {
		this.paymentEnrollmentService = paymentEnrollmentService;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onPaymentConfirmed(PaymentConfirmedEvent event) {
		if (event.tenantId() == null || event.paymentId() == null || event.orderId() == null
				|| !event.isConfirmed()) {
			return;
		}
		runInTenant(event.tenantId(), () -> paymentEnrollmentService.enrollForConfirmedPayment(event.paymentId(),
				event.orderId()), "exam.auto_enroll.failed", event.paymentId());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onPaymentRefunded(PaymentRefundedEvent event) {
		if (event.tenantId() == null || event.paymentId() == null) {
			return;
		}
		runInTenant(event.tenantId(), () -> {
			List<ExamEnrollment> removed = paymentEnrollmentService.removeForRefundedPayment(event.paymentId(),
					event.actorUserId(), event.refundId());
			log.atInfo()
				.setMessage("exam.auto_enroll.refund_processed")
				.addKeyValue("examTenantId", event.tenantId())
				.addKeyValue("paymentId", event.paymentId())
				.addKeyValue("removed", removed.size())
				.log();
			return removed.size();
		}, "exam.auto_enroll.refund_failed", event.paymentId());
	}

	private void runInTenant(UUID tenantId, java.util.function.Supplier<Integer> work, String failureMessage,
			UUID paymentId) {
		UUID previous = TenantContextHolder.isSet() ? new TenantContextHolder().getTenantId() : null;
		try {
			TenantContextHolder.set(tenantId);
			work.get();
		}
		catch (RuntimeException ex) {
			log.atError()
				.setMessage(failureMessage)
				.addKeyValue("examTenantId", tenantId)
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
