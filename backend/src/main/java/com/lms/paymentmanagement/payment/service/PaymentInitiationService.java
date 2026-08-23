package com.lms.paymentmanagement.payment.service;

import com.lms.integrationmanagement.api.PaymentGatewayApi;
import com.lms.integrationmanagement.api.PaymentGatewayInitiation;
import com.lms.paymentmanagement.order.domain.StudentOrder;
import com.lms.paymentmanagement.order.service.OrderService;
import com.lms.paymentmanagement.payment.domain.Payment;
import com.lms.paymentmanagement.payment.domain.PaymentStatus;
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

	public PaymentInitiationService(OrderService orderService, PaymentWriteService paymentWriteService,
			PaymentGatewayApi paymentGatewayApi) {
		this.orderService = orderService;
		this.paymentWriteService = paymentWriteService;
		this.paymentGatewayApi = paymentGatewayApi;
	}

	public PaymentInitiationView initiatePayment(UUID orderId) {
		// Read-only, tenant/owner-checked, its own short transaction.
		StudentOrder order = orderService.loadOrderOwnedByCurrentStudent(orderId);

		// Transaction 1: persist PENDING, commit.
		Payment pending = paymentWriteService.createPendingPayment(order.getId());

		// No open transaction here - the (fake, but architecturally-treated
		// -as-external) gateway call.
		PaymentGatewayInitiation initiation = paymentGatewayApi.initiatePayment(order.getId(), order.getTenantId(),
				order.getAmount(), order.getCurrency());

		// Transaction 2: persist the returned reference, commit.
		paymentWriteService.assignGatewayReference(pending.getId(), initiation.reference());

		return new PaymentInitiationView(pending.getId(), order.getId(), PaymentStatus.PENDING, initiation.reference(),
				initiation.redirectTarget());
	}

}
