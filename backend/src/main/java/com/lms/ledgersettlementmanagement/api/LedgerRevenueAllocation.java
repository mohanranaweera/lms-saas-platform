package com.lms.ledgersettlementmanagement.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * ADR-029: the share of one ledger entry attributed to one course. {@code
 * amount} keeps the entry's sign (negative for a refund/chargeback).
 */
public record LedgerRevenueAllocation(UUID courseId, BigDecimal amount) {

}
