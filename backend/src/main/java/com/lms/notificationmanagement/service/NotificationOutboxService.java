package com.lms.notificationmanagement.service;

import com.lms.common.tenant.TenantContextHolder;
import com.lms.notificationmanagement.domain.NotificationEventType;
import com.lms.notificationmanagement.domain.NotificationOutbox;
import com.lms.notificationmanagement.repository.NotificationOutboxRepository;
import com.lms.paymentmanagement.api.PaymentConfirmedEvent;
import com.lms.paymentmanagement.api.PaymentRefundedEvent;
import com.lms.paymentmanagement.api.PaymentRejectedEvent;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code payment-management}'s three payment-outcome events (plan
 * §9.1). Each listener is {@code @TransactionalEventListener(phase =
 * AFTER_COMMIT)} - it fires on the same (request) thread, synchronously,
 * immediately after the triggering transaction commits, so it never shares a
 * transaction with, or blocks, the triggering write. Its entire job is to
 * build one {@code notification_outbox} row from the event's own fields and
 * persist it in one small, explicitly {@code @Transactional} write - no
 * cross-module calls, no template resolution, no email send happen here, per
 * plan §9.1's "keeps the listener's added latency to a single-row insert"
 * requirement.
 *
 * <h2>Why {@code TenantContextHolder} is set explicitly here, not read ambiently</h2>
 * By the time Spring's transaction-commit machinery invokes an {@code
 * AFTER_COMMIT} listener, the publishing service's own {@code finally {
 * TenantContextHolder.clear(); }} (see {@code
 * PaymentConfirmationService#confirmByGatewayReference}) has already run to
 * completion - the target method body, including its {@code finally} block,
 * fully completes before the {@code @Transactional} proxy's advice commits
 * the transaction and fires synchronization callbacks. So the ambient
 * thread-local is not reliably populated here; every method below explicitly
 * does {@code TenantContextHolder.set(event.tenantId())} - the event's own
 * field, never an assumed-ambient value - in a {@code try} block, with {@code
 * TenantContextHolder.clear()} unconditionally in {@code finally}, mirroring
 * {@code PaymentConfirmationService}'s own set-in-try/clear-in-finally shape
 * (plan §14's "first place in this codebase a thread boundary is crossed
 * with tenant context in production code").
 */
@Service
public class NotificationOutboxService {

	private final NotificationOutboxRepository notificationOutboxRepository;

	private final ObjectMapper objectMapper;

	public NotificationOutboxService(NotificationOutboxRepository notificationOutboxRepository,
			ObjectMapper objectMapper) {
		this.notificationOutboxRepository = notificationOutboxRepository;
		this.objectMapper = objectMapper;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void onPaymentConfirmed(PaymentConfirmedEvent event) {
		Map<String, Object> variables = new LinkedHashMap<>();
		variables.put("amount", event.amount());
		variables.put("currency", event.currency());
		variables.put("paymentId", event.paymentId().toString());
		insertOutboxRow(event.tenantId(), NotificationEventType.PAYMENT_CONFIRMED, event.studentId(), variables);
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void onPaymentRejected(PaymentRejectedEvent event) {
		Map<String, Object> variables = new LinkedHashMap<>();
		variables.put("amount", event.amount());
		variables.put("currency", event.currency());
		variables.put("paymentId", event.paymentId().toString());
		insertOutboxRow(event.tenantId(), NotificationEventType.PAYMENT_REJECTED, event.studentId(), variables);
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void onPaymentRefunded(PaymentRefundedEvent event) {
		Map<String, Object> variables = new LinkedHashMap<>();
		variables.put("amount", event.amount());
		variables.put("reason", event.reason());
		variables.put("paymentId", event.paymentId().toString());
		insertOutboxRow(event.tenantId(), NotificationEventType.PAYMENT_REFUNDED, event.studentId(), variables);
	}

	private void insertOutboxRow(java.util.UUID tenantId, NotificationEventType eventType,
			java.util.UUID recipientUserId, Map<String, Object> variables) {
		// Fail loud rather than insert a row with no resolvable recipient
		// (plan §12) - the NOT NULL DB constraint is the backstop, but a
		// constraint violation surfacing as a generic 500 from inside an
		// AFTER_COMMIT listener (after the triggering response has already
		// been sent) is a worse failure mode than catching it here.
		if (recipientUserId == null) {
			throw new IllegalStateException(
					"Cannot create a notification_outbox row with no recipientUserId for event type " + eventType);
		}
		try {
			TenantContextHolder.set(tenantId);
			String payload = objectMapper.writeValueAsString(variables);
			NotificationOutbox outbox = new NotificationOutbox(tenantId, eventType, recipientUserId, payload,
					Instant.now());
			notificationOutboxRepository.save(outbox);
		}
		finally {
			TenantContextHolder.clear();
		}
	}

}
