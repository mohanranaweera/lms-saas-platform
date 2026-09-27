package com.lms.paymentmanagement.payment.service;

import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.coursemanagement.api.CourseSummary;
import com.lms.integrationmanagement.api.GatewayCheckoutRequest;
import com.lms.integrationmanagement.api.GatewayPayer;
import com.lms.integrationmanagement.api.PaymentGatewayApi;
import com.lms.integrationmanagement.api.PaymentGatewayInitiation;
import com.lms.paymentmanagement.order.domain.StudentOrder;
import com.lms.paymentmanagement.order.service.OrderService;
import com.lms.paymentmanagement.payment.domain.Payment;
import com.lms.paymentmanagement.payment.domain.PaymentStatus;
import com.lms.usermanagement.api.StudentContact;
import com.lms.usermanagement.api.StudentLookupApi;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Orchestrates PAY-2's payment-initiation flow (plan §9 item 2). Deliberately
 * NOT {@code @Transactional} at either the class or method level - the whole
 * point of this class is that {@link PaymentWriteService}'s two calls below
 * each open and commit their OWN short transaction (since they are a
 * different bean), with {@link PaymentGatewayApi#initiatePayment} called
 * strictly in between, outside any open transaction. If this method were
 * itself transactional, Spring's default {@code REQUIRED} propagation would
 * make both {@code PaymentWriteService} calls join this method's single
 * enclosing transaction instead, which would hold a DB transaction open
 * across the gateway call - exactly what {@code .claude/rules/backend.md}
 * forbids.
 */
@Service
public class PaymentInitiationService {

	private final OrderService orderService;

	private final PaymentWriteService paymentWriteService;

	private final PaymentGatewayApi paymentGatewayApi;

	private final CourseLookupApi courseLookupApi;

	private final StudentLookupApi studentLookupApi;

	public PaymentInitiationService(OrderService orderService, PaymentWriteService paymentWriteService,
			PaymentGatewayApi paymentGatewayApi, CourseLookupApi courseLookupApi, StudentLookupApi studentLookupApi) {
		this.orderService = orderService;
		this.paymentWriteService = paymentWriteService;
		this.paymentGatewayApi = paymentGatewayApi;
		this.courseLookupApi = courseLookupApi;
		this.studentLookupApi = studentLookupApi;
	}

	public PaymentInitiationView initiatePayment(UUID orderId) {
		return initiatePayment(orderId, null);
	}

	/**
	 * @param idempotencyKey optional client-supplied dedup key (V52) - see
	 * {@link PaymentWriteService#createPendingPayment(UUID, UUID)}'s
	 * javadoc. When {@link PaymentWriteService.PendingPaymentResult
	 * #replayed()} comes back {@code true}, this method returns the
	 * pre-existing payment's current view WITHOUT calling {@link
	 * PaymentGatewayApi#initiatePayment}/{@code assignGatewayReference} a
	 * second time - a repeated "Pay Now" click must not re-initiate a second
	 * gateway attempt for a payment that is already {@code PENDING} (or
	 * further along) against the same order/key.
	 */
	public PaymentInitiationView initiatePayment(UUID orderId, UUID idempotencyKey) {
		// Read-only, tenant/owner-checked, its own short transaction.
		StudentOrder order = orderService.loadOrderOwnedByCurrentStudent(orderId);

		// PAY-GW-01: amount/currency are the order's stored snapshot; item
		// description and payer details are read server-side for the hosted
		// checkout page - nothing here comes from the request. Validated
		// before any payment row exists (e.g. PayHere needs an address), so a
		// refused checkout never leaves an orphaned PENDING payment.
		GatewayCheckoutRequest checkout = new GatewayCheckoutRequest(order.getId(), order.getTenantId(),
				order.getAmount(), order.getCurrency(), itemDescription(order), payer(order));
		paymentGatewayApi.validateCheckout(checkout);

		// Transaction 1: persist PENDING (or replay an existing row), commit.
		PaymentWriteService.PendingPaymentResult pendingResult = paymentWriteService.createPendingPayment(
				order.getId(), idempotencyKey);
		if (pendingResult.replayed()) {
			Payment existing = pendingResult.payment();
			return new PaymentInitiationView(existing.getId(), order.getId(), existing.getStatus(),
					existing.getGatewayReference(), null);
		}
		Payment pending = pendingResult.payment();

		// No open transaction here - the gateway call.
		PaymentGatewayInitiation initiation = paymentGatewayApi.initiateCheckout(checkout);

		// Transaction 2: persist the returned reference (and, PAY-GW-01, the
		// collecting account), commit.
		paymentWriteService.assignGatewayRouting(pending.getId(), initiation.reference(),
				initiation.gatewayAccountId(), initiation.collectedBy());

		return new PaymentInitiationView(pending.getId(), order.getId(), PaymentStatus.PENDING, initiation.reference(),
				initiation.redirectTarget(), initiation.formAction(), initiation.formFields());
	}


	private String itemDescription(StudentOrder order) {
		return courseLookupApi.getCourseSummaries(Set.of(order.getCourseId()))
			.stream()
			.findFirst()
			.map(CourseSummary::name)
			.orElse("Course enrollment");
	}

	private GatewayPayer payer(StudentOrder order) {
		return studentLookupApi.findContact(order.getStudentId()).map(PaymentInitiationService::toPayer).orElse(null);
	}

	private static GatewayPayer toPayer(StudentContact contact) {
		String name = (contact.name() == null) ? "" : contact.name().trim();
		int split = name.indexOf(' ');
		String first = (split > 0) ? name.substring(0, split) : name;
		String last = (split > 0) ? name.substring(split + 1).trim() : null;
		return new GatewayPayer(first.isEmpty() ? null : first, last, contact.email(), contact.mobile(),
				contact.addressLine(), contact.city());
	}

}
