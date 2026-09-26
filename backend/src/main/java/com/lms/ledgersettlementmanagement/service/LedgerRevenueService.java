package com.lms.ledgersettlementmanagement.service;

import com.lms.ledgersettlementmanagement.api.LedgerRevenueApi;
import com.lms.ledgersettlementmanagement.api.LedgerRevenueEntry;
import com.lms.ledgersettlementmanagement.domain.LedgerEntry;
import com.lms.ledgersettlementmanagement.domain.LedgerEntryType;
import com.lms.ledgersettlementmanagement.repository.LedgerEntryRepository;
import com.lms.paymentmanagement.api.OrderPaymentDetail;
import com.lms.paymentmanagement.api.PaymentStatusApi;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Wave 7 (§4) - implements {@link LedgerRevenueApi}. Reads ledger rows through
 * the tenant-scoped {@link LedgerEntryRepository#findAllCreatedBetween} and
 * resolves {@code orderId -> courseId} with ONE batched {@link
 * PaymentStatusApi#findOrderPaymentDetails} call, mirroring {@code
 * LedgerViewEnrichmentService}'s batching discipline. Read-only: this class
 * never writes, updates, or deletes a ledger row.
 */
@Service
@Transactional(readOnly = true)
public class LedgerRevenueService implements LedgerRevenueApi {

	private final LedgerEntryRepository ledgerEntryRepository;

	private final PaymentStatusApi paymentStatusApi;

	public LedgerRevenueService(LedgerEntryRepository ledgerEntryRepository, PaymentStatusApi paymentStatusApi) {
		this.ledgerEntryRepository = ledgerEntryRepository;
		this.paymentStatusApi = paymentStatusApi;
	}

	@Override
	public List<LedgerRevenueEntry> findRevenueEntries(Instant fromInclusive, Instant toExclusive) {
		if (!fromInclusive.isBefore(toExclusive)) {
			return List.of();
		}
		List<LedgerEntry> entries = ledgerEntryRepository.findAllCreatedBetween(fromInclusive, toExclusive);
		if (entries.isEmpty()) {
			return List.of();
		}
		List<UUID> orderIds = entries.stream().map(LedgerEntry::getOrderId).distinct().toList();
		Map<UUID, UUID> courseIdByOrderId = new HashMap<>();
		for (OrderPaymentDetail detail : paymentStatusApi.findOrderPaymentDetails(orderIds)) {
			courseIdByOrderId.put(detail.orderId(), detail.courseId());
		}
		return entries.stream()
			.map(entry -> new LedgerRevenueEntry(entry.getId(), entry.getOrderId(),
					entry.getEntryType() == LedgerEntryType.REFUND, entry.getAmount(), entry.getCreatedAt(),
					courseIdByOrderId.get(entry.getOrderId())))
			.toList();
	}

}
