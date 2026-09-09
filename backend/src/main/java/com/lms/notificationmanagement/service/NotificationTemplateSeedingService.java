package com.lms.notificationmanagement.service;

import com.lms.common.tenant.TenantContextHolder;
import com.lms.notificationmanagement.domain.NotificationEventType;
import com.lms.tenantmanagement.api.TenantRegisteredEvent;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Closes the template-origination gap flagged in plan §21 item 1: NOTIF-2's
 * own acceptance criterion requires templates to be "tenant-scoped... never a
 * shared static default," but no staff role/UI in this MVP's scope can create
 * a {@code notification_template} row, and no seeding mechanism was specified
 * anywhere. This listener seeds three default template rows (one per {@link
 * NotificationEventType}) for every newly-registered tenant, at the one real
 * tenant-provisioning hook point that exists in this codebase today ({@code
 * TenantRegistrationService#register}, which publishes {@link
 * TenantRegisteredEvent} at {@code PENDING_APPROVAL} status - there is no
 * separate "approval" step wired to anything in this codebase yet).
 *
 * <p>This is NOT a general template-authoring feature - it is a narrow,
 * disclosed default-content seed. <b>Known, disclosed gap, not a bug:</b>
 * tenants registered before this feature shipped are NOT backfilled by this
 * listener; only tenants registered from this point forward receive seeded
 * templates. A future template-authoring endpoint (still unresolved per plan
 * §21) would let a tenant customize/replace this seeded starting copy.
 *
 * <p>Same {@code TenantContextHolder} set-in-try/clear-in-finally discipline
 * as {@link NotificationOutboxService} - see that class's javadoc for the
 * full rationale on why this cannot rely on ambient context inside an {@code
 * AFTER_COMMIT} listener.
 *
 * <h2>Idempotency</h2>
 * Each of the three {@link #seedIfAbsent} calls below delegates the actual
 * guarded insert to {@link NotificationTemplateSeedWriter#seedIfAbsent}, a
 * separate {@code Propagation.REQUIRES_NEW} collaborator - see that class's
 * javadoc for why a plain check-then-insert sharing one ambient transaction
 * across all three calls cannot actually be made race-safe by catching the
 * unique-constraint violation in place, and why this two-class split is what
 * makes {@code uq_notification_template_tenant_key} (V28) an effective,
 * atomic guard rather than just a backstop for a check this class's own code
 * never made safe.
 */
@Service
public class NotificationTemplateSeedingService {

	private static final Logger log = LoggerFactory.getLogger(NotificationTemplateSeedingService.class);

	/** The constraint a lost seed race is expected to violate - see {@link #seedIfAbsent}. */
	private static final String EXPECTED_LOST_RACE_CONSTRAINT = "uq_notification_template_tenant_key";

	private final NotificationTemplateSeedWriter notificationTemplateSeedWriter;

	public NotificationTemplateSeedingService(NotificationTemplateSeedWriter notificationTemplateSeedWriter) {
		this.notificationTemplateSeedWriter = notificationTemplateSeedWriter;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void onTenantRegistered(TenantRegisteredEvent event) {
		try {
			TenantContextHolder.set(event.tenantId());
			seedIfAbsent(event.tenantId(), NotificationEventType.PAYMENT_CONFIRMED,
					"Payment Confirmed - {{amount}} {{currency}}",
					"Hi,\n\nYour payment of {{amount}} {{currency}} (reference {{paymentId}}) has been confirmed. Thank you.\n");
			seedIfAbsent(event.tenantId(), NotificationEventType.PAYMENT_REJECTED, "Payment Rejected",
					"Hi,\n\nYour payment of {{amount}} {{currency}} (reference {{paymentId}}) could not be confirmed. Please contact support or try again.\n");
			seedIfAbsent(event.tenantId(), NotificationEventType.PAYMENT_REFUNDED, "Refund Processed",
					"Hi,\n\nA refund of {{amount}} for payment {{paymentId}} has been processed. Reason: {{reason}}.\n");
		}
		finally {
			TenantContextHolder.clear();
		}
	}

	/**
	 * Defensive idempotency (in case {@link TenantRegisteredEvent} is ever
	 * redelivered, or a template already exists via some other path) - never
	 * overwrite an existing row, only insert if absent. Actually race-safe
	 * (not just "checked, then hope"): {@link
	 * NotificationTemplateSeedWriter#seedIfAbsent} runs the check-then-insert
	 * in its own {@code REQUIRES_NEW} transaction and lets a genuine
	 * unique-constraint loss propagate; this method is the one that catches
	 * it, fully inside its own (separate, still-healthy) transactional
	 * body - see both classes' javadoc for the full reasoning, mirroring
	 * {@code EnrollmentExpiryService}'s identical pattern.
	 */
	private void seedIfAbsent(UUID tenantId, NotificationEventType eventType, String subject, String body) {
		String templateKey = eventType.name();
		try {
			notificationTemplateSeedWriter.seedIfAbsent(tenantId, templateKey, subject, body);
		}
		catch (DataIntegrityViolationException ex) {
			if (!referencesExpectedConstraint(ex)) {
				// Not the known, safely-ignorable lost-race case against
				// uq_notification_template_tenant_key - an unexpected
				// integrity violation must not be silently swallowed
				// alongside the expected race.
				throw ex;
			}
			// Lost a race against a concurrent seed attempt for the same
			// (tenantId, templateKey) - uq_notification_template_tenant_key
			// (V28) already has the row, which is success, not failure. The
			// writer's own REQUIRES_NEW transaction has already rolled back
			// cleanly by this point; catching here (not inside the writer)
			// means this method's own ambient transaction never sees the
			// exception cross its boundary, so it stays healthy and the next
			// seed attempt (a different templateKey) can safely continue.
			log.debug("Lost race seeding notification_template ({}, {}); already seeded by a concurrent invocation",
					tenantId, templateKey);
		}
	}

	private static boolean referencesExpectedConstraint(DataIntegrityViolationException ex) {
		Throwable mostSpecificCause = ex.getMostSpecificCause();
		String message = mostSpecificCause != null ? mostSpecificCause.getMessage() : null;
		return message != null && message.contains(EXPECTED_LOST_RACE_CONSTRAINT);
	}

}
