package com.lms.paymentmanagement.checkout.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.lms.common.tenant.TenantContext;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.enrollmentmanagement.api.ContentCoverageApi;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.service.PermissionCheckServiceImpl;
import com.lms.integrationmanagement.api.PaymentGatewayAccountApi;
import com.lms.paymentmanagement.checkout.service.CheckoutViews.CheckoutCart;
import com.lms.paymentmanagement.checkout.service.CheckoutViews.ItemSelection;
import com.lms.paymentmanagement.discount.service.DiscountCheckoutService;
import com.lms.paymentmanagement.discount.service.DiscountClock;
import com.lms.paymentmanagement.discount.service.DiscountCodeAttemptLimiter;
import com.lms.paymentmanagement.discount.service.DiscountPricingService;
import com.lms.paymentmanagement.discount.service.DiscountRedemptionService;
import com.lms.paymentmanagement.order.repository.StudentOrderCourseChargeRepository;
import com.lms.paymentmanagement.order.repository.StudentOrderLineRepository;
import com.lms.paymentmanagement.order.repository.StudentOrderRepository;
import com.lms.paymentmanagement.payment.service.PaymentStatusApiImpl;
import com.lms.tenantmanagement.api.TenantConfigApi;
import com.lms.usermanagement.api.StudentLookupApi;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/**
 * ADR-030 / R1: the composer's staff variants re-check {@code PAYMENTS_SLIPS}
 * themselves (VIEW for offers, APPROVE for quote / order) with the REAL
 * permission matrix - a TEACHER, TEACHER_ASSISTANT, STUDENT or view-only role
 * never reaches any read or write.
 */
class CheckoutComposerStaffPermissionTest {

	private final CourseLookupApi courseLookupApi = mock(CourseLookupApi.class);

	private final ContentCoverageApi contentCoverageApi = mock(ContentCoverageApi.class);

	private final StudentOrderRepository orderRepository = mock(StudentOrderRepository.class);

	private final StudentOrderLineRepository lineRepository = mock(StudentOrderLineRepository.class);

	private CheckoutComposerService service;

	@BeforeEach
	void setUp() {
		service = new CheckoutComposerService(courseLookupApi, contentCoverageApi, mock(PaymentStatusApiImpl.class),
				mock(TenantConfigApi.class), mock(PaymentGatewayAccountApi.class), orderRepository, lineRepository,
				mock(TenantContext.class), mock(StudentOrderCourseChargeRepository.class), mock(StudentLookupApi.class),
				mock(DiscountCheckoutService.class), mock(DiscountPricingService.class),
				mock(DiscountRedemptionService.class), mock(DiscountClock.class),
				mock(DiscountCodeAttemptLimiter.class), new PermissionCheckServiceImpl(),
				mock(BankTransferPolicy.class));
	}

	@AfterEach
	void clear() {
		AuthenticatedPrincipalHolder.clear();
	}

	private static void as(String role) {
		AuthenticatedPrincipalHolder
			.set(new AuthenticatedPrincipal(UUID.randomUUID(), UUID.randomUUID(), role, UUID.randomUUID()));
	}

	@Test
	void rolesWithoutPaymentsSlipsCannotUseTheStaffVariants() {
		UUID student = UUID.randomUUID();
		UUID course = UUID.randomUUID();
		CheckoutCart cart = new CheckoutCart(null,
				List.of(new ItemSelection("MONTHLY", 2026, 8, null, null, course)), List.of());
		for (String role : List.of("TEACHER", "TEACHER_ASSISTANT", "STUDENT", "COURSE_COORDINATOR")) {
			as(role);
			assertThatThrownBy(() -> service.offersForStudent(student, course, "MONTHLY", null)).as(role)
				.isInstanceOf(AccessDeniedException.class);
			assertThatThrownBy(() -> service.quoteForStudent(student, cart)).as(role)
				.isInstanceOf(AccessDeniedException.class);
			assertThatThrownBy(() -> service.createOrderForStudent(student, cart, "t", UUID.randomUUID())).as(role)
				.isInstanceOf(AccessDeniedException.class);
		}
		verifyNoInteractions(courseLookupApi, contentCoverageApi, orderRepository, lineRepository);
	}

	@Test
	void viewOnlyRolesMayNotQuoteOrPlaceAStaffOrder() {
		UUID student = UUID.randomUUID();
		CheckoutCart cart = new CheckoutCart(null, List.of(), List.of());
		for (String role : List.of("STUDENT_SUPPORT", "READ_ONLY_AUDITOR")) {
			as(role);
			assertThatThrownBy(() -> service.quoteForStudent(student, cart)).as(role)
				.isInstanceOf(AccessDeniedException.class);
			assertThatThrownBy(() -> service.createOrderForStudent(student, cart, "t", UUID.randomUUID())).as(role)
				.isInstanceOf(AccessDeniedException.class);
		}
		verifyNoInteractions(courseLookupApi, contentCoverageApi, orderRepository, lineRepository);
	}

}
