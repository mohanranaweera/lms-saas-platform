package com.lms.ledgersettlementmanagement.service;

import com.lms.common.tenant.TenantContext;
import com.lms.ledgersettlementmanagement.api.LedgerEntryApi;
import com.lms.ledgersettlementmanagement.api.LedgerHistoryEntryView;
import com.lms.ledgersettlementmanagement.domain.LedgerEntry;
import com.lms.ledgersettlementmanagement.domain.LedgerEntryType;
import com.lms.ledgersettlementmanagement.repository.LedgerEntryRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements {@link LedgerEntryApi}. {@code REFUND} entries are stored with
 * a negative {@code amount} (this module's own sign convention, not a DB
 * requirement beyond {@code amount <> 0}) - see {@link LedgerEntry}'s
 * javadoc.
 */
@Service
public class LedgerEntryService implements LedgerEntryApi {

	private final LedgerEntryRepository ledgerEntryRepository;

	private final TenantContext tenantContext;

	public LedgerEntryService(LedgerEntryRepository ledgerEntryRepository, TenantContext tenantContext) {
		this.ledgerEntryRepository = ledgerEntryRepository;
		this.tenantContext = tenantContext;
	}

	@Override
	@Transactional
	public UUID recordPaymentConfirmed(UUID orderId, UUID paymentId, BigDecimal amount) {
		LedgerEntry entry = new LedgerEntry(tenantContext.getTenantId(), orderId, paymentId,
				LedgerEntryType.PAYMENT_CONFIRMED, amount, null);
		entry = ledgerEntryRepository.save(entry);
		return entry.getId();
	}

	@Override
	@Transactional
	public UUID recordRefund(UUID orderId, UUID paymentId, BigDecimal amount) {
		LedgerEntry confirmedEntry = ledgerEntryRepository.findConfirmedEntryForPayment(paymentId)
			.orElseThrow(() -> new IllegalStateException(
					"No PAYMENT_CONFIRMED ledger entry found for payment " + paymentId + " - cannot record refund"));
		LedgerEntry refundEntry = new LedgerEntry(tenantContext.getTenantId(), orderId, paymentId,
				LedgerEntryType.REFUND, amount.negate(), confirmedEntry.getId());
		refundEntry = ledgerEntryRepository.save(refundEntry);
		return refundEntry.getId();
	}

	@Override
	@Transactional(readOnly = true)
	public List<LedgerHistoryEntryView> findHistoryForOrders(List<UUID> orderIds) {
		return ledgerEntryRepository.findAllByOrderIdIn(orderIds).stream().map(LedgerEntryService::toView).toList();
	}

	@Override
	@Transactional(readOnly = true)
	public Page<LedgerHistoryEntryView> findDashboard(Pageable pageable) {
		return ledgerEntryRepository.findAllForDashboard(pageable).map(LedgerEntryService::toView);
	}

	private static LedgerHistoryEntryView toView(LedgerEntry entry) {
		return new LedgerHistoryEntryView(entry.getId(), entry.getOrderId(), entry.getPaymentId(),
				entry.getEntryType(), entry.getAmount(), entry.getReversesEntryId(), entry.getCreatedAt());
	}

}
