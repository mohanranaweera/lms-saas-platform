package com.lms.notificationmanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.lms.common.tenant.TenantContextHolder;
import com.lms.notificationmanagement.domain.NotificationDispatchStatus;
import com.lms.notificationmanagement.domain.NotificationEventType;
import com.lms.notificationmanagement.domain.NotificationOutbox;
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
 * NotificationDispatchClaimService#claim(UUID)} - proves its independent
 * {@code TenantContextHolder} try/finally is symmetric, both on a normal
 * return and when the {@code save} collaborator throws mid-method. Same
 * technique/rigor as {@code
 * NotificationDispatchServiceTenantContextSymmetryTest} (see that class's
 * javadoc) - this module's third of four remaining independently-managed
 * {@code TenantContextHolder} call sites. {@code claim} never catches its
 * own collaborator's exception (only {@code try/finally}, no {@code catch}),
 * so a "collaborator throws" case here must propagate, exactly like {@code
 * NotificationOutboxServiceTenantContextSymmetryTest}'s listener methods.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationDispatchClaimServiceTenantContextSymmetryTest {

	private static final UUID TENANT_ID = UUID.randomUUID();

	private static final UUID OUTBOX_ID = UUID.randomUUID();

	private static final UUID RECIPIENT_USER_ID = UUID.randomUUID();

	@Mock
	private NotificationOutboxRepository notificationOutboxRepository;

	private NotificationDispatchClaimService service;

	@AfterEach
	void clearTenantContextRegardlessOfTestOutcome() {
		TenantContextHolder.clear();
	}

	@Test
	void aSuccessfulClaimClearsTenantContextAfterANormalReturnAndFlipsTheRowToSending() {
		service = new NotificationDispatchClaimService(notificationOutboxRepository);
		NotificationOutbox row = pendingRow();
		when(notificationOutboxRepository.claimPendingByIdAcrossTenantsForUpdateSkipLocked(OUTBOX_ID))
			.thenReturn(Optional.of(row));

		Optional<NotificationOutbox> claimed = service.claim(OUTBOX_ID);

		assertThat(claimed).isPresent();
		assertThat(claimed.get().getStatus()).isEqualTo(NotificationDispatchStatus.SENDING);
		assertThat(TenantContextHolder.isSet()).isFalse();
	}

	@Test
	void aFailedSaveDuringClaimClearsTenantContextEvenThoughTheExceptionPropagates() {
		service = new NotificationDispatchClaimService(notificationOutboxRepository);
		NotificationOutbox row = pendingRow();
		when(notificationOutboxRepository.claimPendingByIdAcrossTenantsForUpdateSkipLocked(OUTBOX_ID))
			.thenReturn(Optional.of(row));
		doThrow(new RuntimeException("Simulated save failure")).when(notificationOutboxRepository).save(row);

		assertThatThrownBy(() -> service.claim(OUTBOX_ID)).isInstanceOf(RuntimeException.class);

		assertThat(TenantContextHolder.isSet()).isFalse();
	}

	@Test
	void anUnclaimableRowIsACompleteNoOpThatNeverTouchesTenantContext() {
		service = new NotificationDispatchClaimService(notificationOutboxRepository);
		when(notificationOutboxRepository.claimPendingByIdAcrossTenantsForUpdateSkipLocked(OUTBOX_ID))
			.thenReturn(Optional.empty());

		Optional<NotificationOutbox> claimed = service.claim(OUTBOX_ID);

		assertThat(claimed).isEmpty();
		assertThat(TenantContextHolder.isSet()).isFalse();
	}

	private static NotificationOutbox pendingRow() {
		NotificationOutbox row = new NotificationOutbox(TENANT_ID, NotificationEventType.PAYMENT_CONFIRMED,
				RECIPIENT_USER_ID, "{\"amount\":\"10.00\"}", Instant.now());
		ReflectionTestUtils.setField(row, "id", OUTBOX_ID);
		return row;
	}

}
