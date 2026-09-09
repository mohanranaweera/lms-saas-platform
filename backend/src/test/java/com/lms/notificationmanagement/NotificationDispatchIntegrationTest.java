package com.lms.notificationmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.notificationmanagement.domain.InAppNotification;
import com.lms.notificationmanagement.domain.NotificationDispatchStatus;
import com.lms.notificationmanagement.domain.NotificationEventType;
import com.lms.notificationmanagement.domain.NotificationOutbox;
import com.lms.notificationmanagement.service.NotificationDispatchService;
import com.lms.paymentmanagement.order.web.dto.OrderResponse;
import com.lms.paymentmanagement.order.web.dto.PaymentInitiationResponse;
import com.lms.tenantmanagement.domain.Tenant;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

/**
 * Testcontainers-backed coverage for MVP-018 plan §18's dispatch-pipeline
 * items: transaction separation between the triggering payment write and the
 * outbox insert, real end-to-end dispatch (render + send + {@code SENT} +
 * {@code in_app_notification} row), {@code FAILED} terminality (no retry),
 * crash-recovery re-claim of a still-{@code PENDING} row, the {@code FOR
 * UPDATE SKIP LOCKED} double-claim guarantee, and the recipient-resolution
 * cross-tenant email-collision guarantee. {@link
 * NotificationManagementTestSupport} overrides {@code MessagingProviderApi}
 * (Mockito mock, controlled per test) and {@code NotificationDispatchPoller}
 * (disabled, so only this test's own explicit {@link
 * NotificationDispatchService#dispatchOne} calls ever dispatch anything).
 */
class NotificationDispatchIntegrationTest extends NotificationManagementTestSupport {

	@Autowired
	private NotificationDispatchService notificationDispatchService;

	@Test
	void paymentConfirmationCommitsAnOutboxRowInItsOwnTransactionSeparateFromThePaymentWrite() {
		PaymentFixture fx = seedConfirmedPaymentFixture("notif-txsep");

		Long outboxCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM notification_outbox WHERE tenant_id = ? AND recipient_user_id = ? "
						+ "AND event_type = 'PAYMENT_CONFIRMED'",
				Long.class, fx.tenant().getId(), fx.studentId());
		assertThat(outboxCount).isEqualTo(1L);

		String outboxStatus = jdbcTemplate.queryForObject(
				"SELECT status FROM notification_outbox WHERE tenant_id = ? AND recipient_user_id = ? "
						+ "AND event_type = 'PAYMENT_CONFIRMED'",
				String.class, fx.tenant().getId(), fx.studentId());
		assertThat(outboxStatus).isEqualTo("PENDING");

		// The payment write itself committed CONFIRMED independent of the
		// outbox row's own separate small transaction - proving these are
		// two genuinely separate commits, not one shared transaction that
		// happened to also write the outbox row.
		String paymentStatus = jdbcTemplate.queryForObject("SELECT status FROM payment WHERE id = ?", String.class,
				fx.initiation().paymentId());
		assertThat(paymentStatus).isEqualTo("CONFIRMED");

