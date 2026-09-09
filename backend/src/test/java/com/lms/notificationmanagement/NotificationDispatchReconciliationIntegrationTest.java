package com.lms.notificationmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.notificationmanagement.domain.NotificationDispatchStatus;
import com.lms.notificationmanagement.domain.NotificationEventType;
import com.lms.notificationmanagement.domain.NotificationOutbox;
import com.lms.notificationmanagement.service.NotificationDispatchReconciliationService;
import com.lms.tenantmanagement.domain.Tenant;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Testcontainers-backed coverage for the V29 stuck-{@code SENDING}
 * reconciliation step (post-ship multi-agent review finding) - closes the
 * disclosed, bounded "a crash between the claim committing and the
 * terminal-status commit leaves a row stuck at SENDING with no automatic
 * recovery" gap. See {@link NotificationDispatchReconciliationService}'s
 * javadoc for the full "failing forward, not a retry" rationale, and {@code
 * docs/requirements/open-decisions.md} for the original disclosed gap this
 * closes.
 *
 * <p>Seeds the stuck row directly via {@link NotificationOutbox#markSending}
 * with an explicit {@code claimedAt} - simulating exactly the crash scenario
 * (a row that transitioned {@code PENDING -> SENDING} and then nothing else
 * ever happened to it), rather than trying to interrupt a real dispatch
 * mid-flight.
 */
class NotificationDispatchReconciliationIntegrationTest extends NotificationManagementTestSupport {

	@Autowired
	private NotificationDispatchReconciliationService notificationDispatchReconciliationService;

	@Test
	void aSendingRowClaimedLongBeforeTheTimeoutIsMarkedFailedAndNeverReClaimedAfterward() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("notif-recon-stuck"));
		TenantUser student = seedActiveStudent(tenant.getId(), "recon-stuck-student@example.test");
		NotificationOutbox outbox = seedStuckSendingOutbox(tenant.getId(), student.getId(),
				Instant.now().minus(10, ChronoUnit.MINUTES));

		boolean reconciled = notificationDispatchReconciliationService.reconcileOne(outbox.getId());

		assertThat(reconciled).isTrue();
		NotificationOutbox afterReconcile = withTenant(tenant.getId(),
				() -> notificationOutboxRepository.findById(outbox.getId())).orElseThrow();
		assertThat(afterReconcile.getStatus()).isEqualTo(NotificationDispatchStatus.FAILED);
		assertThat(afterReconcile.getDispatchedAt()).isNotNull();

		// Never re-claimed afterward - a second reconciliation attempt on the
		// same now-terminal row is a complete no-op: the repository's own
		// `status = 'SENDING'` guard excludes it (no retry, mirroring this
		// module's existing FAILED-is-terminal discipline).
		boolean reconciledAgain = notificationDispatchReconciliationService.reconcileOne(outbox.getId());

		assertThat(reconciledAgain).isFalse();
		NotificationOutbox afterSecondAttempt = withTenant(tenant.getId(),
				() -> notificationOutboxRepository.findById(outbox.getId())).orElseThrow();
		assertThat(afterSecondAttempt.getStatus()).isEqualTo(NotificationDispatchStatus.FAILED);
		assertThat(afterSecondAttempt.getDispatchedAt()).isEqualTo(afterReconcile.getDispatchedAt());
		// Reconciliation never attempted to actually send anything - it only
		// ever fails a stuck claim forward, never retries the send itself.
		verifyNoInteractions(messagingProviderApi);
	}

	@Test
	void aRecentlyClaimedSendingRowWithinTheTimeoutWindowIsLeftAloneByReconciliation() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("notif-recon-recent"));
		TenantUser student = seedActiveStudent(tenant.getId(), "recon-recent-student@example.test");
		NotificationOutbox outbox = seedStuckSendingOutbox(tenant.getId(), student.getId(), Instant.now());

		boolean reconciled = notificationDispatchReconciliationService.reconcileOne(outbox.getId());

		assertThat(reconciled).isFalse();
		NotificationOutbox afterReconcile = withTenant(tenant.getId(),
				() -> notificationOutboxRepository.findById(outbox.getId())).orElseThrow();
		// Untouched - still SENDING, not falsely marked FAILED while a real
		// dispatch attempt could still legitimately be in flight.
		assertThat(afterReconcile.getStatus()).isEqualTo(NotificationDispatchStatus.SENDING);
		assertThat(afterReconcile.getDispatchedAt()).isNull();
	}

	private NotificationOutbox seedStuckSendingOutbox(UUID tenantId, UUID recipientUserId, Instant claimedAt) {
		String payload = objectMapper.writeValueAsString(
				Map.of("amount", "10.00", "currency", "USD", "paymentId", UUID.randomUUID().toString()));
		return withTenant(tenantId, () -> {
			NotificationOutbox outbox = new NotificationOutbox(tenantId, NotificationEventType.PAYMENT_CONFIRMED,
					recipientUserId, payload, Instant.now());
			outbox.markSending(claimedAt);
			return notificationOutboxRepository.save(outbox);
		});
	}

}
