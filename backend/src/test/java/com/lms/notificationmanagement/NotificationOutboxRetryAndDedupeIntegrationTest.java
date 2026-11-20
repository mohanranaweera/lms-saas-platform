package com.lms.notificationmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.notificationmanagement.domain.NotificationEventType;
import com.lms.notificationmanagement.domain.NotificationOutbox;
import com.lms.notificationmanagement.service.NotificationDispatchService;
import com.lms.notificationmanagement.service.NotificationOutboxService;
import com.lms.paymentmanagement.api.PaymentConfirmedEvent;
import com.lms.paymentmanagement.payment.domain.PaymentStatus;
import com.lms.tenantmanagement.domain.Tenant;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.MailSendException;

/**
 * Wave 11 changes to the transactional email outbox (decision D3, V68):
 * bounded retry of SMTP failures with backoff, permanent failures still
 * terminal at once, and duplicate payment events producing a single email.
 */
class NotificationOutboxRetryAndDedupeIntegrationTest extends NotificationManagementTestSupport {

	@Autowired
	private NotificationDispatchService notificationDispatchService;

	@Autowired
	private NotificationOutboxService notificationOutboxService;

	@Test
	void anSmtpFailureIsRetriedWithBackoffAndFailsAfterTheThirdAttempt() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("outbox-retry"));
		TenantUser student = seedActiveStudent(tenant.getId(), "retry-student@example.test");
		seedTemplate(tenant.getId(), NotificationEventType.PAYMENT_CONFIRMED, "Paid {{amount}}", "Ref {{paymentId}}");
		NotificationOutbox outbox = seedPendingOutbox(tenant.getId(), student.getId(),
				NotificationEventType.PAYMENT_CONFIRMED,
				Map.of("amount", "10.00", "currency", "LKR", "paymentId", UUID.randomUUID().toString()));
		doThrow(new MailSendException("smtp unavailable")).when(messagingProviderApi)
			.sendEmail(anyString(), anyString(), anyString());

		notificationDispatchService.dispatchOne(outbox.getId());
		Map<String, Object> first = row(outbox.getId());
		assertThat(first).containsEntry("status", "PENDING").containsEntry("attempt_count", 1);
		assertThat(first.get("next_attempt_at")).isNotNull();
		assertThat(first.get("claimed_at")).isNull();
		assertThat(notificationOutboxRepository.findPendingIdsAcrossTenants(1000))
			.as("not due again until the backoff passes")
			.doesNotContain(outbox.getId());

		makeDue(outbox.getId());
		notificationDispatchService.dispatchOne(outbox.getId());
		assertThat(row(outbox.getId())).containsEntry("status", "PENDING").containsEntry("attempt_count", 2);

		makeDue(outbox.getId());
		notificationDispatchService.dispatchOne(outbox.getId());
		Map<String, Object> last = row(outbox.getId());
		assertThat(last).containsEntry("status", "FAILED").containsEntry("attempt_count", 3);
		assertThat(last.get("next_attempt_at")).isNull();
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM in_app_notification WHERE recipient_user_id = ?",
				Integer.class, student.getId())).isZero();
	}

	@Test
	void aPermanentFailureIsNotRetried() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("outbox-perm"));
		TenantUser student = seedActiveStudent(tenant.getId(), "perm-student@example.test");
		// No template seeded: TemplateNotFoundException is permanent.
		NotificationOutbox outbox = seedPendingOutbox(tenant.getId(), student.getId(),
				NotificationEventType.PAYMENT_CONFIRMED,
				Map.of("amount", "10.00", "currency", "LKR", "paymentId", UUID.randomUUID().toString()));

		notificationDispatchService.dispatchOne(outbox.getId());

		assertThat(row(outbox.getId())).containsEntry("status", "FAILED").containsEntry("attempt_count", 1);
	}

	@Test
	void aRedeliveredPaymentEventProducesOnlyOneOutboxRow() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("outbox-dedupe"));
		TenantUser student = seedActiveStudent(tenant.getId(), "dedupe-student@example.test");
		PaymentConfirmedEvent event = new PaymentConfirmedEvent(tenant.getId(), UUID.randomUUID(), UUID.randomUUID(),
				PaymentStatus.PENDING, PaymentStatus.CONFIRMED, Instant.now(), student.getId(), new BigDecimal("25.00"),
				"LKR");

		notificationOutboxService.onPaymentConfirmed(event);
		notificationOutboxService.onPaymentConfirmed(event);

		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM notification_outbox WHERE tenant_id = ? "
				+ "AND recipient_user_id = ?", Integer.class, tenant.getId(), student.getId())).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("SELECT dedupe_key FROM notification_outbox WHERE tenant_id = ?",
				String.class, tenant.getId())).isEqualTo("PAYMENT_CONFIRMED:" + event.paymentId());
	}

	private Map<String, Object> row(UUID id) {
		return jdbcTemplate.queryForMap("SELECT * FROM notification_outbox WHERE id = ?", id);
	}

	private void makeDue(UUID id) {
		jdbcTemplate.update("UPDATE notification_outbox SET next_attempt_at = now() - interval '1 second' WHERE id = ?",
				id);
	}

}
