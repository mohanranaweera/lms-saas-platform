package com.lms.ledgersettlementmanagement.web.dto;

import com.lms.ledgersettlementmanagement.domain.LedgerEntryType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LedgerHistoryEntryResponse(UUID id, UUID orderId, UUID paymentId, LedgerEntryType entryType,
		BigDecimal amount, UUID reversesEntryId, Instant createdAt) {

}
