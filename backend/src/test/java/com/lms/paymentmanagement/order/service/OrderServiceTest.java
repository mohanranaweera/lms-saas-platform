package com.lms.paymentmanagement.order.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.lms.common.error.ConflictException;
import com.lms.common.persistence.BaseEntity;
import com.lms.common.tenant.TenantContext;
import com.lms.coursemanagement.api.CheckoutAmount;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.enrollmentmanagement.api.EnrollmentAccessApi;
import com.lms.enrollmentmanagement.api.EnrollmentAccessState;
import com.lms.enrollmentmanagement.api.EnrollmentActivationApi;
import com.lms.enrollmentmanagement.api.ReactivationLinkingApi;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.ledgersettlementmanagement.api.LedgerEntryApi;
import com.lms.paymentmanagement.api.PaymentConfirmedEvent;
import com.lms.paymentmanagement.order.domain.StudentOrder;
import com.lms.paymentmanagement.order.repository.StudentOrderRepository;
import com.lms.paymentmanagement.payment.domain.Payment;
import com.lms.paymentmanagement.payment.repository.PaymentRepository;
import com.lms.paymentmanagement.support.PaymentDomainAccessGuard;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;

/**
 * Mockito-only unit coverage for {@link OrderService#createOrder}'s
 * reactivation-order-creation gate (MVP-012/ADR-013 §9), extended for the bug
 * fix described in that method's inline comment (MVP-012 review, Bug-2 part
 * (b)): {@link ReactivationLinkingApi#linkApprovedRequestToNewOrder} losing
 * its concurrency race throws {@link IllegalStateException} - before this
 * fix, that exception was uncaught here and fell through to {@code
 * GlobalExceptionHandler}'s generic {@code 500} fallback; this proves it is
 * now mapped to a clean {@link ConflictException} (409).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderServiceTest {

	private static final UUID TENANT_ID = UUID.randomUUID();

	private static final UUID STUDENT_ID = UUID.randomUUID();

	private static final UUID COURSE_ID = UUID.randomUUID();

	@Mock
	private StudentOrderRepository studentOrderRepository;

	@Mock
	private PaymentRepository paymentRepository;

	@Mock
	private CourseLookupApi courseLookupApi;

	@Mock
	private TenantContext tenantContext;

	@Mock
	private PaymentDomainAccessGuard accessGuard;

	@Mock
	private EnrollmentAccessApi enrollmentAccessApi;

	@Mock
	private EnrollmentActivationApi enrollmentActivationApi;

	@Mock
	private ReactivationLinkingApi reactivationLinkingApi;

	@Mock
	private PermissionCheckService permissionCheckService;

	@Mock
	private ApplicationEventPublisher eventPublisher;

	@Mock
	private LedgerEntryApi ledgerEntryApi;

	private OrderService service;

	@BeforeEach
	void setUp() {
		service = new OrderService(studentOrderRepository, paymentRepository, courseLookupApi, tenantContext,
				accessGuard, enrollmentAccessApi, enrollmentActivationApi, reactivationLinkingApi,
				permissionCheckService, eventPublisher, ledgerEntryApi);
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
		when(courseLookupApi.getResolvedCheckoutAmount(COURSE_ID))
			.thenReturn(Optional.of(new CheckoutAmount(new BigDecimal("99.99"), "USD", null, false, false)));
		when(courseLookupApi.isPublished(COURSE_ID)).thenReturn(true);
		AuthenticatedPrincipalHolder
			.set(new AuthenticatedPrincipal(STUDENT_ID, TENANT_ID, "STUDENT", UUID.randomUUID()));
		when(studentOrderRepository.save(any(StudentOrder.class))).thenAnswer(inv -> inv.getArgument(0));
		when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> {
			Payment payment = inv.getArgument(0);
			if (payment.getId() == null) {
				setId(payment, UUID.randomUUID());
			}
			return payment;
		});
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

	@AfterEach
	void clearPrincipal() {
		AuthenticatedPrincipalHolder.clear();
	}

	@Test
	void createOrderMapsALostReactivationLinkingRaceToAConflictException() {
		when(enrollmentAccessApi.resolveAccessState(STUDENT_ID, COURSE_ID))
			.thenReturn(EnrollmentAccessState.expired(UUID.randomUUID(), null, false));
		when(enrollmentAccessApi.hasApprovedUnfulfilledReactivationRequest(STUDENT_ID, COURSE_ID)).thenReturn(true);
		org.mockito.Mockito
			.doThrow(new IllegalStateException("No APPROVED, unfulfilled reactivation request exists"))
			.when(reactivationLinkingApi)
			.linkApprovedRequestToNewOrder(any(), any(), any());

		assertThatThrownBy(() -> service.createOrder(COURSE_ID, null)).isInstanceOf(ConflictException.class);
	}

	@Test
	void createOrderSucceedsAndLinksWhenTheReactivationRequestIsStillAvailable() {
		when(enrollmentAccessApi.resolveAccessState(STUDENT_ID, COURSE_ID))
			.thenReturn(EnrollmentAccessState.expired(UUID.randomUUID(), null, false));
		when(enrollmentAccessApi.hasApprovedUnfulfilledReactivationRequest(STUDENT_ID, COURSE_ID)).thenReturn(true);

		assertThatCode(() -> service.createOrder(COURSE_ID, null)).doesNotThrowAnyException();

		org.mockito.Mockito.verify(reactivationLinkingApi)
			.linkApprovedRequestToNewOrder(org.mockito.ArgumentMatchers.eq(STUDENT_ID),
					org.mockito.ArgumentMatchers.eq(COURSE_ID), any());
	}

	/**
	 * MVP-012 review finding H3(a): an {@code ACTIVE} enrollment access state
	 * must reject the order outright ("already enrolled") - never reaching
	 * the reactivation branch, never even resolving {@code
	 * hasApprovedUnfulfilledReactivationRequest}, and never touching {@link
	 * ReactivationLinkingApi} or persisting an order row at all.
	 */
	@Test
	void createOrderRejectsWithConflictWhenTheStudentIsAlreadyActivelyEnrolled() {
		when(enrollmentAccessApi.resolveAccessState(STUDENT_ID, COURSE_ID))
			.thenReturn(EnrollmentAccessState.active(UUID.randomUUID(), null));

		assertThatThrownBy(() -> service.createOrder(COURSE_ID, null)).isInstanceOf(ConflictException.class)
			.hasMessageContaining("You are already enrolled in this course");

		org.mockito.Mockito.verify(enrollmentAccessApi, org.mockito.Mockito.never())
			.hasApprovedUnfulfilledReactivationRequest(any(), any());
		org.mockito.Mockito.verify(reactivationLinkingApi, org.mockito.Mockito.never())
			.linkApprovedRequestToNewOrder(any(), any(), any());
		org.mockito.Mockito.verify(studentOrderRepository, org.mockito.Mockito.never()).save(any());
	}

	/**
	 * MVP-012 review finding H3(b): an {@code EXPIRED} enrollment access
	 * state with NO approved, unfulfilled reactivation request must reject
	 * the order ("reactivation approval required") - before any order row is
	 * persisted and before {@link ReactivationLinkingApi} is ever touched.
	 */
	@Test
	void createOrderRejectsWithConflictWhenExpiredWithNoApprovedUnfulfilledReactivationRequest() {
		when(enrollmentAccessApi.resolveAccessState(STUDENT_ID, COURSE_ID))
			.thenReturn(EnrollmentAccessState.expired(UUID.randomUUID(), null, true));
		when(enrollmentAccessApi.hasApprovedUnfulfilledReactivationRequest(STUDENT_ID, COURSE_ID)).thenReturn(false);

		assertThatThrownBy(() -> service.createOrder(COURSE_ID, null)).isInstanceOf(ConflictException.class)
			.hasMessageContaining("Reactivation approval is required");

		org.mockito.Mockito.verify(reactivationLinkingApi, org.mockito.Mockito.never())
			.linkApprovedRequestToNewOrder(any(), any(), any());
		org.mockito.Mockito.verify(studentOrderRepository, org.mockito.Mockito.never()).save(any());
	}

	// ------------------------------------------------------------------
	// Wave 2: pricing-model-aware checkout amount resolution.
	// ------------------------------------------------------------------

	/**
	 * FREE checkout (Wave 2, V41/V42/ADR-015): a genuinely {@code
	 * FREE}-pricing-model course ({@link CheckoutAmount#freePricing()} {@code
	 * true}) creates the order, then creates-and-confirms a {@code $0}
	 * {@link Payment} in the same call via the real {@code PENDING ->
	 * CONFIRMED} transition (proven by the payment argument captured below
	 * genuinely reaching {@code CONFIRMED} with a non-null {@code
	 * gatewayReference}, matching {@link
	 * Payment#confirm(java.time.Instant)}'s own precondition - it would throw
	 * {@link IllegalStateException} if driven any other way), writes a {@code
	 * $0} {@code PAYMENT_CONFIRMED} ledger entry via {@link
	 * LedgerEntryApi#recordPaymentConfirmed(UUID, UUID, BigDecimal)} (V42),
	 * then calls the SAME {@link EnrollmentActivationApi
	 * #activateOrReactivateFromConfirmedPayment(UUID, UUID, UUID, UUID)} a
	 * real gateway payment uses. The end-to-end proof that this actually
	 * produces an {@code ACTIVE} enrollment (which this Mockito-only unit
	 * test cannot reach, since {@code EnrollmentActivationApi} is mocked
	 * here) lives in {@code FreeCourseCheckoutIntegrationTest}.
	 */
	@Test
	void aGenuineFreePricingModelCourseAutoConfirmsAZeroAmountPaymentWritesALedgerEntryAndActivatesEnrollment() {
		when(courseLookupApi.getResolvedCheckoutAmount(COURSE_ID))
			.thenReturn(Optional.of(new CheckoutAmount(BigDecimal.ZERO, "USD", null, false, true)));
		when(enrollmentAccessApi.resolveAccessState(STUDENT_ID, COURSE_ID))
			.thenReturn(EnrollmentAccessState.neverEnrolled());

		OrderView view = service.createOrder(COURSE_ID, null);

		org.assertj.core.api.Assertions.assertThat(view.amount()).isEqualByComparingTo("0.00");

		ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
		org.mockito.Mockito.verify(paymentRepository, org.mockito.Mockito.atLeastOnce()).save(paymentCaptor.capture());
		Payment confirmedPayment = paymentCaptor.getAllValues().get(paymentCaptor.getAllValues().size() - 1);
		org.assertj.core.api.Assertions.assertThat(confirmedPayment.getAmount()).isEqualByComparingTo("0.00");
		org.assertj.core.api.Assertions.assertThat(confirmedPayment.getStatus())
			.isEqualTo(com.lms.paymentmanagement.payment.domain.PaymentStatus.CONFIRMED);
		org.assertj.core.api.Assertions.assertThat(confirmedPayment.getGatewayReference()).isNotNull();

		org.mockito.Mockito.verify(ledgerEntryApi).recordPaymentConfirmed(view.id(), confirmedPayment.getId(),
				confirmedPayment.getAmount());
		org.mockito.Mockito.verify(enrollmentActivationApi)
			.activateOrReactivateFromConfirmedPayment(confirmedPayment.getId(), view.id(), STUDENT_ID, COURSE_ID);
		org.mockito.Mockito.verify(eventPublisher).publishEvent(org.mockito.ArgumentMatchers.any(PaymentConfirmedEvent.class));
	}

	/**
	 * A refusal from {@link EnrollmentActivationApi
	 * #activateOrReactivateFromConfirmedPayment} (e.g. a defense-in-depth
	 * re-verification edge case) must not fail the request or leave the
	 * order/payment rolled back - mirrors {@code
	 * PaymentConfirmationService}'s identical "log and continue" contract for
	 * the exact same refusal on the webhook-confirmed path.
	 */
	@Test
	void aRefusedActivationOnFreeCheckoutIsLoggedNotThrown() {
		when(courseLookupApi.getResolvedCheckoutAmount(COURSE_ID))
			.thenReturn(Optional.of(new CheckoutAmount(BigDecimal.ZERO, "USD", null, false, true)));
		when(enrollmentAccessApi.resolveAccessState(STUDENT_ID, COURSE_ID))
			.thenReturn(EnrollmentAccessState.neverEnrolled());
		org.mockito.Mockito.doThrow(new IllegalStateException("no current enrollment"))
			.when(enrollmentActivationApi)
			.activateOrReactivateFromConfirmedPayment(any(), any(), any(), any());

		assertThatCode(() -> service.createOrder(COURSE_ID, null)).doesNotThrowAnyException();
	}

	/**
	 * Fix 1 (Phase E review, ADR-015): a {@code ONE_TIME}-priced course whose
	 * {@code price} was misconfigured/mistyped down to {@code $0} must be
	 * rejected outright (409) rather than silently free-activated - {@link
	 * CheckoutAmount#freePricing()} is {@code false} here even though {@code
	 * amount} resolves to {@code $0}, since {@code pricing_model} is {@code
	 * ONE_TIME}, not {@code FREE}. No order row may be persisted, and neither
	 * the payment/ledger/enrollment side effects nor the reactivation
	 * -linking gate above may ever run for this rejected order.
	 */
	@Test
	void aZeroPricedOneTimeCourseIsRejectedAndNeverAutoActivated() {
		when(courseLookupApi.getResolvedCheckoutAmount(COURSE_ID))
			.thenReturn(Optional.of(new CheckoutAmount(BigDecimal.ZERO, "USD", null, false, false)));

		assertThatThrownBy(() -> service.createOrder(COURSE_ID, null)).isInstanceOf(ConflictException.class)
			.hasMessageContaining("not priced as FREE");

		org.mockito.Mockito.verify(studentOrderRepository, org.mockito.Mockito.never()).save(any());
		org.mockito.Mockito.verify(enrollmentAccessApi, org.mockito.Mockito.never()).resolveAccessState(any(), any());
		org.mockito.Mockito.verify(paymentRepository, org.mockito.Mockito.never()).save(any());
		org.mockito.Mockito.verify(enrollmentActivationApi, org.mockito.Mockito.never())
			.activateOrReactivateFromConfirmedPayment(any(), any(), any(), any());
		org.mockito.Mockito.verify(ledgerEntryApi, org.mockito.Mockito.never())
			.recordPaymentConfirmed(any(), any(), any());
	}

	/**
	 * Fix 1 (Phase E review, ADR-015): the same rejection applies to a {@code
	 * MONTHLY}/{@code SESSION} course whose current open billing period was
	 * misconfigured down to {@code $0} - {@code
	 * CourseLookupApiImpl#getResolvedCheckoutAmount} would resolve a real
	 * {@code billingPeriodId} here (unlike the {@code ONE_TIME} case above),
	 * but {@code freePricing} is still {@code false}, so the same
	 * misconfiguration guard applies.
	 */
	@Test
	void aZeroPricedMonthlyBillingPeriodCourseIsRejectedAndNeverAutoActivated() {
		UUID billingPeriodId = UUID.randomUUID();
		when(courseLookupApi.getResolvedCheckoutAmount(COURSE_ID))
			.thenReturn(Optional.of(new CheckoutAmount(BigDecimal.ZERO, "USD", billingPeriodId, false, false)));

		assertThatThrownBy(() -> service.createOrder(COURSE_ID, null)).isInstanceOf(ConflictException.class)
			.hasMessageContaining("not priced as FREE");

		org.mockito.Mockito.verify(studentOrderRepository, org.mockito.Mockito.never()).save(any());
		org.mockito.Mockito.verify(enrollmentActivationApi, org.mockito.Mockito.never())
			.activateOrReactivateFromConfirmedPayment(any(), any(), any(), any());
	}

	@Test
	void customAmountForANonCustomPricedCourseIsRejected() {
		when(enrollmentAccessApi.resolveAccessState(STUDENT_ID, COURSE_ID))
			.thenReturn(EnrollmentAccessState.neverEnrolled());

		assertThatThrownBy(() -> service.createOrder(COURSE_ID, new BigDecimal("50.00")))
			.isInstanceOf(ConflictException.class);

		org.mockito.Mockito.verify(studentOrderRepository, org.mockito.Mockito.never()).save(any());
	}

	@Test
	void customPricedCourseWithNoSuppliedAmountIsRejected() {
		when(courseLookupApi.getResolvedCheckoutAmount(COURSE_ID))
			.thenReturn(Optional.of(new CheckoutAmount(null, "USD", null, true, false)));
		when(enrollmentAccessApi.resolveAccessState(STUDENT_ID, COURSE_ID))
			.thenReturn(EnrollmentAccessState.neverEnrolled());

		assertThatThrownBy(() -> service.createOrder(COURSE_ID, null)).isInstanceOf(ConflictException.class);

		org.mockito.Mockito.verify(studentOrderRepository, org.mockito.Mockito.never()).save(any());
	}

	/**
	 * The caller in this test suite is always {@code STUDENT} - a role that
	 * genuinely never holds {@code DomainArea.COURSES}/{@code CREATE_EDIT} in
	 * production (see {@code OrderService}'s class javadoc). This test proves
	 * the permission GATE itself is correctly wired (denied when the mocked
	 * permission check returns {@code false}, which is the real-world outcome
	 * for every student caller), not that a student can reach this path in
	 * practice.
	 */
	@Test
	void customPricedCourseWithASuppliedAmountIsRejectedWhenCallerLacksStaffPermission() {
		when(courseLookupApi.getResolvedCheckoutAmount(COURSE_ID))
			.thenReturn(Optional.of(new CheckoutAmount(null, "USD", null, true, false)));
		when(enrollmentAccessApi.resolveAccessState(STUDENT_ID, COURSE_ID))
			.thenReturn(EnrollmentAccessState.neverEnrolled());
		when(permissionCheckService.hasPermission(DomainArea.COURSES, PermissionAction.CREATE_EDIT))
			.thenReturn(false);

		assertThatThrownBy(() -> service.createOrder(COURSE_ID, new BigDecimal("75.00")))
			.isInstanceOf(AccessDeniedException.class);

		org.mockito.Mockito.verify(studentOrderRepository, org.mockito.Mockito.never()).save(any());
	}

	@Test
	void customPricedCourseWithASuppliedAmountAndStaffPermissionSucceedsAndPublishesAnAuditEvent() {
		when(courseLookupApi.getResolvedCheckoutAmount(COURSE_ID))
			.thenReturn(Optional.of(new CheckoutAmount(null, "USD", null, true, false)));
		when(enrollmentAccessApi.resolveAccessState(STUDENT_ID, COURSE_ID))
			.thenReturn(EnrollmentAccessState.neverEnrolled());
		when(permissionCheckService.hasPermission(DomainArea.COURSES, PermissionAction.CREATE_EDIT))
			.thenReturn(true);

		OrderView view = service.createOrder(COURSE_ID, new BigDecimal("75.00"));

		org.assertj.core.api.Assertions.assertThat(view.amount()).isEqualByComparingTo("75.00");
		org.mockito.Mockito.verify(eventPublisher)
			.publishEvent(org.mockito.ArgumentMatchers
				.any(com.lms.paymentmanagement.api.OrderCustomAmountAppliedEvent.class));
	}

	@Test
	void monthlyOrSessionPricingSnapshotsTheResolvedBillingPeriodIdOntoTheOrder() {
		UUID billingPeriodId = UUID.randomUUID();
		when(courseLookupApi.getResolvedCheckoutAmount(COURSE_ID))
			.thenReturn(Optional.of(new CheckoutAmount(new BigDecimal("20.00"), "USD", billingPeriodId, false, false)));
		when(enrollmentAccessApi.resolveAccessState(STUDENT_ID, COURSE_ID))
			.thenReturn(EnrollmentAccessState.neverEnrolled());

		OrderView view = service.createOrder(COURSE_ID, null);

		org.assertj.core.api.Assertions.assertThat(view.billingPeriodId()).isEqualTo(billingPeriodId);
		org.assertj.core.api.Assertions.assertThat(view.amount()).isEqualByComparingTo("20.00");
	}

}
