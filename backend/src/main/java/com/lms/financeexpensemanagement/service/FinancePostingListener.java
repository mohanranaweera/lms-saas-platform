package com.lms.financeexpensemanagement.service;

import com.lms.common.api.FieldError;
import com.lms.common.error.FieldValidationException;
import com.lms.common.tenant.TenantContext;
import com.lms.financeexpensemanagement.domain.FinanceEnums.RoutingMethod;
import com.lms.financeexpensemanagement.domain.FinanceEnums.SourceType;
import com.lms.financeexpensemanagement.domain.FinanceTransaction;
import com.lms.ledgersettlementmanagement.api.LedgerEntryRecordedEvent;
import com.lms.ledgersettlementmanagement.api.PaymentMethod;
import com.lms.ledgersettlementmanagement.api.WalletPayoutLinkedEvent;
import com.lms.paymentmanagement.api.ManualReceiptFact;
import com.lms.paymentmanagement.api.PaymentFact;
import com.lms.paymentmanagement.api.PaymentReportApi;
import com.lms.paymentmanagement.api.PaymentStatusApi;
import jakarta.persistence.PersistenceException;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * FIN-01: posts canonical money facts to the account book. Both listeners are
 * plain {@code @EventListener}s (synchronous, same thread, same transaction as
 * the publisher) so the ledger / wallet write and its posting commit or roll
 * back together. Neither listener decides anything about payments, enrollment
 * or entitlements.
 */
@Component
public class FinancePostingListener {

	private static final Logger log = LoggerFactory.getLogger(FinancePostingListener.class);

	private final FinancePostingService postingService;

	private final PaymentStatusApi paymentStatusApi;

	private final PaymentReportApi paymentReportApi;

	private final TenantContext tenantContext;

	public FinancePostingListener(FinancePostingService postingService, PaymentStatusApi paymentStatusApi,
			PaymentReportApi paymentReportApi, TenantContext tenantContext) {
		this.postingService = postingService;
		this.paymentStatusApi = paymentStatusApi;
		this.paymentReportApi = paymentReportApi;
		this.tenantContext = tenantContext;
	}

	/**
	 * Student payment confirmed / refunded. Cash methods only (GATEWAY,
	 * MANUAL_SLIP, MANUAL_RECEIPT); FREE / STAFF_GRANTED / zero amounts move no
	 * money and are skipped, matching the Teacher Income report's inclusion rule.
	 */
	@EventListener
	public void onLedgerEntryRecorded(LedgerEntryRecordedEvent event) {
		try {
			postLedgerEntry(event);
		}
		catch (DataAccessException | PersistenceException databaseFailure) {
			// The shared transaction is already aborted: let the ledger write roll back with it
			// (a confirmed payment must never commit with a half-written posting).
			throw databaseFailure;
		}
		catch (RuntimeException logicFailure) {
			// A Finance-side bug must not block a verified payment: nothing was written, the ledger
			// entry commits, and Reconcile (Transactions tab) backfills the missing posting.
			log.atError()
				.setMessage("finance.ledger_posting_skipped")
				.addKeyValue("tenantId", event.tenantId())
				.addKeyValue("ledgerEntryId", event.entryId())
				.setCause(logicFailure)
				.log();
		}
	}

	private void postLedgerEntry(LedgerEntryRecordedEvent event) {
		if (!event.tenantId().equals(tenantContext.getTenantId())) {
			log.warn("finance.ledger_event_tenant_mismatch entryId={}", event.entryId());
			return;
		}
		if (event.paymentId() == null || event.amount() == null || event.amount().signum() == 0) {
			return;
		}
		Optional<PaymentFact> fact = paymentStatusApi.findPaymentFacts(List.of(event.paymentId()))
			.stream()
			.findFirst();
		if (fact.isEmpty()) {
			log.warn("finance.ledger_event_without_payment entryId={}", event.entryId());
			return;
		}
		PaymentMethod method = PaymentMethod.fromGatewayReference(fact.get().gatewayReference());
		if (method == null || method == PaymentMethod.FREE || method == PaymentMethod.STAFF_GRANTED) {
			return;
		}
		String currency = fact.get().currency();
		if (event.isRefund()) {
			postingService.recordLedgerRefund(event.entryId(), event.reversesEntryId(), event.amount().abs(),
					currency, event.recordedAt(), "Student payment refund");
			return;
		}
		postingService.recordLedgerPayment(event.entryId(), event.amount(), currency, routingMethodOf(method,
				event), event.recordedAt(), "Student payment");
	}

	private RoutingMethod routingMethodOf(PaymentMethod method, LedgerEntryRecordedEvent event) {
		return switch (method) {
			case GATEWAY -> RoutingMethod.GATEWAY;
			case MANUAL_SLIP -> RoutingMethod.MANUAL_SLIP;
			case MANUAL_RECEIPT -> paymentReportApi.findManualReceiptsByPaymentIds(List.of(event.paymentId()))
				.stream()
				.findFirst()
				.map(ManualReceiptFact::paymentMethod)
				.map(code -> switch (code) {
					case "CASH" -> RoutingMethod.CASH;
					case "CARD" -> RoutingMethod.CARD;
					case "BANK_TRANSFER" -> RoutingMethod.BANK_TRANSFER;
					default -> RoutingMethod.OTHER;
				})
				.orElse(RoutingMethod.OTHER);
			default -> RoutingMethod.OTHER;
		};
	}

	/**
	 * ADR-036 section 6: a wallet Pay Out linked to a finance account posts
	 * exactly one outflow. Validation failures propagate and roll the wallet
	 * entry back; the publisher refuses to continue unless {@code markPosted} ran.
	 */
	@EventListener
	public void onWalletPayoutLinked(WalletPayoutLinkedEvent event) {
		if (!event.tenantId().equals(tenantContext.getTenantId())) {
			throw new IllegalStateException("Wallet payout event tenant does not match the request tenant");
		}
		if (event.categoryId() != null) {
			postingService.requireActiveCategory(event.categoryId(), "categoryId");
		}
		postingService.requireCurrency(event.currency(), "currency");
		if (event.accountId() == null) {
			throw new FieldValidationException("Account is required",
					List.of(new FieldError("accountId", "is required")));
		}
		FinanceTransaction posted = postingService.postExpense(event.accountId(), event.amount(),
				event.recordedAt().atZone(postingService.tenantZone()).toLocalDate(),
				"Teacher wallet payout: " + event.description(), event.categoryId(), SourceType.WALLET_ENTRY,
				event.settlementId(), null, event.actorId());
		event.markPosted(posted.getId());
	}

}
