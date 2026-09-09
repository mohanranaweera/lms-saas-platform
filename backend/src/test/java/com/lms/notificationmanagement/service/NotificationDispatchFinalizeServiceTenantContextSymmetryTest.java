package com.lms.notificationmanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.lms.common.tenant.TenantContextHolder;
import com.lms.notificationmanagement.domain.NotificationEventType;
import com.lms.notificationmanagement.domain.NotificationOutbox;
import com.lms.notificationmanagement.repository.InAppNotificationRepository;
import com.lms.notificationmanagement.repository.NotificationOutboxRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Deterministic, non-async, plain Mockito unit test directly against {@link
 * NotificationDispatchFinalizeService#markSent}/{@link
 * NotificationDispatchFinalizeService#markFailed} - proves each method's
 * independent {@code TenantContextHolder} try/finally is symmetric, both on a
 * normal return and when a collaborator throws mid-method. Same
 * technique/rigor as {@code
 * NotificationDispatchServiceTenantContextSymmetryTest} (see that class's
 * javadoc) - this module's fourth of four remaining independently-managed
 * {@code TenantContextHolder} call sites. Neither method catches its own
 * collaborator's exception (only {@code try/finally}), so every
 * "collaborator throws" case here must propagate.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationDispatchFinalizeServiceTenantContextSymmetryTest {

	private static final UUID TENANT_ID = UUID.randomUUID();

	private static final UUID OUTBOX_ID = UUID.randomUUID();

	private static final UUID RECIPIENT_USER_ID = UUID.randomUUID();

	@Mock
	private NotificationOutboxRepository notificationOutboxRepository;

	@Mock
	private InAppNotificationRepository inAppNotificationRepository;

	private NotificationDispatchFinalizeService service;

	@AfterEach
	void clearTenantContextRegardlessOfTestOutcome() {
		TenantContextHolder.clear();
	}

	@Test
	void markSentClearsTenantContextAfterANormalSuccessfulReturn() {
		service = new NotificationDispatchFinalizeService(notificationOutboxRepository, inAppNotificationRepository);
		when(notificationOutboxRepository.findById(OUTBOX_ID)).thenReturn(Optional.of(sendingRow()));

		service.markSent(OUTBOX_ID, TENANT_ID, RECIPIENT_USER_ID, "Subject", "Body");

		assertThat(TenantContextHolder.isSet()).isFalse();
	}

	@Test
	void markSentClearsTenantContextEvenWhenTheOutboxSaveThrows() {
		service = new NotificationDispatchFinalizeService(notificationOutboxRepository, inAppNotificationRepository);
		NotificationOutbox row = sendingRow();
		when(notificationOutboxRepository.findById(OUTBOX_ID)).thenReturn(Optional.of(row));
		doThrow(new RuntimeException("Simulated save failure")).when(notificationOutboxRepository).save(row);

		assertThatThrownBy(() -> service.markSent(OUTBOX_ID, TENANT_ID, RECIPIENT_USER_ID, "Subject", "Body"))
			.isInstanceOf(RuntimeException.class);

		assertThat(TenantContextHolder.isSet()).isFalse();
	}

	@Test
	void markSentClearsTenantContextEvenWhenTheClaimedRowIsUnexpectedlyMissing() {
		service = new NotificationDispatchFinalizeService(notificationOutboxRepository, inAppNotificationRepository);
		when(notificationOutboxRepository.findById(OUTBOX_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.markSent(OUTBOX_ID, TENANT_ID, RECIPIENT_USER_ID, "Subject", "Body"))
			.isInstanceOf(IllegalStateException.class);

		assertThat(TenantContextHolder.isSet()).isFalse();
	}

	@Test
	void markFailedClearsTenantContextAfterANormalSuccessfulReturn() {
		service = new NotificationDispatchFinalizeService(notificationOutboxRepository, inAppNotificationRepository);
		when(notificationOutboxRepository.findById(OUTBOX_ID)).thenReturn(Optional.of(sendingRow()));

		service.markFailed(OUTBOX_ID, TENANT_ID);

		assertThat(TenantContextHolder.isSet()).isFalse();
	}

	@Test
	void markFailedClearsTenantContextEvenWhenTheOutboxSaveThrows() {
		service = new NotificationDispatchFinalizeService(notificationOutboxRepository, inAppNotificationRepository);
		NotificationOutbox row = sendingRow();
		when(notificationOutboxRepository.findById(OUTBOX_ID)).thenReturn(Optional.of(row));
		doThrow(new RuntimeException("Simulated save failure")).when(notificationOutboxRepository).save(row);

		assertThatThrownBy(() -> service.markFailed(OUTBOX_ID, TENANT_ID)).isInstanceOf(RuntimeException.class);

		assertThat(TenantContextHolder.isSet()).isFalse();
	}

	private static NotificationOutbox sendingRow() {
		NotificationOutbox row = new NotificationOutbox(TENANT_ID, NotificationEventType.PAYMENT_CONFIRMED,
				RECIPIENT_USER_ID, "{\"amount\":\"10.00\"}", Instant.now());
		ReflectionTestUtils.setField(row, "id", OUTBOX_ID);
		row.markSending(Instant.now());
		return row;
	}

}
