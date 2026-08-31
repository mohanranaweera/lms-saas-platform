package com.lms.paymentmanagement.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lms.common.persistence.BaseEntity;
import com.lms.common.tenant.TenantContextHolder;
import com.lms.enrollmentmanagement.api.EnrollmentActivationApi;
import com.lms.ledgersettlementmanagement.api.LedgerEntryApi;
import com.lms.paymentmanagement.api.PaymentConfirmedEvent;
import com.lms.paymentmanagement.api.PaymentRejectedEvent;
import com.lms.paymentmanagement.order.domain.StudentOrder;
import com.lms.paymentmanagement.order.repository.StudentOrderRepository;
import com.lms.paymentmanagement.payment.domain.Payment;
import com.lms.paymentmanagement.payment.repository.PaymentRepository;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Mockito-only unit coverage for {@link PaymentConfirmationService}'s
 * mandatory idempotency short-circuit (plan §5/§9/§15): a retried/duplicate
 * webhook delivery for an already-terminal payment must never re-run the
 * ledger/enrollment side effects a second time. The real Testcontainers
 * end-to-end equivalent (real DB, real webhook signature) lives in {@code
 * PaymentAndLedgerIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class PaymentConfirmationServiceTest {

	private static final UUID TENANT_ID = UUID.randomUUID();

	private static final String GATEWAY_REFERENCE = "FAKE-ref-123";

	@Mock
	private PaymentRepository paymentRepository;

	@Mock
	private StudentOrderRepository studentOrderRepository;

	@Mock
	private LedgerEntryApi ledgerEntryApi;

	@Mock
	private EnrollmentActivationApi enrollmentActivationApi;

	@Mock
	private ApplicationEventPublisher eventPublisher;

	private PaymentConfirmationService service;

	@BeforeEach
	void setUp() {
		service = new PaymentConfirmationService(paymentRepository, studentOrderRepository, ledgerEntryApi,
				enrollmentActivationApi, eventPublisher);
	}

	@AfterEach
	void clearTenantContext() {
		TenantContextHolder.clear();
	}

	@Test
	void confirmingAnAlreadyConfirmedPaymentIsANoOpThatNeverReRunsLedgerOrEnrollmentSideEffects() {
		Payment confirmedPayment = confirmedPaymentFixture();
		when(paymentRepository.findByGatewayReferenceAcrossTenantsForUpdate(GATEWAY_REFERENCE))
			.thenReturn(Optional.of(confirmedPayment));

		service.confirmByGatewayReference(GATEWAY_REFERENCE, true);

		verify(paymentRepository, never()).save(any());
		verify(ledgerEntryApi, never()).recordPaymentConfirmed(any(), any(), any());
		verify(enrollmentActivationApi, never()).activateOrReactivateFromConfirmedPayment(any(), any(), any(), any());
		verify(eventPublisher, never()).publishEvent(any(PaymentConfirmedEvent.class));
		verify(eventPublisher, never()).publishEvent(any(PaymentRejectedEvent.class));
		verify(studentOrderRepository, never()).findById(any());
	}

	@Test
	void confirmingAnAlreadyRejectedPaymentIsALsoANoOpEvenWhenTheRetriedWebhookNowClaimsSuccess() {
		Payment rejectedPayment = paymentFixtureWithStatus(com.lms.paymentmanagement.payment.domain.PaymentStatus.REJECTED);
		when(paymentRepository.findByGatewayReferenceAcrossTenantsForUpdate(GATEWAY_REFERENCE))
			.thenReturn(Optional.of(rejectedPayment));

		service.confirmByGatewayReference(GATEWAY_REFERENCE, true);

		verify(paymentRepository, never()).save(any());
		verify(ledgerEntryApi, never()).recordPaymentConfirmed(any(), any(), any());
		verify(enrollmentActivationApi, never()).activateOrReactivateFromConfirmedPayment(any(), any(), any(), any());
	}

	@Test
	void firstConfirmationOfAPendingPaymentRunsTheFullSideEffectChainExactlyOnce() {
		UUID orderId = UUID.randomUUID();
		UUID studentId = UUID.randomUUID();
		UUID courseId = UUID.randomUUID();
		Payment pendingPayment = new Payment(TENANT_ID, orderId, new BigDecimal("100.00"), "USD");
		pendingPayment.assignGatewayReference(GATEWAY_REFERENCE);
		StudentOrder order = new StudentOrder(TENANT_ID, studentId, courseId, new BigDecimal("100.00"), "USD");
		setId(order, orderId);
		when(paymentRepository.findByGatewayReferenceAcrossTenantsForUpdate(GATEWAY_REFERENCE))
			.thenReturn(Optional.of(pendingPayment));
		when(studentOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
		when(paymentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		service.confirmByGatewayReference(GATEWAY_REFERENCE, true);

		verify(paymentRepository, times(1)).save(pendingPayment);
		verify(ledgerEntryApi, times(1)).recordPaymentConfirmed(orderId, pendingPayment.getId(),
				pendingPayment.getAmount());
		verify(enrollmentActivationApi, times(1)).activateOrReactivateFromConfirmedPayment(pendingPayment.getId(),
				orderId, studentId, courseId);
		verify(eventPublisher, times(1)).publishEvent(any(PaymentConfirmedEvent.class));
		assertThat(pendingPayment.getStatus())
			.isEqualTo(com.lms.paymentmanagement.payment.domain.PaymentStatus.CONFIRMED);
	}

	/**
	 * Bug fix (MVP-012 review): reactivation refusal - {@link
	 * EnrollmentActivationApi#reactivateFromConfirmedPayment} throwing {@link
	 * IllegalStateException} (e.g. no matching APPROVED reactivation
	 * request) - must NOT propagate out of {@code confirmByGatewayReference}
	 * and must NOT prevent the payment from reaching {@code CONFIRMED} or
	 * the ledger entry / event from being recorded/published. Before this
	 * fix, this exception was uncaught and (in the real, transactional call
	 * path) marked the whole confirmation transaction rollback-only - see
	 * {@code PaymentConfirmationReactivationRefusalIntegrationTest} for the
	 * real-transaction-boundary proof of the same property.
	 */
	@Test
	void aReactivationRefusalIsCaughtAndDoesNotPreventThePaymentFromReachingConfirmed() {
		UUID orderId = UUID.randomUUID();
		UUID studentId = UUID.randomUUID();
		UUID courseId = UUID.randomUUID();
		Payment pendingPayment = new Payment(TENANT_ID, orderId, new BigDecimal("100.00"), "USD");
		pendingPayment.assignGatewayReference(GATEWAY_REFERENCE);
		StudentOrder order = new StudentOrder(TENANT_ID, studentId, courseId, new BigDecimal("100.00"), "USD");
		setId(order, orderId);
		when(paymentRepository.findByGatewayReferenceAcrossTenantsForUpdate(GATEWAY_REFERENCE))
			.thenReturn(Optional.of(pendingPayment));
		when(studentOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
		when(paymentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
		doThrow(new IllegalStateException("no APPROVED reactivation request linked to order " + orderId))
			.when(enrollmentActivationApi)
			.activateOrReactivateFromConfirmedPayment(any(), any(), any(), any());

		assertThatCode(() -> service.confirmByGatewayReference(GATEWAY_REFERENCE, true)).doesNotThrowAnyException();

		verify(ledgerEntryApi, times(1)).recordPaymentConfirmed(orderId, pendingPayment.getId(),
				pendingPayment.getAmount());
		verify(enrollmentActivationApi, times(1)).activateOrReactivateFromConfirmedPayment(pendingPayment.getId(),
				orderId, studentId, courseId);
		verify(eventPublisher, times(1)).publishEvent(any(PaymentConfirmedEvent.class));
		assertThat(pendingPayment.getStatus())
			.isEqualTo(com.lms.paymentmanagement.payment.domain.PaymentStatus.CONFIRMED);
	}

	@Test
	void tenantContextIsClearedAfterConfirmationEvenThoughItWasNeverSetByTheCaller() {
		Payment confirmedPayment = confirmedPaymentFixture();
		when(paymentRepository.findByGatewayReferenceAcrossTenantsForUpdate(anyString())).thenReturn(Optional.of(confirmedPayment));

		service.confirmByGatewayReference(GATEWAY_REFERENCE, true);

		assertThat(TenantContextHolder.isSet()).isFalse();
	}

	private static Payment confirmedPaymentFixture() {
		return paymentFixtureWithStatus(com.lms.paymentmanagement.payment.domain.PaymentStatus.CONFIRMED);
	}

	private static void setId(BaseEntity entity, UUID id) {
		try {
			Field field = BaseEntity.class.getDeclaredField("id");
			field.setAccessible(true);
			field.set(entity, id);
		}
		catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	private static Payment paymentFixtureWithStatus(com.lms.paymentmanagement.payment.domain.PaymentStatus status) {
		Payment payment = new Payment(TENANT_ID, UUID.randomUUID(), new BigDecimal("50.00"), "USD");
		payment.assignGatewayReference(GATEWAY_REFERENCE);
		if (status == com.lms.paymentmanagement.payment.domain.PaymentStatus.CONFIRMED) {
			payment.confirm(java.time.Instant.now());
		}
		else if (status == com.lms.paymentmanagement.payment.domain.PaymentStatus.REJECTED) {
			payment.reject();
		}
		return payment;
	}

}
