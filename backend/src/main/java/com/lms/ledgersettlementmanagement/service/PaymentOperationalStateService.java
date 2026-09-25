package com.lms.ledgersettlementmanagement.service;

import com.lms.ledgersettlementmanagement.api.PaymentOperationalState;
import com.lms.ledgersettlementmanagement.domain.LedgerEntry;
import com.lms.ledgersettlementmanagement.domain.LedgerEntryType;
import com.lms.ledgersettlementmanagement.repository.LedgerEntryRepository;
import com.lms.paymentmanagement.api.OrderPaymentDetail;
import com.lms.paymentmanagement.api.OrderSlipDetail;
import com.lms.paymentmanagement.api.PaymentStatusApi;
import com.lms.paymentmanagement.api.SlipStatusApi;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Wave 6 §1.3/§3.2: the computed {@code PaymentOperationalState} projection
 * service - batches the three data sources {@link
 * PaymentOperationalStateResolver} needs per order (this module's own {@code
 * ledger_entry} rows, plus {@code payment-management}'s {@link
 * OrderPaymentDetail}/{@link OrderSlipDetail} batched reads via its {@code
 * api} package - never that module's repositories/entities directly, per
 * {@code .claude/rules/architecture.md}) into one round trip per source,
 * never one call per order (an N+1 across both the local repository and the
 * cross-module {@code api} calls). Computed per-request, never cached or
 * persisted (plan §10 judgment call 3) - acceptable at current data volumes,
 * the same tradeoff {@link LedgerQueryService#getDashboard} already makes.
 */
@Service
@Transactional(readOnly = true)
public class PaymentOperationalStateService {

	private final LedgerEntryRepository ledgerEntryRepository;

	private final PaymentStatusApi paymentStatusApi;

	private final SlipStatusApi slipStatusApi;

	public PaymentOperationalStateService(LedgerEntryRepository ledgerEntryRepository,
			PaymentStatusApi paymentStatusApi, SlipStatusApi slipStatusApi) {
		this.ledgerEntryRepository = ledgerEntryRepository;
		this.paymentStatusApi = paymentStatusApi;
		this.slipStatusApi = slipStatusApi;
	}

	/**
	 * @return one entry per id in {@code orderIds} that resolves to a real
	 * order in the current tenant context (via {@link
	 * PaymentStatusApi#findOrderPaymentDetails}) - a nonexistent/cross-tenant
	 * order id is simply absent, mirroring that method's own contract.
	 */
	public Map<UUID, PaymentOperationalState> resolveForOrders(List<UUID> orderIds) {
		if (orderIds.isEmpty()) {
			return Map.of();
		}

		Map<UUID, BigDecimal> confirmedTotalsByOrderId = new HashMap<>();
		Map<UUID, BigDecimal> refundedTotalsByOrderId = new HashMap<>();
		for (LedgerEntry entry : ledgerEntryRepository.findAllByOrderIdIn(orderIds)) {
			if (entry.getEntryType() == LedgerEntryType.PAYMENT_CONFIRMED) {
				confirmedTotalsByOrderId.merge(entry.getOrderId(), entry.getAmount(), BigDecimal::add);
			}
			else if (entry.getEntryType() == LedgerEntryType.REFUND) {
				// LedgerEntryService's own sign convention stores REFUND
				// amounts negative - negate back to a positive magnitude
				// for the resolver's contract (see its javadoc).
				refundedTotalsByOrderId.merge(entry.getOrderId(), entry.getAmount().negate(), BigDecimal::add);
			}
		}

		Map<UUID, OrderPaymentDetail> paymentDetailsByOrderId = new HashMap<>();
		for (OrderPaymentDetail detail : paymentStatusApi.findOrderPaymentDetails(orderIds)) {
			paymentDetailsByOrderId.put(detail.orderId(), detail);
		}

		Map<UUID, OrderSlipDetail> slipDetailsByOrderId = new HashMap<>();
		for (OrderSlipDetail detail : slipStatusApi.findOrderSlipDetails(orderIds)) {
			slipDetailsByOrderId.put(detail.orderId(), detail);
		}

		Map<UUID, PaymentOperationalState> results = new HashMap<>();
		for (OrderPaymentDetail paymentDetail : paymentDetailsByOrderId.values()) {
			UUID orderId = paymentDetail.orderId();
			OrderSlipDetail slipDetail = slipDetailsByOrderId.get(orderId);
			PaymentOperationalState state = PaymentOperationalStateResolver.resolve(
					confirmedTotalsByOrderId.get(orderId), refundedTotalsByOrderId.get(orderId),
					paymentDetail.hasPendingPayment(), paymentDetail.hasRejectedPayment(),
					slipDetail != null && slipDetail.hasOpenSlip(), slipDetail != null && slipDetail.hasRejectedSlip());
			results.put(orderId, state);
		}
		return results;
	}

}
