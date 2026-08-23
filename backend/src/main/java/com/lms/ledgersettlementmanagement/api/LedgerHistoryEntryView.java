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
 */
public record LedgerHistoryEntryView(UUID id, UUID orderId, UUID paymentId, LedgerEntryType entryType,
		BigDecimal amount, UUID reversesEntryId, Instant createdAt) {

}
