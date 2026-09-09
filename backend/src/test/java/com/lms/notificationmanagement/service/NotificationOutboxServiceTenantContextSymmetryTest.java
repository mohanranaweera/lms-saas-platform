package com.lms.notificationmanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.lms.common.tenant.TenantContextHolder;
import com.lms.notificationmanagement.repository.NotificationOutboxRepository;
import com.lms.paymentmanagement.api.PaymentConfirmedEvent;
import com.lms.paymentmanagement.api.PaymentRefundedEvent;
import com.lms.paymentmanagement.api.PaymentRejectedEvent;
import com.lms.paymentmanagement.payment.domain.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.ObjectMapper;

/**
 * Deterministic, non-async, plain Mockito unit test directly against {@link
 * NotificationOutboxService}'s three {@code @TransactionalEventListener}
 * methods - proves each one's independent {@code
 * TenantContextHolder.set}/{@code clear} try/finally is symmetric, both on a
 * normal return and when a collaborator throws mid-method, exactly the same
 * technique and rigor as {@code
 * NotificationDispatchServiceTenantContextSymmetryTest} (see that class's
 * javadoc for the full rationale) - this module's second of four remaining
 * independently-managed {@code TenantContextHolder} call sites.
 *
 * <p>Unlike {@code NotificationDispatchService#dispatchOne}, none of these
 * three listener methods catches its own collaborator's exception - a
 * failure here is expected to propagate out of the {@code
 * @TransactionalEventListener} method (there is nothing further downstream
 * in the same call for it to corrupt), so each "collaborator throws" test
 * below asserts the exception DOES propagate, in addition to asserting
 * {@link TenantContextHolder} was still cleared before it did.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationOutboxServiceTenantContextSymmetryTest {

	private static final UUID TENANT_ID = UUID.randomUUID();

	private static final UUID STUDENT_ID = UUID.randomUUID();

	@Mock
	private NotificationOutboxRepository notificationOutboxRepository;

	@Mock
	private ObjectMapper objectMapper;

	private NotificationOutboxService service;

	@BeforeEach
	void setUp() {
		service = new NotificationOutboxService(notificationOutboxRepository, objectMapper);
		when(objectMapper.writeValueAsString(any())).thenReturn("{}");
	}

	@AfterEach
	void clearTenantContextRegardlessOfTestOutcome() {
		TenantContextHolder.clear();
	}

	@Test
	void onPaymentConfirmedClearsTenantContextAfterANormalSuccessfulReturn() {
		service.onPaymentConfirmed(confirmedEvent());

		assertThat(TenantContextHolder.isSet()).isFalse();
	}

	@Test
	void onPaymentConfirmedClearsTenantContextEvenWhenSerializationThrows() {
		doThrow(new RuntimeException("Simulated serialization failure")).when(objectMapper).writeValueAsString(any());

		assertThatThrownBy(() -> service.onPaymentConfirmed(confirmedEvent())).isInstanceOf(RuntimeException.class);

		assertThat(TenantContextHolder.isSet()).isFalse();
	}

	@Test
	void onPaymentRejectedClearsTenantContextAfterANormalSuccessfulReturn() {
		service.onPaymentRejected(rejectedEvent());

		assertThat(TenantContextHolder.isSet()).isFalse();
	}

	@Test
	void onPaymentRejectedClearsTenantContextEvenWhenSerializationThrows() {
		doThrow(new RuntimeException("Simulated serialization failure")).when(objectMapper).writeValueAsString(any());

		assertThatThrownBy(() -> service.onPaymentRejected(rejectedEvent())).isInstanceOf(RuntimeException.class);

		assertThat(TenantContextHolder.isSet()).isFalse();
	}

	@Test
	void onPaymentRefundedClearsTenantContextAfterANormalSuccessfulReturn() {
		service.onPaymentRefunded(refundedEvent());

		assertThat(TenantContextHolder.isSet()).isFalse();
	}

	@Test
	void onPaymentRefundedClearsTenantContextEvenWhenSerializationThrows() {
		doThrow(new RuntimeException("Simulated serialization failure")).when(objectMapper).writeValueAsString(any());

		assertThatThrownBy(() -> service.onPaymentRefunded(refundedEvent())).isInstanceOf(RuntimeException.class);

		assertThat(TenantContextHolder.isSet()).isFalse();
	}

	private static PaymentConfirmedEvent confirmedEvent() {
		return new PaymentConfirmedEvent(TENANT_ID, UUID.randomUUID(), UUID.randomUUID(), PaymentStatus.PENDING,
				PaymentStatus.CONFIRMED, Instant.now(), STUDENT_ID, new BigDecimal("10.00"), "USD");
	}

	private static PaymentRejectedEvent rejectedEvent() {
		return new PaymentRejectedEvent(TENANT_ID, UUID.randomUUID(), UUID.randomUUID(), PaymentStatus.PENDING,
				PaymentStatus.REJECTED, Instant.now(), STUDENT_ID, new BigDecimal("10.00"), "USD");
	}

	private static PaymentRefundedEvent refundedEvent() {
		return new PaymentRefundedEvent(TENANT_ID, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				new BigDecimal("5.00"), "reason", Instant.now(), STUDENT_ID);
	}

}