		// No dispatch was ever triggered by this test - proves the AFTER_COMMIT
		// listener itself never touches MessagingProviderApi (its entire job is
		// the outbox insert only, per plan §9.1).
		verifyNoInteractions(messagingProviderApi);
	}

	@Test
	void endToEndDispatchRendersTheSeededTemplateSendsEmailAndInsertsAnInAppNotificationRow() {
		PaymentFixture fx = seedConfirmedPaymentFixture("notif-e2e");
		seedTemplate(fx.tenant().getId(), NotificationEventType.PAYMENT_CONFIRMED,
				"Payment Confirmed - {{amount}} {{currency}}",
				"Hi,\n\nYour payment of {{amount}} {{currency}} (ref {{paymentId}}) is confirmed.\n");
		UUID outboxId = jdbcTemplate.queryForObject(
				"SELECT id FROM notification_outbox WHERE tenant_id = ? AND recipient_user_id = ? "
						+ "AND event_type = 'PAYMENT_CONFIRMED'",
				UUID.class, fx.tenant().getId(), fx.studentId());

		notificationDispatchService.dispatchOne(outboxId);

		NotificationOutbox reloaded = withTenant(fx.tenant().getId(), () -> notificationOutboxRepository.findById(outboxId))
			.orElseThrow();
		assertThat(reloaded.getStatus()).isEqualTo(NotificationDispatchStatus.SENT);
		assertThat(reloaded.getDispatchedAt()).isNotNull();

		verify(messagingProviderApi).sendEmail(eq("student@example.test"), anyString(), anyString());

		List<InAppNotification> inApp = withTenant(fx.tenant().getId(),
				() -> inAppNotificationRepository.findByRecipientUserId(fx.studentId(), PageRequest.of(0, 10)))
			.getContent();
		assertThat(inApp).hasSize(1);
		InAppNotification created = inApp.get(0);
		assertThat(created.getTenantId()).isEqualTo(fx.tenant().getId());
		assertThat(created.getRecipientUserId()).isEqualTo(fx.studentId());
		assertThat(created.getTitle()).startsWith("Payment Confirmed -");
		assertThat(created.getBody()).contains("is confirmed.").contains(fx.initiation().paymentId().toString());
		assertThat(created.getReadAt()).isNull();
	}

	@Test
	void endToEndDispatchForAPaymentRejectedEventRendersTheCorrectVariablesAndMarksSent() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("notif-rejected"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser student = seedActiveStudent(tenant.getId(), "rejected-student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String studentToken = loginAndGetToken(host, "rejected-student@example.test");
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug("notif-rejected"), teacher.getId(), CourseStatus.PUBLIC));
		OrderResponse order = createOrderOrFail(host, studentToken, course.id());
		PaymentInitiationResponse initiation = initiatePaymentOrFail(host, studentToken, order.id());
		seedTemplate(tenant.getId(), NotificationEventType.PAYMENT_REJECTED,
				"Payment Rejected - {{amount}} {{currency}}", "Ref {{paymentId}} was not confirmed.");

		sendPaymentWebhook(initiation.gatewayReference(), false);

		UUID outboxId = jdbcTemplate.queryForObject(
				"SELECT id FROM notification_outbox WHERE tenant_id = ? AND recipient_user_id = ? "
						+ "AND event_type = 'PAYMENT_REJECTED'",
				UUID.class, tenant.getId(), student.getId());

		notificationDispatchService.dispatchOne(outboxId);

		NotificationOutbox reloaded = withTenant(tenant.getId(), () -> notificationOutboxRepository.findById(outboxId))
			.orElseThrow();
		assertThat(reloaded.getStatus()).isEqualTo(NotificationDispatchStatus.SENT);
		verify(messagingProviderApi).sendEmail(eq("rejected-student@example.test"),
				org.mockito.ArgumentMatchers.startsWith("Payment Rejected -"),
				org.mockito.ArgumentMatchers.contains(initiation.paymentId().toString()));
	}

	@Test
	void endToEndDispatchForAPaymentRefundedEventRendersTheStaffSuppliedReasonAndMarksSent() {
		PaymentFixture fx = seedConfirmedPaymentFixture("notif-refunded");
		seedTemplate(fx.tenant().getId(), NotificationEventType.PAYMENT_REFUNDED,
				"Refund of {{amount}} processed", "Reason: {{reason}}. Ref {{paymentId}}.");
		String host = hostFor(fx.tenant().getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");

		createRefund(host, adminToken, fx.initiation().paymentId(), new BigDecimal("5.00"),
				"duplicate charge - $5 double-billed");

		UUID outboxId = jdbcTemplate.queryForObject(
				"SELECT id FROM notification_outbox WHERE tenant_id = ? AND recipient_user_id = ? "
						+ "AND event_type = 'PAYMENT_REFUNDED'",
				UUID.class, fx.tenant().getId(), fx.studentId());

		notificationDispatchService.dispatchOne(outboxId);

		NotificationOutbox reloaded = withTenant(fx.tenant().getId(),
				() -> notificationOutboxRepository.findById(outboxId)).orElseThrow();
		assertThat(reloaded.getStatus()).isEqualTo(NotificationDispatchStatus.SENT);
		// Free-text staff-supplied reason (containing a literal "$") renders
		// literally, proving NotificationTemplateRenderer's
		// Matcher.quoteReplacement usage handles regex-special characters in
		// substituted values correctly end-to-end, not just at the unit level.
		// Subject uses "5.00" (not "5.0") - NotificationTemplateRenderer now
		// explicitly re-normalizes the "amount" variable to a fixed 2-decimal
		// scale at render time, closing what was a pre-existing quirk of the
		// BigDecimal -> JSONB payload -> generic Map<String,Object> round trip
		// losing trailing-zero scale (post-ship review fix).
		verify(messagingProviderApi).sendEmail(eq("student@example.test"), eq("Refund of 5.00 processed"),
				org.mockito.ArgumentMatchers.contains("duplicate charge - $5 double-billed"));
	}

	@Test
	void aFailedRowIsNeverReClaimedOnASubsequentDispatchAttempt() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("notif-failed"));
		TenantUser student = seedActiveStudent(tenant.getId(), "failed-student@example.test");
		// Deliberately NO notification_template row seeded for this tenant -
		// TemplateNotFoundException is the forcing failure (plan §13).
		NotificationOutbox outbox = seedPendingOutbox(tenant.getId(), student.getId(),
				NotificationEventType.PAYMENT_CONFIRMED,
				Map.of("amount", "10.00", "currency", "USD", "paymentId", UUID.randomUUID().toString()));

		notificationDispatchService.dispatchOne(outbox.getId());

		NotificationOutbox afterFirst = withTenant(tenant.getId(), () -> notificationOutboxRepository.findById(outbox.getId()))
			.orElseThrow();
		assertThat(afterFirst.getStatus()).isEqualTo(NotificationDispatchStatus.FAILED);
		assertThat(afterFirst.getDispatchedAt()).isNotNull();
		Instant firstDispatchedAt = afterFirst.getDispatchedAt();

		// A second attempt on the SAME now-terminal row id must be a complete
		// no-op: claimPendingByIdAcrossTenantsForUpdateSkipLocked's own
		// `status = 'PENDING'` guard excludes it (no separate retry path
		// exists anywhere in this service).
		notificationDispatchService.dispatchOne(outbox.getId());

		NotificationOutbox afterSecond = withTenant(tenant.getId(), () -> notificationOutboxRepository.findById(outbox.getId()))
			.orElseThrow();
		assertThat(afterSecond.getStatus()).isEqualTo(NotificationDispatchStatus.FAILED);
		assertThat(afterSecond.getDispatchedAt()).isEqualTo(firstDispatchedAt);
		verifyNoInteractions(messagingProviderApi);
	}

	@Test
	void aRowNotYetTouchedByAnyDispatchAttemptStaysPendingAndIsCorrectlyClaimedAndDispatchedOnTheNextAttempt() {
		// NOTE: this test's name/comment previously described this as
		// "crash recovery" and claimed "there is no separate CLAIMED status
		// column" - both stale since V29/the SENDING claimed-state design:
		// there IS now a distinct SENDING status (with its own claimed_at,
		// V29) between PENDING and terminal, and a row that crashed mid-claim
		// would be stuck at SENDING, NOT still PENDING (see
		// NotificationDispatchReconciliationIntegrationTest for actual
		// SENDING-crash-recovery coverage). What THIS test actually
		// proves is narrower and still true: a row that was merely LISTED by
		// an earlier poll cycle's id-listing read (findPendingIdsAcrossTenants)
		// but never actually claimed - i.e. still genuinely PENDING in the DB
		// - is correctly picked up and dispatched on a later attempt. That is
		// normal, expected poller behavior, not a crash-recovery path.
		Tenant tenant = seedActiveTenant(uniqueSubdomain("notif-still-pending"));
		TenantUser student = seedActiveStudent(tenant.getId(), "still-pending-student@example.test");
		seedTemplate(tenant.getId(), NotificationEventType.PAYMENT_CONFIRMED,
				"Payment Confirmed - {{amount}} {{currency}}", "Ref {{paymentId}}");
		NotificationOutbox outbox = seedPendingOutbox(tenant.getId(), student.getId(),
				NotificationEventType.PAYMENT_CONFIRMED,
				Map.of("amount", "10.00", "currency", "USD", "paymentId", UUID.randomUUID().toString()));

		NotificationOutbox stillPending = withTenant(tenant.getId(),
				() -> notificationOutboxRepository.findById(outbox.getId())).orElseThrow();
		assertThat(stillPending.getStatus()).isEqualTo(NotificationDispatchStatus.PENDING);
		assertThat(stillPending.getDispatchedAt()).isNull();

		notificationDispatchService.dispatchOne(outbox.getId());

		NotificationOutbox afterDispatch = withTenant(tenant.getId(),
				() -> notificationOutboxRepository.findById(outbox.getId())).orElseThrow();
		assertThat(afterDispatch.getStatus()).isEqualTo(NotificationDispatchStatus.SENT);
		assertThat(afterDispatch.getDispatchedAt()).isNotNull();
		verify(messagingProviderApi).sendEmail(eq("still-pending-student@example.test"), anyString(), anyString());
	}

	@Test
	void twoGenuinelyConcurrentDispatchAttemptsOnTheSameRowNeverBothSendAndLeaveExactlyOneTerminalState()
			throws Exception {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("notif-race"));
		TenantUser student = seedActiveStudent(tenant.getId(), "race-student@example.test");
		seedTemplate(tenant.getId(), NotificationEventType.PAYMENT_CONFIRMED,
				"Payment Confirmed - {{amount}} {{currency}}", "Ref {{paymentId}}");
		NotificationOutbox outbox = seedPendingOutbox(tenant.getId(), student.getId(),
				NotificationEventType.PAYMENT_CONFIRMED,
				Map.of("amount", "10.00", "currency", "USD", "paymentId", UUID.randomUUID().toString()));
		UUID outboxId = outbox.getId();

		int concurrency = 2;
		CyclicBarrier barrier = new CyclicBarrier(concurrency);
		ExecutorService executor = Executors.newFixedThreadPool(concurrency);
		List<Callable<Void>> tasks = new ArrayList<>();
		for (int i = 0; i < concurrency; i++) {
			tasks.add(() -> {
				barrier.await();
				notificationDispatchService.dispatchOne(outboxId);
				return null;
			});
		}

		try {
			List<Future<Void>> futures = executor.invokeAll(tasks);
			for (Future<Void> future : futures) {
				// Neither call may throw - the loser's claim query simply
				// returns empty and dispatchOne returns normally.
				future.get(15, TimeUnit.SECONDS);
			}
		}
		finally {
			executor.shutdownNow();
		}

		verify(messagingProviderApi, times(1)).sendEmail(eq("race-student@example.test"), anyString(), anyString());
		NotificationOutbox afterRace = withTenant(tenant.getId(), () -> notificationOutboxRepository.findById(outboxId))
			.orElseThrow();
		assertThat(afterRace.getStatus()).isEqualTo(NotificationDispatchStatus.SENT);
		Long inAppCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM in_app_notification WHERE tenant_id = ? AND recipient_user_id = ?", Long.class,
				tenant.getId(), student.getId());
		assertThat(inAppCount).isEqualTo(1L);
	}

	@Test
	void recipientResolutionNeverCrossesTenantsEvenWhenTwoTenantsShareTheSameRecipientEmail() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("notif-collide-a"));
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("notif-collide-b"));
		String sharedEmail = "shared-" + UUID.randomUUID() + "@example.test";
		TenantUser studentA = seedActiveStudent(tenantA.getId(), sharedEmail);
		TenantUser studentB = seedActiveStudent(tenantB.getId(), sharedEmail);
		seedTemplate(tenantA.getId(), NotificationEventType.PAYMENT_CONFIRMED, "TENANT-A Confirmed - {{amount}}",
				"A body ref {{paymentId}}");
		seedTemplate(tenantB.getId(), NotificationEventType.PAYMENT_CONFIRMED, "TENANT-B Confirmed - {{amount}}",
				"B body ref {{paymentId}}");
		String paymentRef = UUID.randomUUID().toString();
		NotificationOutbox outboxA = seedPendingOutbox(tenantA.getId(), studentA.getId(),
				NotificationEventType.PAYMENT_CONFIRMED, Map.of("amount", "77.00", "paymentId", paymentRef));

		notificationDispatchService.dispatchOne(outboxA.getId());

		// Sent to the SHARED email, but rendered with TENANT A's own template
		// copy - proves recipient resolution went through
		// (tenant_id, recipientUserId), never an email lookup (which could
		// have ambiguously matched either tenant's row).
		verify(messagingProviderApi, times(1)).sendEmail(eq(sharedEmail), eq("TENANT-A Confirmed - 77.00"),
				contains("A body"));

		List<InAppNotification> tenantAInApp = withTenant(tenantA.getId(),
				() -> inAppNotificationRepository.findByRecipientUserId(studentA.getId(), PageRequest.of(0, 10)))
			.getContent();
		assertThat(tenantAInApp).hasSize(1);
		assertThat(tenantAInApp.get(0).getTitle()).isEqualTo("TENANT-A Confirmed - 77.00");

		// Tenant B's same-email user is completely untouched: zero outbox
		// rows, zero in_app_notification rows, ever created for tenant B as
		// a side effect of tenant A's dispatch.
		Long tenantBInAppCount = jdbcTemplate.queryForObject("SELECT count(*) FROM in_app_notification WHERE tenant_id = ?",
				Long.class, tenantB.getId());
		assertThat(tenantBInAppCount).isEqualTo(0L);
		Long tenantBOutboxCount = jdbcTemplate.queryForObject("SELECT count(*) FROM notification_outbox WHERE tenant_id = ?",
				Long.class, tenantB.getId());
		assertThat(tenantBOutboxCount).isEqualTo(0L);

		// Also proves dispatch's repository access used the structural
		// TenantContextHolder-driven filter, not an ambient/leaked context:
		// under tenant B's OWN context, its own template is visible (sanity)
		// and is unmodified/distinct from tenant A's copy.
		var templateUnderB = withTenant(tenantB.getId(),
				() -> notificationTemplateRepository.findByTemplateKey("PAYMENT_CONFIRMED"));
		assertThat(templateUnderB).isPresent();
		assertThat(templateUnderB.get().getSubject()).isEqualTo("TENANT-B Confirmed - {{amount}}");
	}

	// ------------------------------------------------------------------

	private PaymentFixture seedConfirmedPaymentFixture(String prefix) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain(prefix));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser student = seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug(prefix), teacher.getId(), CourseStatus.PUBLIC));
		OrderResponse order = createOrderOrFail(host, studentToken, course.id());
		PaymentInitiationResponse initiation = initiatePaymentOrFail(host, studentToken, order.id());
		sendPaymentWebhook(initiation.gatewayReference(), true);
		return new PaymentFixture(tenant, student.getId(), order, initiation);
	}

	private record PaymentFixture(Tenant tenant, UUID studentId, OrderResponse order,
			PaymentInitiationResponse initiation) {
	}

}
