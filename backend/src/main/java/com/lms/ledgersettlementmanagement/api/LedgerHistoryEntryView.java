package com.lms.ledgersettlementmanagement.api;

import com.lms.ledgersettlementmanagement.domain.LedgerEntryType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Read-only projection of a {@code ledger_entry} row, returned by {@link
 * LedgerEntryApi}'s read methods. Deliberately not the JPA entity itself
 * (never exposed outside {@code ledger-settlement-management}), per
 * {@code .claude/rules/architecture.md}.
 *
 * <p>Wave 6 §4 extends this view with {@code courseId}/{@code
 * courseTitle}/{@code billingPeriodId}/{@code operationalState}/{@code
 * method}/{@code reference} - all {@code null} when returned directly by
 * {@link LedgerEntryApi} (this module's own ledger-only data has no
 * knowledge of courses/payments/slips), and populated only after {@code
 * LedgerViewEnrichmentService} resolves them via the appropriate cross
 * -module {@code api} calls. Callers that only need the base ledger fields
 * (e.g. {@code LedgerEntryService}'s own internal read methods) are
 * unaffected - the six new fields are purely additive.
 */
public record LedgerHistoryEntryView(UUID id, UUID orderId, UUID paymentId, LedgerEntryType entryType,
		BigDecimal amount, UUID reversesEntryId, Instant createdAt, UUID courseId, String courseTitle,
		UUID billingPeriodId, PaymentOperationalState operationalState, PaymentMethod method, String reference) {

	/** Pre-Wave-6 seven-arg shape - the six enrichment fields default to {@code null}, unenriched. */
	public LedgerHistoryEntryView(UUID id, UUID orderId, UUID paymentId, LedgerEntryType entryType,
			BigDecimal amount, UUID reversesEntryId, Instant createdAt) {
		this(id, orderId, paymentId, entryType, amount, reversesEntryId, createdAt, null, null, null, null, null,
				null);
	}

	/** Returns a copy of this view with the six Wave 6 §4 enrichment fields populated. */
	public LedgerHistoryEntryView withEnrichment(UUID courseId, String courseTitle, UUID billingPeriodId,
			PaymentOperationalState operationalState, PaymentMethod method, String reference) {
		return new LedgerHistoryEntryView(id, orderId, paymentId, entryType, amount, reversesEntryId, createdAt,
				courseId, courseTitle, billingPeriodId, operationalState, method, reference);
	}

}
