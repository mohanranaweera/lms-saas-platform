package com.lms.ledgersettlementmanagement.service;

import com.lms.common.tenant.TenantContext;
import com.lms.ledgersettlementmanagement.api.LedgerEntryApi;
import com.lms.ledgersettlementmanagement.api.LedgerEntryRecordedEvent;
import com.lms.ledgersettlementmanagement.api.LedgerHistoryEntryView;
import com.lms.ledgersettlementmanagement.domain.LedgerEntry;
import com.lms.ledgersettlementmanagement.domain.LedgerEntryType;
import com.lms.ledgersettlementmanagement.repository.LedgerEntryRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.context.ApplicationEventPublisher;
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

	private final ApplicationEventPublisher eventPublisher;

	public LedgerEntryService(LedgerEntryRepository ledgerEntryRepository, TenantContext tenantContext,
			ApplicationEventPublisher eventPublisher) {
		this.ledgerEntryRepository = ledgerEntryRepository;
		this.tenantContext = tenantContext;
		this.eventPublisher = eventPublisher;
	}

	@Override
	@Transactional
	public UUID recordPaymentConfirmed(UUID orderId, UUID paymentId, BigDecimal amount) {
		LedgerEntry entry = new LedgerEntry(tenantContext.getTenantId(), orderId, paymentId,
				LedgerEntryType.PAYMENT_CONFIRMED, amount, null);
		entry = ledgerEntryRepository.saveAndFlush(entry);
		publishRecorded(entry);
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
		refundEntry = ledgerEntryRepository.saveAndFlush(refundEntry);
		publishRecorded(refundEntry);
		return refundEntry.getId();
	}

	/**
	 * FIN-01: synchronous, same-transaction fan-out so the Finance account book
	 * posts this entry atomically with the ledger write (listener:
	 * {@code financeexpensemanagement.service.FinancePostingListener}). The
	 * event never changes the ledger row or any payment/enrollment state.
	 */
	private void publishRecorded(LedgerEntry entry) {
		eventPublisher.publishEvent(new LedgerEntryRecordedEvent(entry.getTenantId(), entry.getId(),
				entry.getOrderId(), entry.getPaymentId(), entry.getEntryType().name(), entry.getAmount(),
				entry.getReversesEntryId(), entry.getCreatedAt()));
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

	@Override
	@Transactional(readOnly = true)
	public List<LedgerHistoryEntryView> findAllDashboardEntries() {
		return ledgerEntryRepository.findAllForDashboardUnpaged().stream().map(LedgerEntryService::toView).toList();
	}

	private static LedgerHistoryEntryView toView(LedgerEntry entry) {
		return new LedgerHistoryEntryView(entry.getId(), entry.getOrderId(), entry.getPaymentId(),
				entry.getEntryType(), entry.getAmount(), entry.getReversesEntryId(), entry.getCreatedAt());
	}

}
