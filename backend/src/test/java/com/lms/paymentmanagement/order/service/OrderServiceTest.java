package com.lms.paymentmanagement.order.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.lms.common.error.ConflictException;
import com.lms.common.tenant.TenantContext;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.enrollmentmanagement.api.EnrollmentAccessApi;
import com.lms.enrollmentmanagement.api.EnrollmentAccessState;
import com.lms.enrollmentmanagement.api.ReactivationLinkingApi;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.paymentmanagement.order.domain.StudentOrder;
import com.lms.paymentmanagement.order.repository.StudentOrderRepository;
import com.lms.paymentmanagement.payment.repository.PaymentRepository;
import com.lms.paymentmanagement.support.PaymentDomainAccessGuard;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

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
	private ReactivationLinkingApi reactivationLinkingApi;

	private OrderService service;

	@BeforeEach
	void setUp() {
		service = new OrderService(studentOrderRepository, paymentRepository, courseLookupApi, tenantContext,
				accessGuard, enrollmentAccessApi, reactivationLinkingApi);
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
		when(courseLookupApi.getCurrentPrice(COURSE_ID)).thenReturn(java.util.Optional.of(new BigDecimal("99.99")));
		when(courseLookupApi.isPublished(COURSE_ID)).thenReturn(true);
		AuthenticatedPrincipalHolder
			.set(new AuthenticatedPrincipal(STUDENT_ID, TENANT_ID, "STUDENT", UUID.randomUUID()));
		when(studentOrderRepository.save(any(StudentOrder.class))).thenAnswer(inv -> inv.getArgument(0));
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

		assertThatThrownBy(() -> service.createOrder(COURSE_ID)).isInstanceOf(ConflictException.class);
	}

	@Test
	void createOrderSucceedsAndLinksWhenTheReactivationRequestIsStillAvailable() {
		when(enrollmentAccessApi.resolveAccessState(STUDENT_ID, COURSE_ID))
			.thenReturn(EnrollmentAccessState.expired(UUID.randomUUID(), null, false));
		when(enrollmentAccessApi.hasApprovedUnfulfilledReactivationRequest(STUDENT_ID, COURSE_ID)).thenReturn(true);

		assertThatCode(() -> service.createOrder(COURSE_ID)).doesNotThrowAnyException();

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

		assertThatThrownBy(() -> service.createOrder(COURSE_ID)).isInstanceOf(ConflictException.class)
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

		assertThatThrownBy(() -> service.createOrder(COURSE_ID)).isInstanceOf(ConflictException.class)
			.hasMessageContaining("Reactivation approval is required");

		org.mockito.Mockito.verify(reactivationLinkingApi, org.mockito.Mockito.never())
			.linkApprovedRequestToNewOrder(any(), any(), any());
		org.mockito.Mockito.verify(studentOrderRepository, org.mockito.Mockito.never()).save(any());
	}

}
