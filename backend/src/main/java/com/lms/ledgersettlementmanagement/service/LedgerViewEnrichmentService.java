package com.lms.ledgersettlementmanagement.service;

import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.coursemanagement.api.CourseSummary;
import com.lms.ledgersettlementmanagement.api.LedgerHistoryEntryView;
import com.lms.ledgersettlementmanagement.api.PaymentMethod;
import com.lms.ledgersettlementmanagement.api.PaymentOperationalState;
import com.lms.paymentmanagement.api.OrderPaymentDetail;
import com.lms.paymentmanagement.api.OrderSlipDetail;
import com.lms.paymentmanagement.api.PaymentStatusApi;
import com.lms.paymentmanagement.api.SlipStatusApi;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Wave 6 §4: resolves {@code courseId}/{@code courseTitle}/{@code
 * billingPeriodId}/{@code operationalState}/{@code method}/{@code
 * reference} for a batch of {@link LedgerHistoryEntryView} rows in ONE
 * round trip per data source (never one cross-module call per row - an
 * N+1 across the {@code payment-management}/{@code course-management}
 * module boundary), mirroring {@code SlipReviewService#loadOrdersById}'s
 * established batching discipline.
 *
 * <p>{@code method}/{@code reference} are derived from the order's
 * CONFIRMED payment's {@code gatewayReference} prefix - see {@link
 * com.lms.ledgersettlementmanagement.api.PaymentMethod}'s javadoc for the
 * convention and the judgment call it documents. A ledger row's own {@code
 * paymentId} is always that same CONFIRMED payment (PAY-2/PAY-3's "at most
 * one CONFIRMED payment per order" invariant - a {@code REFUND} entry's
 * {@code paymentId} is the ORIGINAL payment being refunded, per {@code
 * LedgerEntryService#recordRefund}), so no per-row extra lookup is needed
 * beyond the batched {@link OrderPaymentDetail} read.
 */
@Service
@Transactional(readOnly = true)
public class LedgerViewEnrichmentService {

	private static final String FREE_PREFIX = "FREE-";

	private static final String STAFF_GRANTED_PREFIX = "STAFF_GRANTED-";

	private static final String SLIP_PREFIX = "SLIP-";

	private final PaymentStatusApi paymentStatusApi;

	private final SlipStatusApi slipStatusApi;

	private final CourseLookupApi courseLookupApi;

	private final PaymentOperationalStateService paymentOperationalStateService;

	public LedgerViewEnrichmentService(PaymentStatusApi paymentStatusApi, SlipStatusApi slipStatusApi,
			CourseLookupApi courseLookupApi, PaymentOperationalStateService paymentOperationalStateService) {
		this.paymentStatusApi = paymentStatusApi;
		this.slipStatusApi = slipStatusApi;
		this.courseLookupApi = courseLookupApi;
		this.paymentOperationalStateService = paymentOperationalStateService;
	}

	public List<LedgerHistoryEntryView> enrich(List<LedgerHistoryEntryView> rawViews) {
		if (rawViews.isEmpty()) {
			return rawViews;
		}
		List<UUID> orderIds = rawViews.stream().map(LedgerHistoryEntryView::orderId).distinct().toList();

		Map<UUID, OrderPaymentDetail> paymentDetailsByOrderId = new HashMap<>();
		for (OrderPaymentDetail detail : paymentStatusApi.findOrderPaymentDetails(orderIds)) {
			paymentDetailsByOrderId.put(detail.orderId(), detail);
		}

		Map<UUID, OrderSlipDetail> slipDetailsByOrderId = new HashMap<>();
		for (OrderSlipDetail detail : slipStatusApi.findOrderSlipDetails(orderIds)) {
			slipDetailsByOrderId.put(detail.orderId(), detail);
		}

		Set<UUID> courseIds = paymentDetailsByOrderId.values()
			.stream()
			.map(OrderPaymentDetail::courseId)
			.collect(Collectors.toSet());
		Map<UUID, String> courseTitlesByCourseId = courseLookupApi.getCourseSummaries(courseIds)
			.stream()
			.collect(Collectors.toMap(CourseSummary::id, CourseSummary::name));

		Map<UUID, PaymentOperationalState> operationalStatesByOrderId = paymentOperationalStateService
			.resolveForOrders(orderIds);

		return rawViews.stream().map(view -> {
			OrderPaymentDetail paymentDetail = paymentDetailsByOrderId.get(view.orderId());
			if (paymentDetail == null) {
				// Defensive - a ledger row whose order no longer resolves
				// (should be structurally unreachable, composite FK-backed)
				// is returned unenriched rather than throwing, so one
				// unexpected row never breaks an entire dashboard page.
				return view;
			}
			OrderSlipDetail slipDetail = slipDetailsByOrderId.get(view.orderId());
			PaymentMethod method = deriveMethod(paymentDetail.confirmedGatewayReference());
			String reference = (method == PaymentMethod.MANUAL_SLIP && slipDetail != null)
					? slipDetail.approvedReferenceNumber() : paymentDetail.confirmedGatewayReference();
			return view.withEnrichment(paymentDetail.courseId(), courseTitlesByCourseId.get(paymentDetail.courseId()),
					paymentDetail.billingPeriodId(), operationalStatesByOrderId.get(view.orderId()), method,
					reference);
		}).toList();
	}

	private static PaymentMethod deriveMethod(String gatewayReference) {
		if (gatewayReference == null) {
			return null;
		}
		if (gatewayReference.startsWith(FREE_PREFIX)) {
			return PaymentMethod.FREE;
		}
		if (gatewayReference.startsWith(STAFF_GRANTED_PREFIX)) {
			return PaymentMethod.STAFF_GRANTED;
		}
		if (gatewayReference.startsWith(SLIP_PREFIX)) {
			return PaymentMethod.MANUAL_SLIP;
		}
		return PaymentMethod.GATEWAY;
	}

}
