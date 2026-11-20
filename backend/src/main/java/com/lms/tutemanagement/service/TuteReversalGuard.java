package com.lms.tutemanagement.service;

import com.lms.common.tenant.TenantContext;
import com.lms.paymentmanagement.api.PaymentRefundReportApi;
import com.lms.tutemanagement.domain.FulfillmentSource;
import com.lms.tutemanagement.domain.FulfillmentStatus;
import com.lms.tutemanagement.domain.TuteFulfillment;
import com.lms.tutemanagement.repository.TuteFulfillmentRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * TUTE-01 security review M1: before labels are prepared or a dispatch is confirmed, every
 * PAYMENT-sourced copy in the selection is checked against the payment's refund facts (staff
 * refunds AND chargebacks - the latter publish no event). A reversed copy that is not flagged yet
 * gets the {@code payment_reversed_at} hold in its OWN transaction ({@code REQUIRES_NEW}), so the
 * hold persists even though the caller then fails the request with 409 {@code
 * TUTE_SELECTION_STALE}. Read-only against payment-management; nothing there is changed.
 */
@Component
public class TuteReversalGuard {

	private static final Logger log = LoggerFactory.getLogger(TuteReversalGuard.class);

	private final TuteFulfillmentRepository fulfillmentRepository;

	private final PaymentRefundReportApi refundApi;

	private final TuteStock stock;

	private final TuteSupport support;

	private final TenantContext tenantContext;

	private final Clock clock;

	public TuteReversalGuard(TuteFulfillmentRepository fulfillmentRepository, PaymentRefundReportApi refundApi,
			TuteStock stock, TuteSupport support, TenantContext tenantContext, Clock clock) {
		this.fulfillmentRepository = fulfillmentRepository;
		this.refundApi = refundApi;
		this.stock = stock;
		this.support = support;
		this.tenantContext = tenantContext;
		this.clock = clock;
	}

	/**
	 * Holds every not-yet-flagged, non-cancelled PAYMENT copy among {@code fulfillmentIds} whose
	 * source payment has any refund / chargeback. Commits independently of the caller.
	 * @return the ids that were reversed (newly held); empty when the selection is clean.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public List<UUID> holdReversed(Collection<UUID> fulfillmentIds, UUID actorId) {
		if (fulfillmentIds == null || fulfillmentIds.isEmpty()) {
			return List.of();
		}
		List<TuteFulfillment> candidates = fulfillmentRepository.findAllById(List.copyOf(Set.copyOf(fulfillmentIds)))
			.stream()
			.filter(row -> row.getSource() == FulfillmentSource.PAYMENT && row.getSourcePaymentId() != null
					&& row.getStatus() != FulfillmentStatus.CANCELLED && row.getPaymentReversedAt() == null)
			.sorted(Comparator.comparing(TuteFulfillment::getId))
			.toList();
		if (candidates.isEmpty()) {
			return List.of();
		}
		Set<UUID> reversedPayments = refundApi
			.findRefundedAmountsByPaymentIds(candidates.stream().map(TuteFulfillment::getSourcePaymentId).distinct().toList())
			.entrySet()
			.stream()
			.filter(entry -> entry.getValue() != null && entry.getValue().compareTo(BigDecimal.ZERO) > 0)
			.map(Map.Entry::getKey)
			.collect(Collectors.toSet());
		if (reversedPayments.isEmpty()) {
			return List.of();
		}
		Instant now = clock.instant();
		List<UUID> held = new ArrayList<>();
		for (TuteFulfillment row : candidates) {
			if (!reversedPayments.contains(row.getSourcePaymentId())) {
				continue;
			}
			stock.lock(row);
			if (row.markPaymentReversed(now)) {
				fulfillmentRepository.save(row);
				held.add(row.getId());
				Map<String, Object> metadata = new LinkedHashMap<>();
				metadata.put("fulfillmentId", row.getId().toString());
				metadata.put("studentId", row.getStudentId().toString());
				metadata.put("paymentId", row.getSourcePaymentId().toString());
				metadata.put("status", row.getStatus().name());
				metadata.put("detectedBy", "DISPATCH_CHECK");
				support.audit(actorId, "tute.payment_reversed_hold", "tute", row.getTuteId(), null, metadata);
			}
		}
		fulfillmentRepository.flush();
		if (!held.isEmpty()) {
			log.atWarn()
				.setMessage("tute.dispatch.reversed_payment_held")
				.addKeyValue("tuteTenantId", tenantContext.getTenantId())
				.addKeyValue("held", held.size())
				.log();
		}
		return held;
	}

}
