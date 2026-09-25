package com.lms.ledgersettlementmanagement.web.dto;

import com.lms.ledgersettlementmanagement.api.LedgerHistoryEntryView;
import com.lms.ledgersettlementmanagement.api.PaymentMethod;
import com.lms.ledgersettlementmanagement.api.PaymentOperationalState;
import com.lms.ledgersettlementmanagement.domain.LedgerEntryType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Wave 6 §4 extends this response with {@code courseId}/{@code
 * courseTitle}/{@code billingPeriodId}/{@code operationalState}/{@code
 * method}/{@code reference} - additive, on-the-wire-optional fields (may be
 * {@code null} for a genuinely unenriched view, structurally unreachable
 * via any controller after this wave but kept nullable defensively).
 */
public record LedgerHistoryEntryResponse(UUID id, UUID orderId, UUID paymentId, LedgerEntryType entryType,
		BigDecimal amount, UUID reversesEntryId, Instant createdAt, UUID courseId, String courseTitle,
		UUID billingPeriodId, PaymentOperationalState operationalState, PaymentMethod method, String reference) {

	public static LedgerHistoryEntryResponse from(LedgerHistoryEntryView view) {
		return new LedgerHistoryEntryResponse(view.id(), view.orderId(), view.paymentId(), view.entryType(),
				view.amount(), view.reversesEntryId(), view.createdAt(), view.courseId(), view.courseTitle(),
				view.billingPeriodId(), view.operationalState(), view.method(), view.reference());
	}

}
