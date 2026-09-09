package com.lms.notificationmanagement.service;

import com.lms.notificationmanagement.domain.NotificationTemplate;
import com.lms.notificationmanagement.repository.NotificationTemplateRepository;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Internal collaborator of {@link NotificationTemplateSeedingService} - NOT
 * exposed via this module's {@code api} package, never injected by another
 * domain (package-private, mirroring {@code
 * enrollmentmanagement.service.EnrollmentExpiryEventWriter}'s established
 * convention for a single-purpose guarded-insert collaborator).
 *
 * <h2>Why this runs in its OWN {@code Propagation.REQUIRES_NEW} transaction, and why the exception is NOT caught here</h2>
 * Exact same shape/reasoning as {@code EnrollmentExpiryEventWriter} (see its
 * javadoc for the full explanation, which this mirrors rather than repeats):
 * {@code uq_notification_template_tenant_key} (V28) is this table's own guard
 * against a genuine concurrent double-seed (e.g. two overlapping {@link
 * NotificationTemplateSeedingService#onTenantRegistered} invocations for the
 * same tenant, such as a redelivered {@code TenantRegisteredEvent}), but the
 * previous {@code findByTemplateKey(...).isPresent()}-then-{@code save(...)}
 * shape in the caller was a plain check-then-insert with no transactional
 * guard at all - despite the caller's own javadoc previously claiming
 * idempotency protection the unique constraint alone cannot provide from
 * inside a single ambient transaction shared across all three seed attempts:
 * once one insert in that loop fails the unique-constraint check, Postgres
 * aborts the CURRENT transaction at the statement level, and Hibernate/JPA
 * marks the underlying {@code EntityTransaction} rollback-only internally
 * regardless of whether the translated {@link DataIntegrityViolationException}
 * is caught - so every subsequent statement in that same transaction
 * (including a seed attempt for a different, still-genuinely-absent template
 * key) would fail too, and a plain catch-and-continue in the SAME transaction
 * would only trade that failure for a very visible {@link
 * org.springframework.transaction.UnexpectedRollbackException} at commit
 * time.
 *
 * <p>{@code Propagation.REQUIRES_NEW} isolates each single seed attempt in
 * its own genuinely separate physical transaction, so a losing attempt's
 * abort is fully contained here; the caller ({@link
 * NotificationTemplateSeedingService#onTenantRegistered}) is the one that
 * catches {@link DataIntegrityViolationException}, fully inside its own
 * (separate, still-healthy) transactional method body, per {@code
 * EnrollmentExpiryEventWriter}'s documented rule that only an exception
 * escaping THIS method's own proxy boundary marks anything rollback-only.
 */
@Service
class NotificationTemplateSeedWriter {

	private final NotificationTemplateRepository notificationTemplateRepository;

	NotificationTemplateSeedWriter(NotificationTemplateRepository notificationTemplateRepository) {
		this.notificationTemplateRepository = notificationTemplateRepository;
	}

	/**
	 * @throws DataIntegrityViolationException when this call loses a genuine
	 * concurrent race against another seed attempt for the same {@code
	 * (tenantId, templateKey)} - deliberately left uncaught here; see class
	 * javadoc for why the caller must be the one to catch it.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	void seedIfAbsent(UUID tenantId, String templateKey, String subject, String body) {
		if (notificationTemplateRepository.findByTemplateKey(templateKey).isPresent()) {
			return;
		}
		// saveAndFlush (not save) is load-bearing, mirroring
		// EnrollmentExpiryEventWriter#recordExpiryEventIfAbsent - a plain
		// save() would not flush inside this method, so a genuine constraint
		// violation would surface later (commit-time auto-flush) rather than
		// here, where the caller expects it.
		notificationTemplateRepository.saveAndFlush(new NotificationTemplate(tenantId, templateKey, subject, body));
	}

}
